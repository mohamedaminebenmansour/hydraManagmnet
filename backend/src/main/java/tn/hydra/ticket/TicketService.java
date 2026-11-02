package tn.hydra.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class TicketService {

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> TERMINAL_NO_OVERDUE = Set.of("CLOSED", "CANCELLED", "AS_BUILT");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public TicketService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record TicketResponse(UUID id, String title, String description, String status,
                                 String priority, UUID projectId, String projectName,
                                 UUID assignedToId, String assignedToName,
                                 LocalDate startAt, LocalDate dueAt,
                                 Instant createdAt, UUID createdById, String createdByName,
                                 Instant resolvedAt, UUID resolvedById,
                                 UUID documentId, UUID processInstanceId,
                                 String type, String typeLabel,
                                 String category, String categoryLabel,
                                 String origin, String locationText,
                                 UUID ouvrageId, String ouvrageName,
                                 UUID responsiblePartyId, String responsiblePartyName,
                                 String statusLabel, boolean overdue) {}

    public record CatalogType(String code, String label, String icon, String colour,
                              Integer defaultDays, boolean requiresAfterPhoto,
                              List<Map<String, String>> categories,
                              List<Map<String, String>> statuses) {}

    private static final String SELECT_SQL = """
            select t.id, t.title, t.description, t.status, t.priority,
                   t.project_id, p.name as project_name,
                   t.assigned_to, au.full_name as assigned_to_name,
                   t.start_at, t.due_at,
                   t.created_at, t.created_by, cu.full_name as created_by_name,
                   t.resolved_at, t.resolved_by,
                   t.document_id, t.process_instance_id,
                   t.type, t.category, t.origin, t.location_text,
                   t.ouvrage_id, o.name as ouvrage_name,
                   t.responsible_party_id, rp.name as responsible_party_name,
                   t.resolution_note, t.closed_at, t.closed_by
            from ticket t
            left join project p on p.id = t.project_id
            left join app_user au on au.id = t.assigned_to
            left join app_user cu on cu.id = t.created_by
            left join ouvrage o on o.id = t.ouvrage_id
            left join party rp on rp.id = t.responsible_party_id
            """;

    // ══════════════════ 1. LIST WITH FILTERS ══════════════════

    /** Original 6-arg list (kept for backward compatibility; delegates with no type filter). */
    public List<TicketResponse> list(Authentication auth, UUID projectId, UUID assignedTo,
                                     String status, String priority, Boolean mine) {
        return list(auth, projectId, assignedTo, status, priority, mine, null);
    }

    public List<TicketResponse> list(Authentication auth, UUID projectId, UUID assignedTo,
                                     String status, String priority, Boolean mine, String type) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();

        StringBuilder sql = new StringBuilder(SELECT_SQL).append(" where t.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (projectId != null) { sql.append(" and t.project_id = ?"); args.add(projectId); }
        if (assignedTo != null) { sql.append(" and t.assigned_to = ?"); args.add(assignedTo); }
        if (status != null && !status.isBlank()) { sql.append(" and t.status = ?"); args.add(status.trim()); }
        if (priority != null && !priority.isBlank()) { sql.append(" and t.priority = ?"); args.add(priority.trim()); }
        if (type != null && !type.isBlank()) { sql.append(" and t.type = ?"); args.add(type.trim()); }
        if (Boolean.TRUE.equals(mine)) { sql.append(" and t.assigned_to = ?"); args.add(me.getId()); }
        sql.append(" order by t.created_at desc");

        List<TicketResponse> result = jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(TicketService::toResponse).toList();

        // IDOR guard: non-owner users only see tickets on projects they're members of
        // (or tickets with no project — those are org-wide).
        if (Boolean.TRUE.equals(me.getIsOwner())) return result;
        Set<UUID> accessibleProjects = new HashSet<>(jdbc.queryForList(
                "select project_id from project_member where user_id = ?", UUID.class, me.getId()));
        return result.stream()
                .filter(t -> t.projectId() == null || accessibleProjects.contains(t.projectId()))
                .toList();
    }

    // ══════════════════ 2. GET ONE (null → 404) ══════════════════

    public TicketResponse get(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_SQL + " where t.id = ? and t.org_id = ?", id, me.getOrgId());
        if (rows.isEmpty()) return null;
        // IDOR guard: non-owner users can only see tickets on projects they're members of
        // (or tickets with no project — those are org-wide).
        Map<String, Object> row = rows.get(0);
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            UUID ticketProjectId = (UUID) row.get("project_id");
            if (ticketProjectId != null) {
                Integer cnt = jdbc.queryForObject(
                        "select count(*) from project_member where project_id = ? and user_id = ?",
                        Integer.class, ticketProjectId, me.getId());
                if (cnt == null || cnt == 0) {
                    return null; // Not found → same as 404, no info leak
                }
            }
        }
        return toResponse(row);
    }

    // ══════════════════ 3. CREATE TICKET ══════════════════

    /**
     * Original 9-arg create — kept exactly so old callers (and the mock tests that
     * verify this insert SQL) keep working. A ticket created here is a
     * VISA_DOCUMENT in DRAFT (type column default + explicit 'DRAFT' status).
     */
    @Transactional
    public TicketResponse create(Authentication auth, String title, String description,
                                 UUID projectId, UUID assignedToId, String priority,
                                 LocalDate startAt, LocalDate dueAt, UUID documentId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(title))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le titre est obligatoire");
        if (projectId == null)
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le projet est obligatoire");
        requireProject(me, projectId, false);                 // must exist in org and be ACTIF
        if (assignedToId != null) requireUserInOrg(me, assignedToId);
        String prio = normalizePriority(priority);

        UUID id = jdbc.queryForObject("""
                insert into ticket (org_id, title, description, status, priority,
                                    project_id, assigned_to, start_at, due_at, created_by, document_id)
                values (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, me.getOrgId(), title.trim(), description,
                prio, projectId, assignedToId, startAt, dueAt, me.getId(), documentId);

        audit.log("TICKET_CREATED", "ticket", id,
                "{\"title\":\"" + jsonEscape(title.trim()) + "\"}");
        return reload(id, me.getOrgId());
    }

    /**
     * Type-aware create for signalements. Accepts the type and its category,
     * the RESERVE origin, a free-form location text, and links to an ouvrage
     * and a responsible party. Validation follows the design spec:
     * <ul>
     *   <li>type missing → VISA_DOCUMENT (so a call with no type behaves like the 9-arg create).</li>
     *   <li>category required for PROBLEME/RESERVE/DEMANDE and must belong to the type; forbidden for VISA_DOCUMENT.</li>
     *   <li>origin only for RESERVE; defaults to INTERNE.</li>
     *   <li>dueAt missing and the type has a default delay → today + default days.</li>
     *   <li>ouvrageId must belong to the same project + org; responsiblePartyId to the same org.</li>
     * </ul>
     */
    @Transactional
    public TicketResponse create(Authentication auth, String title, String description,
                                 UUID projectId, UUID assignedToId, String priority,
                                 LocalDate startAt, LocalDate dueAt, UUID documentId,
                                 String type, String category, String origin,
                                 String locationText, UUID ouvrageId, UUID responsiblePartyId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(title))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le titre est obligatoire");
        if (projectId == null)
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le projet est obligatoire");
        requireProject(me, projectId, false);                 // must exist in org and be ACTIF
        if (assignedToId != null) requireUserInOrg(me, assignedToId);
        String prio = normalizePriority(priority);

        TicketType t = TicketType.from(type);

        // category: required for the three signalement types (and must belong to the type);
        // forbidden for VISA_DOCUMENT.
        String categoryNorm = null;
        if (t == TicketType.VISA_DOCUMENT) {
            if (!isBlank(category))
                throw new BusinessRuleException("INVALID_CATEGORY",
                        "Un visa de document n'a pas de catégorie");
        } else {
            if (isBlank(category))
                throw new BusinessRuleException("REQUIRED_FIELDS",
                        "La catégorie est obligatoire pour ce type de signalement");
            categoryNorm = category.trim().toUpperCase();
            if (!t.hasCategory(categoryNorm))
                throw new BusinessRuleException("INVALID_CATEGORY",
                        "Catégorie invalide pour ce type de signalement");
        }

        // origin: only for RESERVE; default INTERNE.
        String originNorm = null;
        if (t == TicketType.RESERVE) {
            originNorm = isBlank(origin) ? "INTERNE" : origin.trim().toUpperCase();
            if (!originNorm.equals("CLIENT") && !originNorm.equals("INTERNE"))
                throw new BusinessRuleException("INVALID_ORIGIN",
                        "Origine invalide : CLIENT ou INTERNE");
        } else if (!isBlank(origin)) {
            throw new BusinessRuleException("INVALID_ORIGIN",
                    "L'origine ne s'applique qu'à une réserve");
        }

        if (ouvrageId != null) requireOuvrageInProject(me, ouvrageId, projectId);
        if (responsiblePartyId != null) requirePartyInOrg(me, responsiblePartyId);

        // due date: if missing and the type has a default delay, today + default days
        LocalDate due = dueAt;
        if (due == null && t.defaultDays() != null) {
            due = LocalDate.now().plusDays(t.defaultDays());
        }

        TicketStatus initial = TicketWorkflow.initialStatus(t);
        UUID id = jdbc.queryForObject("""
                insert into ticket (org_id, title, description, status, priority,
                                    project_id, assigned_to, start_at, due_at, created_by, document_id,
                                    type, category, origin, location_text, ouvrage_id, responsible_party_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, me.getOrgId(), title.trim(), description,
                initial.name(), prio, projectId, assignedToId, startAt, due, me.getId(), documentId,
                t.name(), categoryNorm, originNorm, locationText, ouvrageId, responsiblePartyId);

        audit.log("TICKET_CREATED", "ticket", id,
                "{\"title\":\"" + jsonEscape(title.trim()) + "\",\"type\":\"" + t.name() + "\"}");
        return reload(id, me.getOrgId());
    }

    // ══════════════════ 4. PARTIAL UPDATE ══════════════════

    @Transactional
    public TicketResponse update(Authentication auth, UUID id, String title, String description,
                                 UUID projectId, UUID assignedToId, String priority,
                                 LocalDate startAt, LocalDate dueAt, UUID documentId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> current = requireTicket(me, id);

        if (title != null && isBlank(title))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le titre est obligatoire");
        if (projectId != null && !projectId.equals(current.get("project_id")))
            requireProject(me, projectId, false);
        if (assignedToId != null && !assignedToId.equals(current.get("assigned_to")))
            requireUserInOrg(me, assignedToId);
        if (priority != null && !PRIORITIES.contains(priority))
            throw new BusinessRuleException("INVALID_PRIORITY", "Priorité invalide");

        // null body field = leave unchanged (partial update)
        StringBuilder sql = new StringBuilder("update ticket set updated_at = now()");
        List<Object> args = new ArrayList<>();
        if (title != null)        { sql.append(", title = ?");        args.add(title.trim()); }
        if (description != null)  { sql.append(", description = ?");  args.add(description); }
        if (projectId != null)    { sql.append(", project_id = ?");   args.add(projectId); }
        if (assignedToId != null) { sql.append(", assigned_to = ?");  args.add(assignedToId); }
        if (priority != null)     { sql.append(", priority = ?");     args.add(priority); }
        if (startAt != null)      { sql.append(", start_at = ?");     args.add(startAt); }
        if (dueAt != null)        { sql.append(", due_at = ?");       args.add(dueAt); }
        if (documentId != null)   { sql.append(", document_id = ?");  args.add(documentId); }
        sql.append(" where id = ? and org_id = ?");
        args.add(id);
        args.add(me.getOrgId());
        jdbc.update(sql.toString(), args.toArray());

        audit.log("TICKET_UPDATED", "ticket", id, null);
        return reload(id, me.getOrgId());
    }

    // ══════════════════ 5. STATUS TRANSITIONS (workflow) ══════════════════

    /**
     * Move a ticket to the given status if the transition is allowed by
     * {@link TicketWorkflow#next(TicketType, TicketStatus)} for this ticket's
     * type. VISA_DOCUMENT therefore keeps its transmittal workflow; the three
     * new types follow OPEN → IN_PROGRESS → RESOLVED → CLOSED (+ CANCELLED).
     *
     * <p>Role rules (who may move a status), resolution notes and « après »
     * photos are enforced in a later task. Reaching RESOLVED (or AS_BUILT for
     * a visa) stamps resolved_at/by; reaching CLOSED stamps closed_at/by.
     */
    @Transactional
    public TicketResponse setStatus(Authentication auth, UUID id, String targetCode) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);

        TicketType type = TicketType.from((String) t.get("type"));
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target;
        try {
            target = TicketStatus.from(targetCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_STATUS", "Statut inconnu : " + targetCode);
        }

        if (!TicketWorkflow.next(type, current).contains(target))
            throw new BusinessRuleException("INVALID_STATUS",
                    "Transition non autorisée : " + current + " → " + target.name());

        if (target == TicketStatus.RESOLVED || target == TicketStatus.AS_BUILT) {
            jdbc.update("""
                    update ticket set status = ?, resolved_at = now(), resolved_by = ?,
                                      updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), me.getId(), id, me.getOrgId());
        } else if (target == TicketStatus.CLOSED) {
            jdbc.update("""
                    update ticket set status = ?, closed_at = now(), closed_by = ?,
                                      updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), me.getId(), id, me.getOrgId());
        } else {
            jdbc.update("""
                    update ticket set status = ?, updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), id, me.getOrgId());
        }
        audit.log("TICKET_STATUS_CHANGED", "ticket", id,
                "{\"from\":\"" + current + "\",\"to\":\"" + target.name() + "\"}");
        return reload(id, me.getOrgId());
    }

    /** Possible next statuses for the UI (empty when terminal). */
    public List<String> availableTransitions(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        return TicketWorkflow.next(type, current).stream().map(Enum::name).sorted().toList();
    }

    // ══════════════════ 5b. CLOSE (simple task semantics) ══════════════════

    /**
     * Close a ticket from any non-terminal status (simple task/issue semantics —
     * no need to walk the full transmittal workflow). Sets the terminal status,
     * resolved_at and resolved_by. Closing an already-closed ticket is a 409.
     */
    @Transactional
    public TicketResponse close(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        String current = (String) t.get("status");
        if (TicketStatus.AS_BUILT.name().equals(current))
            throw new BusinessRuleException("ALREADY_CLOSED", "Ce ticket est déjà clôturé");
        jdbc.update("""
                update ticket set status = 'AS_BUILT', resolved_at = now(), resolved_by = ?,
                                  updated_at = now()
                where id = ? and org_id = ?
                """, me.getId(), id, me.getOrgId());
        audit.log("TICKET_CLOSED", "ticket", id,
                "{\"from\":\"" + current + "\",\"to\":\"AS_BUILT\"}");
        return reload(id, me.getOrgId());
    }

    // ══════════════════ 6. LINKED DOCUMENTS ══════════════════

    /** Documents linked to this ticket (via document.ticket_id). */
    public List<Map<String, Object>> documentsOf(Authentication auth, UUID ticketId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireTicket(me, ticketId);
        return jdbc.queryForList("""
                select d.id, d.reference, d.title, d.doc_type, d.current_rev, d.status,
                       d.created_at, d.created_by, u.full_name as created_by_name
                from document d
                left join app_user u on u.id = d.created_by
                where d.ticket_id = ? and d.org_id = ? and d.deleted_at is null
                order by d.reference
                """, ticketId, me.getOrgId());
    }

    // ══════════════════ 7. CATALOG (labels for the UI) ══════════════════

    /**
     * The four signalement types with their French label, icon, colour, default
     * delay, après-photo requirement, ordered categories, and the statuses
     * valid for that type. The frontend reads labels only from here.
     */
    public List<CatalogType> catalog() {
        List<CatalogType> out = new ArrayList<>();
        for (TicketType t : TicketType.values()) {
            List<Map<String, String>> cats = new ArrayList<>();
            t.categories().forEach((code, label) -> cats.add(catEntry(code, label)));
            out.add(new CatalogType(t.name(), t.label(), t.icon(), t.colour(),
                    t.defaultDays(), t.requiresAfterPhoto(), cats, statusesFor(t)));
        }
        return out;
    }

    private static Map<String, String> catEntry(String code, String label) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        return m;
    }

    private static List<Map<String, String>> statusesFor(TicketType t) {
        List<TicketStatus> order = t == TicketType.VISA_DOCUMENT
                ? List.of(TicketStatus.DRAFT, TicketStatus.IN_REVIEW, TicketStatus.APPROVED,
                          TicketStatus.IFC, TicketStatus.AS_BUILT)
                : List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED,
                          TicketStatus.CLOSED, TicketStatus.CANCELLED);
        List<Map<String, String>> out = new ArrayList<>();
        for (TicketStatus s : order) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", s.name());
            m.put("label", s.label());
            out.add(m);
        }
        return out;
    }

    // ══════════════════ VALIDATION HELPERS ══════════════════

    /** Same-org ticket row or ResourceNotFoundException(TICKET_NOT_FOUND → 404 JSON). */
    private Map<String, Object> requireTicket(AppUser me, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, title, status, type, project_id, assigned_to from ticket where id = ? and org_id = ?",
                id, me.getOrgId());
        if (rows.isEmpty())
            throw new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket introuvable");
        return rows.get(0);
    }

    /** Re-read after this user's own write (already checked by requireTicket/requireProject): no membership check. */
    private TicketResponse reload(UUID id, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_SQL + " where t.id = ? and t.org_id = ?", id, orgId);
        if (rows.isEmpty())
            throw new ResourceNotFoundException("TICKET_NOT_FOUND", "Ticket introuvable");
        return toResponse(rows.get(0));
    }

    private void requireProject(AppUser me, UUID projectId, boolean allowClosed) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status from project where id = ? and org_id = ?", projectId, me.getOrgId());
        if (rows.isEmpty())
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");
        if (!allowClosed && !"ACTIF".equals(rows.get(0).get("status")))
            throw new BusinessRuleException("PROJECT_CLOSED", "Ce projet est clôturé");
    }

    private void requireUserInOrg(AppUser me, UUID userId) {
        List<UUID> found = jdbc.queryForList(
                "select id from app_user where id = ? and org_id = ?", UUID.class, userId, me.getOrgId());
        if (found.isEmpty())
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");
    }

    /** Ouvrage must belong to the same project + org (foreign ouvrage → 400). */
    private void requireOuvrageInProject(AppUser me, UUID ouvrageId, UUID projectId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from ouvrage where id = ? and org_id = ? and project_id = ?",
                Integer.class, ouvrageId, me.getOrgId(), projectId);
        if (cnt == null || cnt == 0)
            throw new BusinessRuleException("OUVRAGE_NOT_FOUND",
                    "Ouvrage introuvable dans ce chantier");
    }

    /** Responsible party must belong to the same org (foreign party → 400). */
    private void requirePartyInOrg(AppUser me, UUID partyId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from party where id = ? and org_id = ?",
                Integer.class, partyId, me.getOrgId());
        if (cnt == null || cnt == 0)
            throw new BusinessRuleException("PARTY_NOT_FOUND",
                    "Tiers introuvable dans cette organisation");
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) return "MEDIUM";
        if (!PRIORITIES.contains(priority))
            throw new BusinessRuleException("INVALID_PRIORITY", "Priorité invalide");
        return priority;
    }

    // ══════════════════ HELPERS ══════════════════

    private static TicketResponse toResponse(Map<String, Object> r) {
        String status = (String) r.get("status");
        String type = (String) r.get("type");
        TicketType tt = TicketType.from(type);
        TicketStatus ts = TicketStatus.from(status);
        String category = (String) r.get("category");
        LocalDate due = toLocalDate(r.get("due_at"));
        boolean overdue = due != null && due.isBefore(LocalDate.now())
                && !TERMINAL_NO_OVERDUE.contains(status);
        return new TicketResponse(
                (UUID) r.get("id"),
                (String) r.get("title"),
                (String) r.get("description"),
                status,
                (String) r.get("priority"),
                (UUID) r.get("project_id"),
                (String) r.get("project_name"),
                (UUID) r.get("assigned_to"),
                (String) r.get("assigned_to_name"),
                toLocalDate(r.get("start_at")),
                due,
                toInstant(r.get("created_at")),
                (UUID) r.get("created_by"),
                (String) r.get("created_by_name"),
                toInstant(r.get("resolved_at")),
                (UUID) r.get("resolved_by"),
                (UUID) r.get("document_id"),
                (UUID) r.get("process_instance_id"),
                type,
                tt.label(),
                category,
                tt.categoryLabel(category),
                (String) r.get("origin"),
                (String) r.get("location_text"),
                (UUID) r.get("ouvrage_id"),
                (String) r.get("ouvrage_name"),
                (UUID) r.get("responsible_party_id"),
                (String) r.get("responsible_party_name"),
                ts.label(),
                overdue);
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toLocalDate();
        if (v instanceof String s && !s.isBlank()) return LocalDate.parse(s.substring(0, 10));
        return null;
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
