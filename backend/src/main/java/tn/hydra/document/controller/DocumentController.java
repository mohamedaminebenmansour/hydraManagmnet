package tn.hydra.document.controller;

import org.springframework.core.io.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.common.PageResponse;
import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.dto.CreateDocumentRequest;
import tn.hydra.document.dto.DocumentResponse;
import tn.hydra.document.dto.DocumentSearchQuery;
import tn.hydra.document.dto.LinkTicketRequest;
import tn.hydra.document.dto.RevisionResponse;
import tn.hydra.document.dto.UpdateDocumentRequest;
import tn.hydra.document.service.DocumentService;
import tn.hydra.document.service.DownloadFile;
import tn.hydra.document.service.RevisionService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documents;
    private final RevisionService revisions;

    public DocumentController(DocumentService documents, RevisionService revisions) {
        this.documents = documents; this.revisions = revisions;
    }

    // ── combined upload: create Document + first revision (multipart) ─────
    @PostMapping("/api/projects/{projectId}/folders/{folderId}/documents/upload")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable UUID projectId,
            @PathVariable UUID folderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam String title,
            @RequestParam DocType docType,
            @RequestParam(required = false) String revision,
            @RequestParam(required = false) UUID ticketId,
            @RequestParam(required = false) List<String> tagNames,
            @RequestParam(required = false) String description,
            Authentication auth) {
        log.info("[DOC-UPLOAD] entry projectId={} folderId={} title={} docType={} filename={} size={} user={}",
                projectId, folderId, title, docType,
                file == null ? null : file.getOriginalFilename(),
                file == null ? -1L : file.getSize(),
                auth == null ? "anonymous" : auth.getName());
        try {
            DocumentResponse created = documents.upload(auth, folderId, title, docType,
                    revision, ticketId, tagNames, description, file);
            log.info("[DOC-UPLOAD] OK projectId={} folderId={} documentId={} reference={}",
                    projectId, folderId, created.id(), created.reference());
            return ResponseEntity.status(201).body(created);
        } catch (Exception e) {
            log.error("[DOC-UPLOAD] FAILED projectId={} folderId={} title={}: {} — {}",
                    projectId, folderId, title, e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        }
    }

    // ── create (metadata only, no file) ───────────────────────────────────
    @PostMapping("/api/projects/{projectId}/folders/{folderId}/documents")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<DocumentResponse> create(@PathVariable UUID projectId,
                                                   @PathVariable UUID folderId,
                                                   @RequestBody CreateDocumentRequest body,
                                                   Authentication auth) {
        DocumentResponse created = documents.create(auth, folderId, body);
        return ResponseEntity.status(201).body(created);
    }

    // ── search + master register ──────────────────────────────────────────
    @GetMapping("/api/projects/{projectId}/documents")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public PageResponse<DocumentResponse> search(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) DocType type,
            @RequestParam(required = false) DocStatus status,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String revision,
            @RequestParam(required = false) UUID uploaderId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            Pageable pageable,
            Authentication auth) {
        DocumentSearchQuery query = new DocumentSearchQuery(folderId, projectId, type, status,
                tagId, q, dateFrom, dateTo, uploaderId, revision);
        return documents.search(auth, query, pageable);
    }

    @GetMapping("/api/projects/{projectId}/documents/register")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public PageResponse<DocumentResponse> register(@PathVariable UUID projectId,
                                                   Pageable pageable,
                                                   Authentication auth) {
        return documents.register(auth, projectId, pageable);
    }

    // ── single document ──────────────────────────────────────────────────
    @GetMapping("/api/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public DocumentResponse get(@PathVariable UUID id, Authentication auth) {
        return documents.get(auth, id);
    }

    @GetMapping("/api/documents/by-reference/{reference}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public DocumentResponse byReference(@PathVariable String reference, Authentication auth) {
        return documents.byReference(auth, reference);
    }

    @PutMapping("/api/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentResponse update(@PathVariable UUID id,
                                   @RequestBody UpdateDocumentRequest body,
                                   Authentication auth) {
        return documents.update(auth, id, body);
    }

    @DeleteMapping("/api/documents/{id}")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        documents.softDelete(auth, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/documents/{id}/link-ticket")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public DocumentResponse linkTicket(@PathVariable UUID id,
                                       @RequestBody LinkTicketRequest body,
                                       Authentication auth) {
        return documents.linkTicket(auth, id, body);
    }

    // ── revisions ────────────────────────────────────────────────────────
    @GetMapping("/api/documents/{id}/revisions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_READ')")
    public List<RevisionResponse> listRevisions(@PathVariable UUID id, Authentication auth) {
        return revisions.list(auth, id);
    }

    @PostMapping("/api/documents/{id}/revisions")
    @PreAuthorize("hasAuthority('PERM_DOCUMENT_UPLOAD')")
    public ResponseEntity<RevisionResponse> uploadRevision(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "changeNote", required = false) String changeNote,
            @RequestParam(value = "revision", required = false) String revision,
            Authentication auth) {
        RevisionResponse created = revisions.uploadNewRevision(auth, id, file, changeNote, revision);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/api/documents/{id}/revisions/{revId}/download")
    @PreAuthorize("hasAnyAuthority('PERM_DOCUMENT_READ','PERM_DOCUMENT_DOWNLOAD')")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @PathVariable UUID revId,
                                             Authentication auth) {
        DownloadFile file = revisions.download(auth, id, revId);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        // Phase B (DOC-ACTIONS): if revision.mimeType is null/blank/octet-stream
        // (common for legacy/curl uploads), infer the real Content-Type from the
        // fileName extension. Without this, a PDF served as octet-stream triggers
        // a download instead of inline preview even when window.open(blobUrl) is used.
        String effectiveContentType = resolveContentType(file.contentType(), file.fileName());
        // inline for browser-previewable types (PDF + images) so a new-tab/window.open()
        // shows the file; attachment otherwise so the browser saves it with the correct
        // filename. Filename header is RFC 5987.
        String disposition = isInlinePreviewable(effectiveContentType)
                ? "inline; filename*=UTF-8''" + encoded
                : "attachment; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(effectiveContentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(file.resource());
    }

    /** PDF and any raster image type can be inlined by the browser. */
    private static boolean isInlinePreviewable(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase().trim();
        int semi = ct.indexOf(';');
        if (semi >= 0) { ct = ct.substring(0, semi).trim(); }
        return ct.equals("application/pdf")
                || ct.equals("application/x-pdf")
                || ct.startsWith("image/");
    }

    /**
     * Phase B (DOC-ACTIONS): resolve the effective Content-Type.
     * Returns the stored mimeType when it is informative; otherwise falls back
     * to a filename-extension lookup. Always returns a non-null, non-blank
     * MIME (defaults to {@code application/octet-stream} when nothing fits).
     */
    private static String resolveContentType(String stored, String fileName) {
        if (stored != null) {
            String ct = stored.toLowerCase().trim();
            int semi = ct.indexOf(';');
            if (semi >= 0) { ct = ct.substring(0, semi).trim(); }
            if (!ct.isEmpty() && !ct.equals("application/octet-stream")
                    && !ct.equals("application/x-www-form-urlencoded")
                    && !ct.equals("multipart/form-data")) {
                return ct;
            }
        }
        String inferred = inferContentTypeFromExtension(fileName);
        return inferred != null ? inferred : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /** Filename-extension → MIME map for the types HYDRA accepts on upload. */
    private static String inferContentTypeFromExtension(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc"))  return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt"))  return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".zip"))  return "application/zip";
        if (lower.endsWith(".msg") || lower.endsWith(".eml")) return "application/vnd.ms-outlook";
        if (lower.endsWith(".dwg") || lower.endsWith(".dxf")) return "application/acad";
        if (lower.endsWith(".ifc"))  return "application/x-step";
        if (lower.endsWith(".psd"))   return "image/vnd.adobe.photoshop";
        return null;
    }
}