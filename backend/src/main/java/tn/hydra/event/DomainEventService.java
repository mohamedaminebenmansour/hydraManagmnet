package tn.hydra.event;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Domain events (V47): one row per business action, written in the caller's
 * transaction, so a failed action leaves no event. Nothing consumes them yet.
 * Not the audit log (EventLogService / event_log), which keeps its own purpose.
 */
@Service
public class DomainEventService {

    public static final int PAGE_SIZE = 50;

    public record DomainEventResponse(UUID id, String type, String typeLabel, String subjectType,
                                      UUID subjectId, UUID projectId, String projectName,
                                      Map<String, Object> payload, UUID createdBy, String createdByName,
                                      Instant createdAt) {}

    public record DomainEventPage(List<DomainEventResponse> items, long total, int page, int size) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    /** Same rule as BordereauImportService: flush JPA writes before the JDBC insert. */
    @PersistenceContext
    private EntityManager entityManager;

    public DomainEventService(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /**
     * Insert one event in the caller's transaction (REQUIRED). Call it at the END of the
     * business method, after the write succeeded. Payload: ids, amounts as strings and
     * codes only — no names, no free text. created_by = the current user, or null.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID publish(UUID orgId, EventType type, String subjectType, UUID subjectId,
                        UUID projectId, Map<String, ?> payload) {
        // The caller may have written through JPA: make those rows visible to this JDBC insert.
        entityManager.flush();
        return jdbc.queryForObject("""
                insert into domain_event (org_id, type, subject_type, subject_id, project_id, payload, created_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?) returning id
                """, UUID.class, orgId, type.name(), subjectType, subjectId, projectId,
                toJson(payload), currentUserId());
    }

    /** Payload builder: key, value, key, value… (null values allowed, they are dropped). */
    public static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    /** GET /api/events — owner only, own org, newest first, PAGE_SIZE per page. */
    public DomainEventPage list(Authentication auth, String type, UUID projectId,
                                LocalDate from, LocalDate to, int page) {
        AppUser me = auth == null ? null : users.findByEmail(auth.getName()).orElse(null);
        if (me == null || !Boolean.TRUE.equals(me.getIsOwner()))
            throw new AccessDeniedException("Réservé au propriétaire du compte");
        int p = Math.max(page, 0);

        StringBuilder where = new StringBuilder(" where e.org_id = ?");
        List<Object> args = new ArrayList<>(List.of(me.getOrgId()));
        if (type != null && !type.isBlank()) { where.append(" and e.type = ?"); args.add(type.trim()); }
        if (projectId != null) { where.append(" and e.project_id = ?"); args.add(projectId); }
        ZoneId zone = ZoneId.of("Africa/Tunis");
        if (from != null) { where.append(" and e.created_at >= ?"); args.add(Timestamp.from(from.atStartOfDay(zone).toInstant())); }
        if (to != null) { where.append(" and e.created_at < ?"); args.add(Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant())); }

        Long total = jdbc.queryForObject("select count(*) from domain_event e" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add(p * PAGE_SIZE);
        List<DomainEventResponse> items = jdbc.queryForList("""
                select e.id, e.type, e.subject_type, e.subject_id, e.project_id, p.name as project_name,
                       e.payload::text as payload, e.created_by, u.full_name as created_by_name, e.created_at
                from domain_event e
                left join project p on p.id = e.project_id and p.org_id = e.org_id
                left join app_user u on u.id = e.created_by
                """ + where + " order by e.created_at desc, e.id limit ? offset ?", pageArgs.toArray())
                .stream().map(DomainEventService::toResponse).toList();
        return new DomainEventPage(items, total == null ? 0 : total, p, PAGE_SIZE);
    }

    private static DomainEventResponse toResponse(Map<String, Object> r) {
        String type = (String) r.get("type");
        Object created = r.get("created_at");
        return new DomainEventResponse((UUID) r.get("id"), type, EventType.labelOf(type),
                (String) r.get("subject_type"), (UUID) r.get("subject_id"), (UUID) r.get("project_id"),
                (String) r.get("project_name"), fromJson((String) r.get("payload")),
                (UUID) r.get("created_by"), (String) r.get("created_by_name"),
                created instanceof Timestamp ts ? ts.toInstant() : null);
    }

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return users.findByEmail(auth.getName()).map(AppUser::getId).orElse(null);
    }

    /** Values written as strings (ids, amounts, codes); null values dropped. */
    private static String toJson(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) return "{}";
        Map<String, String> clean = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (v == null) return;
            clean.put(k, v instanceof java.math.BigDecimal bd ? bd.toPlainString() : String.valueOf(v));
        });
        return JSON.writeValueAsString(clean);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromJson(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try { return JSON.readValue(s, Map.class); } catch (RuntimeException e) { return Map.of(); }
    }
}
