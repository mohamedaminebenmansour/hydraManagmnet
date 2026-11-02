package tn.hydra.ticket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.event.DomainEventService;
import tn.hydra.event.EventType;
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

    /** Field-injected so the 3-arg constructor (used by the mock TicketServiceTest) stays unchanged. */
    @Autowired(required = false)
    private DomainEventService domainEvents;

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
        insertComment(me.getOrgId(), id, me.getId(), "SYSTEM", "Signalement créé", null, null);
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
        if (domainEvents != null)
            domainEvents.publish(me.getOrgId(), EventType.TICKET_CREATED, "TICKET", id, projectId,
                    DomainEventService.payload("type", t.name(), "projectId", projectId));
        insertComment(me.getOrgId(), id, me.getId(), "SYSTEM", "Signalement créé", null, null);
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
        insertComment(me.getOrgId(), id, me.getId(), "STATUS_CHANGE", null, current.name(), target.name());
        if (domainEvents != null)
            domainEvents.publish(me.getOrgId(), EventType.TICKET_STATUS_CHANGED, "TICKET", id,
                    (UUID) t.get("project_id"),
                    DomainEventService.payload("from", current.name(), "to", target.name()));
        return reload(id, me.getOrgId());
    }

    /** VISA-only guard for POST /{id}/status: signalements must use the named actions. */
    public void assertVisaForSetStatus(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        if (TicketType.from((String) t.get("type")) != TicketType.VISA_DOCUMENT)
            throw new BusinessRuleException("USE_ACTIONS", "Utilisez les actions du signalement");
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

    // ══════════════════ 8. SEARCH ══════════════════

    /** Same columns as SELECT_SQL plus a per-ticket photo_count (for the search result items). */
    private static final String SEARCH_SELECT_SQL = SELECT_SQL.replace(
            "t.resolution_note, t.closed_at, t.closed_by",
            "t.resolution_note, t.closed_at, t.closed_by,\n"
                    + "                   (select count(*) from ticket_attachment ta where ta.ticket_id = t.id) as photo_count");

    /**
     * Paginated search. {@code mine} = assigned to me, or status RESOLVED and I
     * am the creator. {@code overdue} = due_at before today and status not in
     * CLOSED/CANCELLED/AS_BUILT. {@code q} = case-insensitive in title and
     * location_text. Order: overdue first, then due_at ascending, then created_at
     * descending. {@code GET /api/tickets} (the old list) is left untouched.
     */
    public TicketSearchPage search(Authentication auth, UUID projectId, String type, String status,
                                   Boolean mine, Boolean overdue, String q, int page, int size) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        int sz = Math.min(Math.max(size <= 0 ? 20 : size, 1), 50);
        int pg = Math.max(page, 0);

        StringBuilder where = new StringBuilder(" where t.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());
        if (projectId != null) { where.append(" and t.project_id = ?"); args.add(projectId); }
        if (type != null && !type.isBlank()) { where.append(" and t.type = ?"); args.add(type.trim()); }
        if (status != null && !status.isBlank()) { where.append(" and t.status = ?"); args.add(status.trim()); }
        if (Boolean.TRUE.equals(mine)) {
            where.append(" and (t.assigned_to = ? or (t.status = 'RESOLVED' and t.created_by = ?))");
            args.add(me.getId()); args.add(me.getId());
        }
        if (Boolean.TRUE.equals(overdue)) {
            where.append(" and t.due_at is not null and t.due_at < current_date")
                 .append(" and t.status not in ('CLOSED','CANCELLED','AS_BUILT')");
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(t.title) like lower(?) or lower(coalesce(t.location_text,'')) like lower(?))");
            String pat = "%" + q.trim() + "%";
            args.add(pat); args.add(pat);
        }
        // IDOR: non-owner sees only tickets on projects they're a member of (or no project)
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            Set<UUID> accessible = new HashSet<>(jdbc.queryForList(
                    "select project_id from project_member where user_id = ?", UUID.class, me.getId()));
            if (accessible.isEmpty()) {
                where.append(" and t.project_id is null");
            } else {
                where.append(" and (t.project_id is null or t.project_id in (");
                StringJoiner sj = new StringJoiner(",");
                for (UUID pid : accessible) { sj.add("?"); args.add(pid); }
                where.append(sj).append("))");
            }
        }

        Long total = jdbc.queryForObject("select count(*) from ticket t" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add(pg * sz);
        List<TicketSearchItem> items = jdbc.queryForList(
                SEARCH_SELECT_SQL + where
                  + " order by (t.due_at is not null and t.due_at < current_date"
                  + " and t.status not in ('CLOSED','CANCELLED','AS_BUILT')) desc,"
                  + " t.due_at asc nulls last, t.created_at desc limit ? offset ?",
                pageArgs.toArray()).stream().map(TicketService::toSearchItem).toList();
        return new TicketSearchPage(items, total == null ? 0 : total, pg, sz);
    }

    // ══════════════════ 9. SUMMARY ══════════════════

    /**
     * Counts of not-closed, not-cancelled tickets. {@code projectId} optional =
     * all my projects (owner = all org projects; otherwise projects I'm a member
     * of). {@code toVerify} = RESOLVED tickets I may close (creator, or owner —
     * project-manager rule comes in T3).
     */
    public TicketSummary summary(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        String scope = " where t.org_id = ? and t.status not in ('CLOSED','CANCELLED')";
        List<Object> scopeArgs = new ArrayList<>();
        scopeArgs.add(me.getOrgId());
        if (projectId != null) {
            scope += " and t.project_id = ?";
            scopeArgs.add(projectId);
        } else if (!Boolean.TRUE.equals(me.getIsOwner())) {
            Set<UUID> accessible = new HashSet<>(jdbc.queryForList(
                    "select project_id from project_member where user_id = ?", UUID.class, me.getId()));
            if (accessible.isEmpty()) {
                scope += " and t.project_id is null";
            } else {
                scope += " and (t.project_id is null or t.project_id in (";
                StringJoiner sj = new StringJoiner(",");
                for (UUID pid : accessible) { sj.add("?"); scopeArgs.add(pid); }
                scope += sj + "))";
            }
        }

        long mineCount = count(scope + " and t.assigned_to = ?", append(scopeArgs, me.getId()));
        long overdueCount = count(scope + " and t.due_at is not null and t.due_at < current_date and t.status <> 'AS_BUILT'", scopeArgs);
        long toVerify = Boolean.TRUE.equals(me.getIsOwner())
                ? count(scope + " and t.status = 'RESOLVED'", scopeArgs)
                : count(scope + " and t.status = 'RESOLVED' and t.created_by = ?", append(scopeArgs, me.getId()));
        long openCount = count(scope + " and t.status = 'OPEN'", scopeArgs);
        long inProgressCount = count(scope + " and t.status = 'IN_PROGRESS'", scopeArgs);
        Map<String, Long> byType = new LinkedHashMap<>();
        for (TicketType tt : TicketType.values())
            byType.put(tt.name(), count(scope + " and t.type = ?", append(scopeArgs, tt.name())));
        return new TicketSummary(mineCount, overdueCount, toVerify, openCount, inProgressCount, byType);
    }

    private long count(String whereClause, List<Object> args) {
        Long n = jdbc.queryForObject("select count(*) from ticket t" + whereClause, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static List<Object> append(List<Object> base, Object extra) {
        List<Object> out = new ArrayList<>(base);
        out.add(extra);
        return out;
    }

    private static TicketSearchItem toSearchItem(Map<String, Object> r) {
        Object pc = r.get("photo_count");
        return new TicketSearchItem(toResponse(r), pc == null ? 0 : ((Number) pc).longValue());
    }

    public record TicketSearchItem(TicketResponse ticket, long photoCount) {}
    public record TicketSearchPage(List<TicketSearchItem> items, long total, int page, int size) {}
    public record TicketSummary(long mine, long overdue, long toVerify, long open, long inProgress,
                                Map<String, Long> byType) {}

    // ══════════════════ 10. ACTIONS (start / resolve / verify / reopen / cancel) ══════════════════

    /**
     * Named actions for PROBLEME / RESERVE / DEMANDE. Each is one transaction:
     * status change + one STATUS_CHANGE row in ticket_comment (body = note/comment/reason)
     * + a TICKET_STATUS_CHANGED domain event. Role rules:
     * <ul>
     *   <li>start, resolve — assignee, or creator if no assignee.</li>
     *   <li>verify, reopen, cancel — creator, owner, or project manager (ADMIN/MANAGER).</li>
     * </ul>
     * resolve requires a note ≥ 5 chars (saved in resolution_note) and, for RESERVE, ≥ 1 AFTER photo.
     * reopen and cancel require a comment/reason ≥ 5 chars. verify for a RESERVE requires the
     * verifier to differ from the resolver.
     */
    @Transactional
    public TicketResponse start(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        requireSignalement(type);
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target = TicketWorkflow.targetForAction("START", type, current);
        if (target == null) throw new BusinessRuleException("INVALID_ACTION", "Action impossible depuis cet état");
        requireAssigneeOrCreator(me, t);
        return applyTransition(me, id, (UUID) t.get("project_id"), current, target, null);
    }

    @Transactional
    public TicketResponse resolve(Authentication auth, UUID id, String note) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        requireSignalement(type);
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target = TicketWorkflow.targetForAction("RESOLVE", type, current);
        if (target == null) throw new BusinessRuleException("INVALID_ACTION", "Action impossible depuis cet état");
        requireAssigneeOrCreator(me, t);
        String norm = requireBody(note, 5, "La note de résolution doit faire au moins 5 caractères");
        if (type == TicketType.RESERVE) requireAfterPhoto(me, id);
        return applyTransition(me, id, (UUID) t.get("project_id"), current, target, norm);
    }

    @Transactional
    public TicketResponse verify(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        requireSignalement(type);
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target = TicketWorkflow.targetForAction("VERIFY", type, current);
        if (target == null) throw new BusinessRuleException("INVALID_ACTION", "Action impossible depuis cet état");
        requireCreatorOwnerOrManager(me, t);
        if (type == TicketType.RESERVE && me.getId().equals(t.get("resolved_by")))
            throw new AccessDeniedException("La personne qui a résolu ne peut pas vérifier cette réserve");
        return applyTransition(me, id, (UUID) t.get("project_id"), current, target, null);
    }

    @Transactional
    public TicketResponse reopen(Authentication auth, UUID id, String comment) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        requireSignalement(type);
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target = TicketWorkflow.targetForAction("REOPEN", type, current);
        if (target == null) throw new BusinessRuleException("INVALID_ACTION", "Action impossible depuis cet état");
        requireCreatorOwnerOrManager(me, t);
        String norm = requireBody(comment, 5, "Le commentaire de réouverture doit faire au moins 5 caractères");
        return applyTransition(me, id, (UUID) t.get("project_id"), current, target, norm);
    }

    @Transactional
    public TicketResponse cancel(Authentication auth, UUID id, String reason) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        requireSignalement(type);
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target = TicketWorkflow.targetForAction("CANCEL", type, current);
        if (target == null) throw new BusinessRuleException("INVALID_ACTION", "Action impossible depuis cet état");
        requireCreatorOwnerOrManager(me, t);
        String norm = requireBody(reason, 5, "Le motif d'annulation doit faire au moins 5 caractères");
        return applyTransition(me, id, (UUID) t.get("project_id"), current, target, norm);
    }

    /**
     * Action codes the current user may do now. For PROBLEME/RESERVE/DEMANDE:
     * START, RESOLVE, VERIFY, REOPEN, CANCEL (filtered by status + role) + COMMENT + ADD_PHOTO.
     * For VISA_DOCUMENT: the allowed next status codes (as today's /transitions).
     */
    public List<String> actions(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        TicketType type = TicketType.from((String) t.get("type"));
        TicketStatus current = TicketStatus.from((String) t.get("status"));
        if (type == TicketType.VISA_DOCUMENT)
            return TicketWorkflow.next(type, current).stream().map(Enum::name).sorted().toList();
        List<String> out = new ArrayList<>();
        if (TicketWorkflow.targetForAction("START", type, current) != null && canAssigneeOrCreator(me, t)) out.add("START");
        if (TicketWorkflow.targetForAction("RESOLVE", type, current) != null && canAssigneeOrCreator(me, t)) out.add("RESOLVE");
        if (TicketWorkflow.targetForAction("VERIFY", type, current) != null && canCreatorOwnerOrManager(me, t)
                && !(type == TicketType.RESERVE && me.getId().equals(t.get("resolved_by")))) out.add("VERIFY");
        if (TicketWorkflow.targetForAction("REOPEN", type, current) != null && canCreatorOwnerOrManager(me, t)) out.add("REOPEN");
        if (TicketWorkflow.targetForAction("CANCEL", type, current) != null && canCreatorOwnerOrManager(me, t)) out.add("CANCEL");
        out.add("COMMENT");
        if (!Set.of(TicketStatus.CLOSED, TicketStatus.CANCELLED, TicketStatus.AS_BUILT).contains(current)) out.add("ADD_PHOTO");
        return out;
    }

    // ══════════════════ 11. COMMENTS + TIMELINE ══════════════════

    public record CommentResponse(UUID id, String body, UUID authorId, String authorName, Instant createdAt) {}

    public record TimelineEntry(UUID id, String kind, String body, UUID authorId, String authorName,
                                String authorRole, String fromStatus, String toStatus,
                                String fromStatusLabel, String toStatusLabel, Instant createdAt) {}

    @Transactional
    public CommentResponse addComment(Authentication auth, UUID id, String body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        requireReadAccess(me, t);
        if (body == null || body.isBlank() || body.length() > 2000)
            throw new BusinessRuleException("INVALID_COMMENT", "Le commentaire doit faire entre 1 et 2000 caractères");
        String norm = body.trim();
        Map<String, Object> row = jdbc.queryForMap("""
                insert into ticket_comment (org_id, ticket_id, author_id, kind, body, created_at)
                values (?, ?, ?, 'COMMENT', ?, clock_timestamp()) returning id, created_at
                """, me.getOrgId(), id, me.getId(), norm);
        return new CommentResponse((UUID) row.get("id"), norm, me.getId(), me.getFullName(),
                toInstant(row.get("created_at")));
    }

    public List<TimelineEntry> timeline(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        requireReadAccess(me, t);
        return jdbc.queryForList("""
                select tc.id, tc.kind, tc.body, tc.author_id, u.full_name as author_name,
                       (select r.code from user_role ur join role r on r.id = ur.role_id
                         where ur.user_id = tc.author_id order by r.code limit 1) as author_role,
                       tc.from_status, tc.to_status, tc.created_at
                from ticket_comment tc
                left join app_user u on u.id = tc.author_id
                where tc.ticket_id = ? and tc.org_id = ?
                order by tc.created_at, tc.id
                """, id, me.getOrgId()).stream().map(TicketService::toTimelineEntry).toList();
    }

    // ── action helpers ───────────────────────────────────────────────────

    private TicketResponse applyTransition(AppUser me, UUID id, UUID projectId,
                                           TicketStatus current, TicketStatus target, String body) {
        if (target == TicketStatus.RESOLVED) {
            jdbc.update("""
                    update ticket set status = ?, resolved_at = now(), resolved_by = ?,
                                      resolution_note = ?, updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), me.getId(), body, id, me.getOrgId());
        } else if (target == TicketStatus.CLOSED) {
            jdbc.update("""
                    update ticket set status = ?, closed_at = now(), closed_by = ?, updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), me.getId(), id, me.getOrgId());
        } else {
            jdbc.update("""
                    update ticket set status = ?, updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), id, me.getOrgId());
        }
        insertComment(me.getOrgId(), id, me.getId(), "STATUS_CHANGE", body, current.name(), target.name());
        audit.log("TICKET_STATUS_CHANGED", "ticket", id, null);
        if (domainEvents != null)
            domainEvents.publish(me.getOrgId(), EventType.TICKET_STATUS_CHANGED, "TICKET", id, projectId,
                    DomainEventService.payload("from", current.name(), "to", target.name()));
        return reload(id, me.getOrgId());
    }

    private void insertComment(UUID orgId, UUID ticketId, UUID authorId, String kind,
                               String body, String fromStatus, String toStatus) {
        jdbc.update("""
                insert into ticket_comment (org_id, ticket_id, author_id, kind, body, from_status, to_status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                """, orgId, ticketId, authorId, kind, body, fromStatus, toStatus);
    }

    private void requireSignalement(TicketType type) {
        if (type == TicketType.VISA_DOCUMENT)
            throw new BusinessRuleException("USE_ACTIONS", "Utilisez les actions du signalement");
    }

    private String requireBody(String body, int minLen, String message) {
        if (body == null || body.trim().length() < minLen)
            throw new BusinessRuleException("REQUIRED_FIELDS", message);
        return body.trim();
    }

    private void requireAfterPhoto(AppUser me, UUID id) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from ticket_attachment where ticket_id = ? and org_id = ? and phase = 'AFTER'",
                Integer.class, id, me.getOrgId());
        if (cnt == null || cnt == 0)
            throw new BusinessRuleException("AFTER_PHOTO_REQUIRED", "Ajoutez au moins une photo après travaux");
    }

    private void requireAssigneeOrCreator(AppUser me, Map<String, Object> t) {
        UUID assignedTo = (UUID) t.get("assigned_to");
        if (assignedTo != null) {
            if (!me.getId().equals(assignedTo))
                throw new AccessDeniedException("Seul le responsable peut effectuer cette action");
        } else if (!me.getId().equals(t.get("created_by"))) {
            throw new AccessDeniedException("Seul le créateur peut effectuer cette action");
        }
    }

    private boolean canAssigneeOrCreator(AppUser me, Map<String, Object> t) {
        UUID assignedTo = (UUID) t.get("assigned_to");
        if (assignedTo != null) return me.getId().equals(assignedTo);
        return me.getId().equals(t.get("created_by"));
    }

    private void requireCreatorOwnerOrManager(AppUser me, Map<String, Object> t) {
        if (!canCreatorOwnerOrManager(me, t))
            throw new AccessDeniedException("Action non autorisée");
    }

    private boolean canCreatorOwnerOrManager(AppUser me, Map<String, Object> t) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return true;
        if (me.getId().equals(t.get("created_by"))) return true;
        UUID projectId = (UUID) t.get("project_id");
        if (projectId == null) return false;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ? and project_role in ('ADMIN','MANAGER')",
                Integer.class, projectId, me.getId());
        return cnt != null && cnt > 0;
    }

    private void requireReadAccess(AppUser me, Map<String, Object> t) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        UUID projectId = (UUID) t.get("project_id");
        if (projectId == null) return;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, me.getId());
        if (cnt == null || cnt == 0)
            throw new AccessDeniedException("Accès non autorisé à ce signalement");
    }

    private static TimelineEntry toTimelineEntry(Map<String, Object> r) {
        String from = (String) r.get("from_status");
        String to = (String) r.get("to_status");
        return new TimelineEntry(
                (UUID) r.get("id"),
                (String) r.get("kind"),
                (String) r.get("body"),
                (UUID) r.get("author_id"),
                (String) r.get("author_name"),
                (String) r.get("author_role"),
                from,
                to,
                from == null ? null : TicketStatus.from(from).label(),
                to == null ? null : TicketStatus.from(to).label(),
                toInstant(r.get("created_at")));
    }

    // ══════════════════ VALIDATION HELPERS ══════════════════

    /** Same-org ticket row or ResourceNotFoundException(TICKET_NOT_FOUND → 404 JSON). */
    private Map<String, Object> requireTicket(AppUser me, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, title, status, type, project_id, assigned_to, created_by, resolved_by from ticket where id = ? and org_id = ?",
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
