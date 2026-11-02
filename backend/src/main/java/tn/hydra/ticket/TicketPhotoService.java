package tn.hydra.ticket;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.dto.DocumentResponse;
import tn.hydra.document.service.DocumentService;
import tn.hydra.document.service.FileStorage;
import tn.hydra.document.service.FolderFilingService;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.*;

/**
 * Photos attached to a signalement. Each photo is stored as a document through
 * {@link DocumentService#upload} (so folder rights and local/S3 storage are
 * respected), filed in the folder chosen by type, and linked to the ticket by a
 * {@code ticket_attachment} row with a phase (BEFORE / AFTER / OTHER).
 *
 * <p>One user action = one transaction (AI-RULES 14): {@link #createWithPhotos}
 * creates the ticket and all photos atomically; if one photo fails, no ticket is
 * created and any file already written to storage is deleted on rollback (same
 * pattern as {@code BordereauImportService.deleteStoredFileOnRollback}).
 */
@Service
public class TicketPhotoService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5 Mo
    private static final int MAX_PHOTOS = 10;

    private final TicketService tickets;
    private final DocumentService documents;
    private final FolderFilingService folderFiling;
    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    @PersistenceContext
    private EntityManager em;

    public TicketPhotoService(TicketService tickets, DocumentService documents,
                              FolderFilingService folderFiling, FileStorage storage,
                              JdbcTemplate jdbc, AppUserRepository users) {
        this.tickets = tickets; this.documents = documents;
        this.folderFiling = folderFiling; this.storage = storage;
        this.jdbc = jdbc; this.users = users;
    }

    public record PhotoResponse(UUID id, UUID documentId, String phase, String title,
                                UUID createdBy, Instant createdAt, String downloadUrl) {}

    // ══════════════════ 1. CREATE WITH PHOTOS (one transaction) ══════════════════

    /**
     * Create a ticket and attach 0..10 BEFORE photos in ONE transaction. Photos
     * are validated up front (type / size / count); if any is invalid, no ticket
     * is created and nothing is stored. If a photo fails while being stored, the
     * transaction rolls back (no ticket row) and every file already written is
     * deleted by the rollback synchronizations registered in {@link #attachPhoto}.
     */
    @Transactional
    public TicketService.TicketResponse createWithPhotos(Authentication auth,
                                                          String title, String description,
                                                          UUID projectId, UUID assignedToId, String priority,
                                                          java.time.LocalDate startAt, java.time.LocalDate dueAt,
                                                          UUID documentId,
                                                          String type, String category, String origin,
                                                          String locationText, UUID ouvrageId, UUID responsiblePartyId,
                                                          List<MultipartFile> photos) {
        if (photos != null) {
            if (photos.size() > MAX_PHOTOS)
                throw new BusinessRuleException("TOO_MANY_PHOTOS", "10 photos maximum par signalement");
            for (MultipartFile f : photos) validatePhoto(f);
        }

        // create the ticket (also publishes the TICKET_CREATED domain event)
        TicketService.TicketResponse t = tickets.create(auth, title, description, projectId,
                assignedToId, priority, startAt, dueAt, documentId,
                type, category, origin, locationText, ouvrageId, responsiblePartyId);

        if (photos != null) {
            for (MultipartFile f : photos) {
                attachPhoto(auth, t.id(), t.projectId(), t.type(), t.title(), f, "BEFORE");
            }
        }
        return t;
    }

    // ══════════════════ 2. ADD ONE PHOTO ══════════════════

    /** Attach one photo (phase BEFORE/AFTER/OTHER) to an existing ticket. */
    @Transactional
    public PhotoResponse addPhoto(Authentication auth, UUID ticketId, MultipartFile file, String phase) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        validatePhoto(file);
        String phaseNorm = normalizePhase(phase);
        requireTicketExists(me, ticketId);

        Integer existing = jdbc.queryForObject(
                "select count(*) from ticket_attachment where ticket_id = ? and org_id = ?",
                Integer.class, ticketId, me.getOrgId());
        if (existing == null) existing = 0;
        if (existing + 1 > MAX_PHOTOS)
            throw new BusinessRuleException("TOO_MANY_PHOTOS", "10 photos maximum par signalement");

        Map<String, Object> t = jdbc.queryForList(
                "select project_id, type, title from ticket where id = ? and org_id = ?",
                ticketId, me.getOrgId()).stream().findFirst().orElseThrow();
        UUID projectId = (UUID) t.get("project_id");
        String typeCode = (String) t.get("type");
        String ticketTitle = (String) t.get("title");
        return attachPhoto(auth, ticketId, projectId, typeCode, ticketTitle, file, phaseNorm);
    }

    // ══════════════════ 3. LIST PHOTOS ══════════════════

    public List<PhotoResponse> listPhotos(Authentication auth, UUID ticketId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireTicketExists(me, ticketId);
        return jdbc.queryForList("""
                select ta.id, ta.document_id, ta.phase, d.title, ta.created_by, ta.created_at,
                       dr.id as revision_id
                from ticket_attachment ta
                join document d on d.id = ta.document_id
                left join document_revision dr on dr.document_id = d.id and dr.rev_label = d.current_rev
                where ta.ticket_id = ? and ta.org_id = ?
                order by ta.created_at, ta.id
                """, ticketId, me.getOrgId()).stream()
                .map(TicketPhotoService::toPhotoResponse).toList();
    }

    // ══════════════════ 4. DELETE PHOTO ══════════════════

    /** Only the uploader, and only while the ticket is not CLOSED/CANCELLED. */
    @Transactional
    public void deletePhoto(Authentication auth, UUID ticketId, UUID attachmentId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> att = requireAttachment(me, ticketId, attachmentId);
        if (!me.getId().equals(att.get("created_by")))
            throw new AccessDeniedException("Seul l'auteur de la photo peut la supprimer");
        String status = jdbc.queryForObject(
                "select status from ticket where id = ? and org_id = ?", String.class, ticketId, me.getOrgId());
        if ("CLOSED".equals(status) || "CANCELLED".equals(status))
            throw new BusinessRuleException("TICKET_CLOSED",
                    "Impossible de supprimer une photo d'un signalement clos ou annulé");
        jdbc.update("delete from ticket_attachment where id = ? and org_id = ?", attachmentId, me.getOrgId());
    }

    // ══════════════════ HELPERS ══════════════════

    /** Store one photo as a document + create the ticket_attachment row + register rollback cleanup. */
    private PhotoResponse attachPhoto(Authentication auth, UUID ticketId, UUID projectId,
                                      String typeCode, String ticketTitle, MultipartFile file, String phase) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        TicketType t = TicketType.from(typeCode);
        UUID folderId = folderFiling.folderFor(projectId, folderCodeFor(t));
        String title = t.label() + " — " + (ticketTitle == null ? "" : ticketTitle) + " — " + phaseLabel(phase);
        DocumentResponse doc = documents.upload(auth, folderId, title, DocType.PHOTO,
                null, ticketId, null, null, file);
        em.flush(); // make the revision row visible to the JDBC read below

        List<Map<String, Object>> revs = jdbc.queryForList(
                "select id, storage_key from document_revision where document_id = ? and rev_label = ?",
                doc.id(), doc.currentRev());
        if (revs.isEmpty())
            throw new IllegalStateException("photo revision not found after flush");
        UUID revId = (UUID) revs.get(0).get("id");
        String storageKey = (String) revs.get(0).get("storage_key");
        registerDeleteOnRollback(storageKey);

        Map<String, Object> att = jdbc.queryForMap("""
                insert into ticket_attachment (org_id, ticket_id, document_id, phase, created_by)
                values (?, ?, ?, ?, ?) returning id, created_at
                """, me.getOrgId(), ticketId, doc.id(), phase, me.getId());
        UUID attachmentId = (UUID) att.get("id");
        Instant createdAt = toInstant(att.get("created_at"));
        String downloadUrl = "/api/documents/" + doc.id() + "/revisions/" + revId + "/download";
        return new PhotoResponse(attachmentId, doc.id(), phase, title, me.getId(), createdAt, downloadUrl);
    }

    private void validatePhoto(MultipartFile f) {
        if (f == null || f.isEmpty())
            throw new BusinessRuleException("PHOTO_REQUIRED", "Une photo est requise");
        String ct = f.getContentType();
        if (ct == null || !ALLOWED_CONTENT_TYPES.contains(ct.toLowerCase()))
            throw new BusinessRuleException("INVALID_PHOTO_TYPE",
                    "Seules les images JPEG, PNG ou WebP sont acceptées");
        if (f.getSize() > MAX_BYTES)
            throw new BusinessRuleException("PHOTO_TOO_LARGE", "Photo trop lourde (max 5 Mo)");
    }

    private static String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) return "OTHER";
        String p = phase.trim().toUpperCase();
        if (!Set.of("BEFORE", "AFTER", "OTHER").contains(p))
            throw new BusinessRuleException("INVALID_PHASE", "Phase invalide : BEFORE, AFTER ou OTHER");
        return p;
    }

    private static String folderCodeFor(TicketType t) {
        return switch (t) {
            case RESERVE                     -> "04_QUAL.RESERVES";
            case PROBLEME, DEMANDE           -> "03_EXEC.PHOTOS";
            case VISA_DOCUMENT               -> "02_TECH.PLANS";
        };
    }

    private static String phaseLabel(String phase) {
        return switch (phase) {
            case "BEFORE" -> "avant";
            case "AFTER"  -> "après";
            default       -> "autre";
        };
    }

    /**
     * Nothing half-saved: if the surrounding transaction rolls back, delete the
     * stored object too (storage is not transactional). Same rule as
     * BordereauImportService.deleteStoredFileOnRollback.
     */
    private void registerDeleteOnRollback(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try { storage.delete(storageKey); } catch (RuntimeException ignored) { /* best effort */ }
            }
        });
    }

    private void requireTicketExists(AppUser me, UUID ticketId) {
        Integer c = jdbc.queryForObject(
                "select count(*) from ticket where id = ? and org_id = ?", Integer.class, ticketId, me.getOrgId());
        if (c == null || c == 0)
            throw new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket introuvable");
    }

    private Map<String, Object> requireAttachment(AppUser me, UUID ticketId, UUID attachmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, ticket_id, created_by from ticket_attachment where id = ? and org_id = ?",
                attachmentId, me.getOrgId());
        if (rows.isEmpty())
            throw new ResourceNotFoundException("ATTACHMENT_NOT_FOUND", "Photo introuvable");
        Map<String, Object> att = rows.get(0);
        if (!ticketId.equals(att.get("ticket_id")))
            throw new ResourceNotFoundException("ATTACHMENT_NOT_FOUND", "Photo introuvable");
        return att;
    }

    private static PhotoResponse toPhotoResponse(Map<String, Object> r) {
        UUID docId = (UUID) r.get("document_id");
        UUID revId = (UUID) r.get("revision_id");
        String downloadUrl = revId == null ? null
                : "/api/documents/" + docId + "/revisions/" + revId + "/download";
        return new PhotoResponse(
                (UUID) r.get("id"),
                docId,
                (String) r.get("phase"),
                (String) r.get("title"),
                (UUID) r.get("created_by"),
                toInstant(r.get("created_at")),
                downloadUrl);
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}
