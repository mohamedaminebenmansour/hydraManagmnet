package tn.hydra.process;

import tn.hydra.event.DomainEventService;
import tn.hydra.event.EventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.cash.MovementNature;
import tn.hydra.cash.PaymentMode;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Process engine service — single-file, pure JdbcTemplate, no JPA flush risk.
 *
 * Permission model (clear contract used everywhere):
 *  - Org OWNER:          may do everything in their org.
 *  - Project member:     may start a process on that project.
 *  - Step assignee:      may complete / reject their own PENDING step
 *                        (assignee_user_id = me, OR my project_role = assignee_role).
 *  - Complete / reject fallback: if assignee_user_id is null, any project member whose
 *    project_role matches the step's assignee_role may act.
 */
@Service
public class ProcessService {

    // ═══════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE RECORDS
    // ═══════════════════════════════════════════════════════════════

    public record StartProcessCommand(UUID projectId, String templateKey, String subjectType, UUID subjectId) {}

    public record CompleteStepRequest(String comment, List<UUID> documentIds) {}

    public record RejectStepRequest(String comment) {}

    public record CancelRequest(String reason) {}

    /** TASK 3 — body for POST /steps/{stepInstanceId}/reassign. */
    public record ReassignStepRequest(UUID userId, String comment) {}

    /** Returned after start — 201 */
    public record StartProcessResponse(UUID id, String status, UUID projectId,
                                       String templateKey, String subjectType, UUID subjectId,
                                       // TASK 3 — guardrails: warnings when a step's role has no
                                       // user mapped (process still starts; the role-based
                                       // fallback keeps it alive).
                                       List<String> warnings) {

        /** Backwards-compatible 6-field constructor (no warnings → empty list). */
        public StartProcessResponse(UUID id, String status, UUID projectId,
                                     String templateKey, String subjectType, UUID subjectId) {
            this(id, status, projectId, templateKey, subjectType, subjectId, List.of());
        }
    }

    /** One line on the timeline steps list. */
    public record StepView(
            UUID stepInstanceId,
            int stepOrder,
            String stepKey,
            String name,
            String status,
            String assigneeRole,
            UUID assigneeUserId,
            String assigneeName,
            boolean requiresAttachment,
            Instant startedAt,
            Instant completedAt,
            String comment,
            List<UUID> documentIds) {}

    /** Full traceability view. */
    public record ProcessTimeline(
            UUID instanceId,
            UUID projectId,
            String projectName,
            String status,
            String templateKey,
            String templateName,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            UUID createdBy,
            String createdByName,
            int currentStepOrder,
            List<StepView> steps) {}

    // ═══════════════════════════════════════════════════════════════
    // APPROVAL DESK (TASK 1) — single payload for an approver to decide
    // on a CASH_MOVEMENT-linked process step. One JOINed query + one
    // step-list query — no per-field N+1.
    // ═══════════════════════════════════════════════════════════════

    /** One previous/completed step shown in the desk's mini-timeline. */
    public record DeskStepView(
            int stepOrder,
            String stepKey,
            String name,
            String status,
            String assigneeRole,
            String assigneeName,
            Instant completedAt,
            String comment) {}

    /**
     * Approval desk response — everything an approver needs to decide on a
     * CASH_MOVEMENT-linked payment step, in a single round-trip.
     *
     * <p>Flags:
     * <ul>
     *   <li>{@code canComplete} / {@code canReject} — true only if the caller
     *       is the step's assignee (or owner, or holds the business role on
     *       the project). Mirrors {@link #requireStepAccess}.</li>
     *   <li>{@code requiresAttachment} — copied from the step template flag.</li>
     *   <li>{@code hasAttachment} — true if the linked movement has a
     *       document_id set (the payment-proof uploaded via the movement
     *       attachment endpoint) OR a process_step_document row exists.</li>
     * </ul>
     *
     * <p>Cash fields are populated only when {@code subjectType == CASH_MOVEMENT};
     * for other subjects the endpoint returns 400 SUBJECT_NOT_SUPPORTED_FOR_DESK.
     */
    public record ApprovalDeskResponse(
            // ── process step + instance context ──
            UUID stepInstanceId,
            UUID instanceId,
            String templateKey,
            String templateName,
            String instanceStatus,
            int currentStepOrder,
            String currentStepKey,
            String currentStepName,
            String currentStepRole,
            UUID assigneeUserId,
            String assigneeName,
            boolean requiresAttachment,
            // ── subject identification ──
            String subjectType,
            UUID subjectId,
            // ── cash movement payload (CASH_MOVEMENT only) ──
            UUID movementId,
            String movementLabel,
            String movementDate,
            String movementType,
            String movementStatus,
            BigDecimal assessedAmount,
            BigDecimal claimedAmount,
            BigDecimal varianceAmount,
            String varianceReason,
            String paymentMode,
            String paymentRef,
            String voucherNo,
            String nature,
            String invoiceRef,
            // ── linked entities (names — JOINed, no N+1) ──
            UUID projectId,
            String projectName,
            UUID partyId,
            String partyName,
            String partyType,
            UUID fromPotId,
            String fromPotName,
            // ── proof document ──
            UUID documentId,
            String documentName,
            // ── flags ──
            boolean canComplete,
            boolean canReject,
            boolean hasAttachment,
            // ── mini-timeline (previous steps) ──
            List<DeskStepView> steps) {}

    /** Minimal result returned after complete / reject. */
    public record StepResult(UUID stepInstanceId, String newStatus) {}

    /** Cross-project task for the "My Tasks" screen. */
    public record MyTaskSummary(
            UUID stepInstanceId,
            UUID instanceId,
            UUID projectId,
            String projectName,
            String templateKey,
            String templateName,
            String subjectType,
            UUID subjectId,
            String stepKey,
            String stepName,
            String status,
            String assigneeRole,
            UUID assigneeUserId,
            String assigneeName,
            boolean requiresAttachment,
            Instant startedAt,
            // Subject display info (cash payments): label + amount when the
            // subject is a CASH_MOVEMENT — filled via LEFT JOIN, null otherwise.
            // Lets "Mes tâches" show "Paiement — 2 500,000 TND" without N+1.
            String subjectLabel,
            java.math.BigDecimal subjectAmount,
            // due_days SLA: true when a PENDING step started more than due_days
            // days ago (null due_days => never late).
            boolean late,
            // When the substitute sees a step (the main assignee is absent/inactive),
            // this is the main person's full name; null otherwise.
            String onBehalfOf) {

        public MyTaskSummary withOnBehalfOf(String onBehalf) {
            return new MyTaskSummary(stepInstanceId, instanceId, projectId, projectName,
                    templateKey, templateName, subjectType, subjectId, stepKey, stepName,
                    status, assigneeRole, assigneeUserId, assigneeName, requiresAttachment,
                    startedAt, subjectLabel, subjectAmount, late, onBehalf);
        }
    }

    /** One pending approval on a chantier (the "En attente" block). */
    public record PendingApproval(
            UUID stepInstanceId,
            UUID instanceId,
            String templateName,
            String stepName,
            String assigneeRole,
            String assigneeName,
            String subjectLabel,
            java.math.BigDecimal subjectAmount,
            Instant startedAt,
            Integer dueDays,
            boolean late,
            Integer daysWaiting) {}

    /** Summary row for the project chantier screen. */
    public record ProcessInstanceSummary(
            UUID id,
            String templateKey,
            String templateName,
            String status,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            int currentStepOrder,
            String currentStepName,
            // Extra fields for owner visibility (who is responsible right now)
            String currentAssigneeName,
            String currentAssigneeRole,
            int totalSteps) {}

    /** Template option for dropdowns / project config. */
    public record TemplateOption(UUID id, String key, String name, boolean active,
                                  String description) {
        /** Backwards-compatible 4-field constructor (description = null). */
        public TemplateOption(UUID id, String key, String name, boolean active) {
            this(id, key, name, active, null);
        }
    }

    /** Template option + its steps (ordered by step_order), for the owner's Processus page. */
    public record AdminTemplateOption(UUID id, String key, String name, boolean active,
                                      String description, List<TemplateStepView> steps) {}

    /** Full template detail (for admin screens). */
    public record TemplateDetail(
            UUID id, String key, String name, UUID orgId, boolean active,
            String description,
            Instant createdAt, List<TemplateStepView> steps,
            // Rule-builder fields (V38 columns).
            String subjectType, Map<String, Object> conditions, Integer priority) {
        /** Backwards-compatible 7-field constructor (description + rule fields = null). */
        public TemplateDetail(UUID id, String key, String name, UUID orgId, boolean active,
                               Instant createdAt, List<TemplateStepView> steps) {
            this(id, key, name, orgId, active, null, createdAt, steps, null, null, null);
        }
    }

    public record TemplateStepView(
            UUID id, int stepOrder, String stepKey, String name,
            String assigneeRole, boolean requiresAttachment,
            // Rule-builder fields (V38 columns).
            String approvalMode, Integer dueDays) {}

    // Template management request records
    public record CreateTemplateRequest(String key, String name, String description,
                                        String subjectType,
                                        Map<String, Object> conditions,
                                        Integer priority) {
        /** Backwards-compatible 2-field constructor. */
        public CreateTemplateRequest(String key, String name) { this(key, name, null, null, null, null); }
        /** Backwards-compatible 3-field constructor. */
        public CreateTemplateRequest(String key, String name, String description) {
            this(key, name, description, null, null, null);
        }
    }

    public record UpdateTemplateRequest(String name, Boolean active, String description,
                                        String subjectType,
                                        Map<String, Object> conditions,
                                        Integer priority) {
        /** Backwards-compatible 2-field constructor. */
        public UpdateTemplateRequest(String name, Boolean active) { this(name, active, null, null, null, null); }
        /** Backwards-compatible 3-field constructor. */
        public UpdateTemplateRequest(String name, Boolean active, String description) {
            this(name, active, description, null, null, null);
        }
    }

    public record CreateStepRequest(int stepOrder, String stepKey, String name,
                                    String assigneeRole, boolean requiresAttachment,
                                    String approvalMode, Integer dueDays) {
        /** Backwards-compatible 5-field constructor. */
        public CreateStepRequest(int stepOrder, String stepKey, String name,
                                 String assigneeRole, boolean requiresAttachment) {
            this(stepOrder, stepKey, name, assigneeRole, requiresAttachment, null, null);
        }
    }

    public record UpdateStepRequest(String name, String assigneeRole,
                                    boolean requiresAttachment, Integer stepOrder,
                                    String approvalMode, Integer dueDays) {
        /** Backwards-compatible 4-field constructor. */
        public UpdateStepRequest(String name, String assigneeRole,
                                 boolean requiresAttachment, Integer stepOrder) {
            this(name, assigneeRole, requiresAttachment, stepOrder, null, null);
        }
    }

    // Project template assignment
    public record AssignTemplateRequest(String templateKey, boolean enabled,
                                        String defaultForSubjectType,
                                        BigDecimal amountThresholdOverride) {}

    public record ProjectTemplateConfig(
            UUID templateId, String templateKey, String templateName,
            boolean enabled, String defaultForSubjectType,
            BigDecimal amountThresholdOverride) {}

    // ═══════════════════════════════════════════════════════════════
    // V27 (TASK 1) — Project role assignee records
    // ═══════════════════════════════════════════════════════════════

    /** A role → user mapping as stored in project_role_assignee. */
    public record RoleAssigneeResponse(
            String roleCode,
            UUID userId,
            String userName,
            String userEmail,
            UUID substituteUserId,
            String substituteUserName,
            // V44 — absence dates read from app_user.absent_until. Null when the
            // person is not absent or the stored date is before today (absence
            // already ended). Powers the chantier "Règles" tab badge + the
            // candidate dropdown hint.
            LocalDate absentUntil,
            LocalDate substituteAbsentUntil) {}

    /** Body for PUT /api/projects/{id}/process-role-assignees — upsert batch. */
    public record UpsertRoleAssigneesRequest(List<RoleAssigneeItem> items) {}

    public record RoleAssigneeItem(String roleCode, UUID userId, UUID substituteUserId) {}

    /**
     * Composite view returned by GET /process-roles-needed: for each distinct
     * assignee_role used by templates enabled on this project (or all active
     * org/system templates when no project_process_template rows exist),
     * the current assignee (if any) + the candidate users the owner can pick.
     */
    public record RoleNeeded(
            String roleCode,
            RoleAssigneeResponse currentAssignee,
            List<RoleCandidate> candidates) {}

    public record RoleCandidate(
            UUID userId,
            String userName,
            String userEmail,
            boolean isOwner,
            boolean isProjectMember,
            // V44 — null when not absent or the date is before today.
            LocalDate absentUntil) {}

    // ═══════════════════════════════════════════════════════════════
    // DEPENDENCIES
    // ═══════════════════════════════════════════════════════════════

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final tn.hydra.cash.CashService cashService;  // for auto-post on COMPLETED + handle on REJECTED
    private final ObjectMapper objectMapper;              // conditions jsonb <-> Map
    private final ApprovalRuleService approvalRuleService; // for the /test endpoint

    private final DomainEventService domainEvents;

    public ProcessService(JdbcTemplate jdbc,
                          AppUserRepository users,
                          EventLogService audit,
                          tn.hydra.cash.CashService cashService,
                          ObjectMapper objectMapper,
                          ApprovalRuleService approvalRuleService,
            DomainEventService domainEvents) {
        this.domainEvents = domainEvents;
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.cashService = cashService;
        this.objectMapper = objectMapper;
        this.approvalRuleService = approvalRuleService;
    }

    // ═══════════════════════════════════════════════════════════════
    // AUTH HELPERS  — all use plain JDBC to avoid Hibernate autoflush
    // ═══════════════════════════════════════════════════════════════

    /** Returns the authenticated user from app_user via plain JDBC. */
    private AppUser currentUser(Authentication auth) {
        String email = auth.getName();
        return users.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + email));
    }

    private UUID currentUserId(Authentication auth) {
        return currentUser(auth).getId();
    }

    /**
     * Enforces: caller is org owner OR a member of the given project.
     * Throws ResourceNotFoundException (→ 404) so we never reveal project existence
     * to non-members.
     */
    private void requireProjectAccess(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, me.getId());
        if (cnt == null || cnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND",
                    "Projet introuvable ou accès refusé");
        }
    }

    /**
     * Enforces: caller is org owner OR is_owner flag.
     * Used for template admin operations.
     */
    private void requireOwner(AppUser me) {
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            throw new BusinessRuleException("OWNER_ONLY",
                    "Seul le gérant peut effectuer cette action");
        }
    }

    /**
     * Can the caller complete or reject the given step?
     *  1. Org owner → always yes.
     *  2. assignee_user_id = me → yes.
     *  3. role-based fallback: I am a project member AND I hold the business role
     *    (via user_role join — project_role is ADMIN/MANAGER/ENGINEER/VIEWER which
     *    is the project-level permission, not the business role).
     */
    private void requireStepAccess(AppUser me, UUID stepInstanceId,
                                   UUID assigneeUserId, String assigneeRole, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (Objects.equals(me.getId(), assigneeUserId)) return;
        // role-based fallback: I'm a project member AND I hold the business role
        List<UUID> roleMatch = jdbc.queryForList(
                """
                select pm.user_id from project_member pm
                join user_role ur on ur.user_id = pm.user_id
                join role r on r.id = ur.role_id
                where pm.project_id = ? and pm.user_id = ? and r.code = ?
                limit 1
                """,
                UUID.class, projectId, me.getId(), assigneeRole);
        if (!roleMatch.isEmpty()) return;
        // Substitute path: if the step's assignee is absent or inactive, the
        // substitute for that role on the chantier may act.
        if (assigneeUserId != null) {
            Integer absentOrInactive = jdbc.queryForObject(
                    "select count(*) from app_user where id = ? and (is_active = false or (absent_until is not null and absent_until >= current_date))",
                    Integer.class, assigneeUserId);
            if (absentOrInactive != null && absentOrInactive > 0) {
                Integer subMatch = jdbc.queryForObject(
                        "select count(*) from project_role_assignee where project_id = ? and upper(role_code) = upper(?) and substitute_user_id = ?",
                        Integer.class, projectId, assigneeRole, me.getId());
                if (subMatch != null && subMatch > 0) return;
            }
        }
        throw new BusinessRuleException("NOT_ALLOWED",
                "Vous n'êtes pas autorisé à agir sur cette étape");
    }

    /**
     * Tries to resolve a specific user for the given business role on the project.
     * Returns null if nobody found (any user with that role may still act via role-check).
     * Picks the first alphabetically by email for determinism.
     *
     * NOTE: assignee_role is a BUSINESS role (FINANCE, ACHAT, CHEF_CHANTIER, etc.),
     * NOT a project_role (ADMIN/MANAGER/ENGINEER/VIEWER). The join must go through
     * user_role + role, not project_member.project_role.
     *
     * SPECIAL CASE: if the role is OWNER, return the org's owner directly (the owner
     * doesn't need to be a project_member to act on the project).
     *
     * V27 (TASK 1) — resolution order:
     *   1) project_role_assignee table (per-project explicit mapping);
     *      the mapping user is re-checked: still active; for non-OWNER roles
     *      still project_member (or, for OWNER role, may be org owner).
     *      If the mapping is stale, we fall through to the legacy path so the
     *      process still starts (don't hard-fail a payment because the finance
     *      user was removed from the project last week).
     *   2) OWNER → org owner directly (legacy).
     *   3) First project_member holding that business role via user_role+role.code.
     */
    private UUID resolveAssigneeByRole(UUID projectId, String assigneeRole) {
        if (assigneeRole == null || assigneeRole.isBlank()) return null;

        // ── (1) V27 — explicit per-project mapping ────────────────────
        List<Map<String, Object>> mappingRows = jdbc.queryForList(
                """
                select pra.user_id, u.is_owner as user_is_owner, u.is_active as user_active,
                       u.absent_until as user_absent_until,
                       pm.user_id as pm_user_id,
                       pra.substitute_user_id, su.is_active as substitute_active,
                       su.absent_until as substitute_absent_until
                from project_role_assignee pra
                join app_user u on u.id = pra.user_id
                left join app_user su on su.id = pra.substitute_user_id
                left join project_member pm on pm.project_id = pra.project_id
                                          and pm.user_id = pra.user_id
                where pra.project_id = ? and upper(pra.role_code) = upper(?)
                limit 1
                """,
                projectId, assigneeRole);
        if (!mappingRows.isEmpty()) {
            Map<String, Object> row = mappingRows.get(0);
            UUID mappedUser = (UUID) row.get("user_id");
            boolean userActive = row.get("user_active") == null
                    || Boolean.TRUE.equals(row.get("user_active"));
            boolean userAbsent = isAbsent(row.get("user_absent_until"));
            boolean isOwnerRole = "OWNER".equalsIgnoreCase(assigneeRole);
            // For OWNER role: mapped user may be org owner OR a project_member.
            // For other roles: mapped user must be a project_member.
            boolean pmFound = row.get("pm_user_id") != null;
            if (userActive && !userAbsent && (isOwnerRole || pmFound)) {
                return mappedUser;
            }
            // Main person absent (inactive OR absent_until >= today) — fall back
            // to the substitute if set + active + not absent.
            UUID substitute = (UUID) row.get("substitute_user_id");
            if (substitute != null) {
                boolean subActive = row.get("substitute_active") == null
                        || Boolean.TRUE.equals(row.get("substitute_active"));
                boolean subAbsent = isAbsent(row.get("substitute_absent_until"));
                if (subActive && !subAbsent) {
                    return substitute;
                }
            }
            // Mapping is stale (user inactive, no usable substitute) → fall through
            // to the legacy path so the process still starts. The owner will be
            // able to reassign via the UI; for now the role-based fallback keeps
            // the workflow alive.
        }

        // ── (2) OWNER → look up the org's owner directly ──────────────
        if ("OWNER".equalsIgnoreCase(assigneeRole)) {
            List<UUID> ownerRows = jdbc.queryForList(
                    """
                    select u.id from app_user u
                    where u.org_id = (select org_id from project where id = ?)
                      and u.is_owner = true
                    order by u.email asc
                    limit 1
                    """,
                    UUID.class, projectId);
            return ownerRows.isEmpty() ? null : ownerRows.get(0);
        }

        // ── (3) Other roles → must be a project_member AND hold the business role
        List<UUID> rows = jdbc.queryForList(
                """
                select pm.user_id from project_member pm
                join app_user u on u.id = pm.user_id
                join user_role ur on ur.user_id = u.id
                join role r on r.id = ur.role_id
                where pm.project_id = ? and r.code = ?
                order by u.email asc
                limit 1
                """,
                UUID.class, projectId, assigneeRole);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A user is absent when absent_until >= today (null = not absent). */
    private static boolean isAbsent(Object absentUntil) {
        if (absentUntil == null) return false;
        if (absentUntil instanceof java.sql.Date d) {
            return !d.toLocalDate().isBefore(java.time.LocalDate.now());
        }
        if (absentUntil instanceof java.time.LocalDate ld) {
            return !ld.isBefore(java.time.LocalDate.now());
        }
        return false;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.4  START PROCESS
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StartProcessResponse start(Authentication auth, StartProcessCommand cmd) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        if (orgId == null) throw new IllegalStateException("User has no org");

        // Resolve active template by key — prefer org-scoped, fall back to system.
        // Then delegate to startByTemplateId so the enablement / RUNNING / insert
        // logic lives in exactly one place (no duplication).
        List<Map<String, Object>> tmplRows = jdbc.queryForList(
                """
                select id from process_template
                where key = ? and active = true
                  and (org_id = ? or org_id is null)
                order by (org_id = ?) desc nulls last
                limit 1
                """,
                cmd.templateKey(), orgId, orgId);
        if (tmplRows.isEmpty()) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou inactif: " + cmd.templateKey());
        }
        UUID templateId = (UUID) tmplRows.get(0).get("id");
        return startByTemplateId(auth, cmd.projectId(), templateId, cmd.subjectType(), cmd.subjectId());
    }

    /**
     * Start a process from an already-resolved template id. Performs the SAME
     * checks as {@link #start}: project membership, template active + visible to
     * the org, per-chantier enablement whitelist (no rows => all active; any row
     * => only templates with an enabled = true row), one-RUNNING-per-subject
     * guard, then inserts the instance + first PENDING step.
     *
     * Used by PaymentFacade.submitPayment, which resolves the template via
     * ApprovalRuleService.findRule and starts by id (no key lookup needed).
     */
    @Transactional
    public StartProcessResponse startByTemplateId(Authentication auth, UUID projectId,
                                                   UUID templateId, String subjectType, UUID subjectId) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        if (orgId == null) throw new IllegalStateException("User has no org");

        // (1) Project membership
        requireProjectAccess(me, projectId);

        // (2) Load template by id (confirm active + visible to the org) + fetch
        //     key for the response/audit.
        List<Map<String, Object>> tmplRows = jdbc.queryForList(
                """
                select id, key from process_template
                where id = ? and active = true
                  and (org_id = ? or org_id is null)
                """,
                templateId, orgId);
        if (tmplRows.isEmpty()) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou inactif: " + templateId);
        }
        String templateKey = (String) tmplRows.get(0).get("key");

        // (3) Project enablement check — opt-out: a template is enabled on the
        //     chantier unless a project_process_template row says enabled = false.
        //     Same rule as ApprovalRuleService.findRule, via the shared
        //     isTemplateEnabledForProject helper (one meaning for the chantier switch).
        if (!approvalRuleService.isTemplateEnabledForProject(projectId, templateId)) {
            throw new BusinessRuleException("TEMPLATE_NOT_ENABLED_ON_PROJECT",
                    "Ce template n'est pas activé sur ce projet");
        }

        // (4) Guard: only one RUNNING instance per (org, subjectType, subjectId)
        Integer running = jdbc.queryForObject(
                """
                select count(*) from process_instance
                where org_id = ? and subject_type = ? and subject_id = ? and status = 'RUNNING'
                """,
                Integer.class, orgId, subjectType, subjectId);
        if (running != null && running > 0) {
            throw new BusinessRuleException("PROCESS_ALREADY_RUNNING",
                    "Une instance RUNNING existe déjà pour ce sujet");
        }

        // (5) Insert process_instance
        UUID instanceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_instance (id, org_id, project_id, template_id,
                                             subject_type, subject_id, status,
                                             current_step_order, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'RUNNING', 1, ?, now(), now())
                """,
                instanceId, orgId, projectId, templateId,
                subjectType, subjectId, me.getId());

        // (6) Load ALL template steps up front — collect warnings for any role
        //     that doesn't resolve to a concrete user (the process still starts;
        //     the role-based fallback keeps it alive).
        List<Map<String, Object>> allSteps = jdbc.queryForList(
                """
                select step_order, step_key, name, assignee_role, requires_attachment
                from process_template_step
                where template_id = ?
                order by step_order asc
                """,
                templateId);

        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> s : allSteps) {
            String role = (String) s.get("assignee_role");
            if (role == null || role.isBlank()) continue;
            UUID resolved = resolveAssigneeByRole(projectId, role);
            if (resolved == null) {
                warnings.add("Aucun utilisateur pour le rôle " + role.toUpperCase()
                        + " sur ce projet — étape '" + s.get("name") + "' (rôle seul).");
            }
        }

        // (7) Insert first PENDING step_instance (only the first step is created
        //     at start; subsequent steps are created by completeStep).
        if (!allSteps.isEmpty()) {
            Map<String, Object> first = allSteps.get(0);
            int stepOrder = ((Number) first.get("step_order")).intValue();
            String stepKey = (String) first.get("step_key");
            String stepName = (String) first.get("name");
            String assigneeRole = (String) first.get("assignee_role");
            boolean requiresAttachment = Boolean.TRUE.equals(first.get("requires_attachment"));

            // Resolve assignee — null is OK; any user with role may still act
            UUID assigneeUserId = resolveAssigneeByRole(projectId, assigneeRole);

            jdbc.update(
                    """
                    insert into process_step_instance
                      (id, instance_id, step_order, step_key, name,
                       status, assignee_role, assignee_user_id, requires_attachment,
                       started_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, now(), now())
                    """,
                    UUID.randomUUID(), instanceId, stepOrder, stepKey, stepName,
                    assigneeRole, assigneeUserId, requiresAttachment);

            audit.log("STEP_STARTED", "process_step_instance", instanceId,
                    "{\"instanceId\":\"" + instanceId + "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                    "\",\"stepOrder\":" + stepOrder + "}");
        }

        // (9) Audit
        audit.log("PROCESS_STARTED", "process_instance", instanceId,
                "{\"templateKey\":\"" + jsonEscape(templateKey) +
                "\",\"subjectType\":\"" + jsonEscape(subjectType) +
                "\",\"subjectId\":\"" + subjectId +
                "\",\"projectId\":\"" + projectId + "\"}");

        // Return warnings so the caller can surface them (soft guardrail).
        return new StartProcessResponse(instanceId, "RUNNING", projectId,
                templateKey, subjectType, subjectId, warnings);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.5  COMPLETE STEP
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StepResult completeStep(Authentication auth, UUID instanceId,
                                   UUID stepInstanceId, CompleteStepRequest req) {
        AppUser me = currentUser(auth);

        // Load step + instance in one join (subject_type + subject_id needed for cash hook)
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id, psi.instance_id, psi.status, psi.step_order,
                       psi.step_key, psi.assignee_user_id, psi.assignee_role,
                       psi.requires_attachment,
                       pi.template_id, pi.project_id, pi.status as instance_status,
                       pi.subject_type, pi.subject_id, pi.org_id,
                       coalesce(pts.approval_mode, 'ANY') as approval_mode
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                left join process_template_step pts
                       on pts.template_id = pi.template_id and pts.step_order = psi.step_order
                where psi.id = ? and psi.instance_id = ?
                """,
                stepInstanceId, instanceId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND",
                    "Étape introuvable sur cette instance");
        }
        Map<String, Object> step = rows.get(0);

        if (!"RUNNING".equals(step.get("instance_status"))) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "L'instance de processus n'est pas en cours");
        }
        if (!"PENDING".equals(step.get("status"))) {
            throw new BusinessRuleException("STEP_NOT_PENDING",
                    "L'étape n'est pas en attente");
        }

        UUID projectId = (UUID) step.get("project_id");
        UUID assigneeUserId = (UUID) step.get("assignee_user_id");
        String assigneeRole = (String) step.get("assignee_role");
        boolean requiresAttachment = Boolean.TRUE.equals(step.get("requires_attachment"));
        String subjectType = (String) step.get("subject_type");
        UUID subjectId = (UUID) step.get("subject_id");
        UUID orgId = (UUID) step.get("org_id");
        String stepKey = (String) step.get("step_key");
        String approvalMode = (String) step.get("approval_mode");

        // Permission
        requireStepAccess(me, stepInstanceId, assigneeUserId, assigneeRole, projectId);

        // ── TASK 1: Attachment guard ──────────────────────────────────
        // The step requires a proof document. The proof may be:
        //   (a) a document linked on this step via process_step_document
        //       (req.documentIds) — the approver attaches at decision time;
        //   (b) the payment-proof document already on the linked movement
        //       (movement.document_id, uploaded via the cash attachment endpoint).
        // Either counts. If neither is present → French 400 ATTACHMENT_REQUIRED.
        boolean hasStepDocs = req.documentIds() != null && !req.documentIds().isEmpty();
        boolean hasMovementDoc = false;
        BigDecimal assessedAmount = null;
        if ("CASH_MOVEMENT".equals(subjectType) && subjectId != null) {
            // queryForList (not queryForMap) so a missing movement doesn't
            // throw EmptyResultDataAccessException → 500. A missing movement
            // is unusual but possible (e.g. orphaned process instance from a
            // deleted test movement) — we degrade gracefully: no attachment
            // detected, no assessedAmount in the audit.
            List<Map<String, Object>> mvRows = jdbc.queryForList(
                    """
                    select document_id, amount from cash_movement
                    where id = ? and org_id = ?
                    """,
                    subjectId, orgId);
            if (!mvRows.isEmpty()) {
                Map<String, Object> mv = mvRows.get(0);
                hasMovementDoc = mv.get("document_id") != null;
                assessedAmount = (BigDecimal) mv.get("amount");
            }
        }
        boolean hasAttachment = hasStepDocs || hasMovementDoc;
        if (requiresAttachment && !hasAttachment) {
            throw new BadRequestException("ATTACHMENT_REQUIRED",
                    "Cette étape requiert une pièce justificative. " +
                    "Joignez la facture / le bon de livraison sur le mouvement " +
                    "ou téléversez-la ici avant d'approuver.");
        }

        // ── approval_mode = ALL: record this approver and only flip to DONE when
        //    every ACTIVE user holding the role on the chantier has approved. The
        //    step stays PENDING (and the instance stays RUNNING) until the last
        //    one. Required approvers = project_role_assignee (explicit per-chantier
        //    mapping) UNION project members holding the business role via
        //    user_role+role.code; both sides join app_user and filter is_active =
        //    true so a deactivated user neither blocks completion nor counts. The
        //    union is needed because project_role_assignee is unique per (project,
        //    role) (V27) — at most one user — so ALL steps that must require
        //    several approvers rely on the role-holder set. The org owner
        //    (is_owner) completes an ALL step at once regardless of pending
        //    approvers (STEP_OWNER_OVERRIDE audit). An empty required set
        //    (nobody active holds the role) degrades to single-approval
        //    completion (ALL == ANY).
        if ("ALL".equals(approvalMode)) {
            // Record this approver (idempotent per user).
            jdbc.update("""
                    insert into process_step_approval
                      (step_instance_id, user_id, org_id, comment)
                    values (?, ?, ?, ?)
                    on conflict (step_instance_id, user_id) do nothing
                    """, stepInstanceId, me.getId(), orgId, req.comment());

            // Owner override: the org owner completes an ALL step at once,
            // regardless of other pending approvers.
            if (Boolean.TRUE.equals(me.getIsOwner())) {
                audit.log("STEP_OWNER_OVERRIDE", "process_step_instance", stepInstanceId,
                        "{\"instanceId\":\"" + instanceId +
                        "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                        "\",\"approver\":\"" + me.getId() + "\"" +
                        (subjectType != null ? ",\"subjectType\":\"" + jsonEscape(subjectType) + "\"" : "") +
                        (subjectId != null ? ",\"subjectId\":\"" + subjectId + "\"" : "") +
                        ",\"hasAttachment\":" + hasAttachment +
                        (assessedAmount != null ? ",\"assessedAmount\":" + assessedAmount : "") +
                        "}");
                // fall through to DONE + proceed.
            } else {
                // Required approvers = ACTIVE users holding the role on the chantier:
                // project_role_assignee UNION project members with the business role.
                // Inactive users are excluded from both sides (join app_user
                // is_active = true) so a deactivated approver neither blocks nor counts.
                List<UUID> required = jdbc.queryForList("""
                        select pra.user_id from project_role_assignee pra
                        join app_user u on u.id = pra.user_id and u.is_active = true
                        where pra.project_id = ? and upper(pra.role_code) = upper(?)
                        union
                        select pm.user_id from project_member pm
                        join app_user u on u.id = pm.user_id and u.is_active = true
                        join user_role ur on ur.user_id = pm.user_id
                        join role r on r.id = ur.role_id
                        where pm.project_id = ? and r.code = ?
                        """, UUID.class, projectId, assigneeRole, projectId, assigneeRole);

                if (!required.isEmpty()) {
                    Integer approvedByRequired = jdbc.queryForObject("""
                            select count(distinct psa.user_id) from process_step_approval psa
                            where psa.step_instance_id = ?
                              and psa.org_id = ?
                              and psa.user_id in (
                                  select pra.user_id from project_role_assignee pra
                                  join app_user u on u.id = pra.user_id and u.is_active = true
                                  where pra.project_id = ? and upper(pra.role_code) = upper(?)
                                  union
                                  select pm.user_id from project_member pm
                                  join app_user u on u.id = pm.user_id and u.is_active = true
                                  join user_role ur on ur.user_id = pm.user_id
                                  join role r on r.id = ur.role_id
                                  where pm.project_id = ? and r.code = ?
                              )
                            """, Integer.class, stepInstanceId, orgId,
                            projectId, assigneeRole, projectId, assigneeRole);
                    int approved = approvedByRequired == null ? 0 : approvedByRequired;
                    if (approved < required.size()) {
                        // Not everyone yet — step stays PENDING, instance stays RUNNING.
                        jdbc.update("update process_step_instance set comment = ? where id = ?",
                                req.comment(), stepInstanceId);
                        audit.log("STEP_PARTIAL_APPROVAL", "process_step_instance", stepInstanceId,
                                "{\"instanceId\":\"" + instanceId +
                                "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                                "\",\"approver\":\"" + me.getId() + "\"" +
                                ",\"approvedCount\":" + approved +
                                ",\"requiredCount\":" + required.size() + "}");
                        return new StepResult(stepInstanceId, "PENDING");
                    }
                    // All required approvers have approved -> fall through to DONE.
                }
                // required empty -> degenerate (ALL == ANY): fall through to DONE.
            }
        }

        // Mark step DONE
        jdbc.update(
                """
                update process_step_instance
                set status = 'DONE', completed_at = now(), completed_by = ?, comment = ?
                where id = ?
                """,
                me.getId(), req.comment(), stepInstanceId);

        // Link documents
        if (req.documentIds() != null) {
            for (UUID docId : req.documentIds()) {
                jdbc.update(
                        """
                        insert into process_step_document (step_instance_id, document_id)
                        values (?, ?)
                        on conflict do nothing
                        """,
                        stepInstanceId, docId);
            }
        }

        UUID templateId = (UUID) step.get("template_id");
        int currentOrder = ((Number) step.get("step_order")).intValue();
        String currentKey = (String) step.get("step_key");

        // Load next template step
        List<Map<String, Object>> nextSteps = jdbc.queryForList(
                """
                select step_order, step_key, name, assignee_role, requires_attachment
                from process_template_step
                where template_id = ? and step_order > ?
                order by step_order asc
                limit 1
                """,
                templateId, currentOrder);

        // ── TASK 3: Enriched audit — stepKey, comment, subjectType, subjectId,
        // hasAttachment, assessedAmount (cash subjects only). Lets the movement
        // timeline answer "who approved, with which document, on what amount".
        audit.log("STEP_COMPLETED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId +
                "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"" +
                (subjectType != null ? ",\"subjectType\":\"" + jsonEscape(subjectType) + "\"" : "") +
                (subjectId != null ? ",\"subjectId\":\"" + subjectId + "\"" : "") +
                ",\"hasAttachment\":" + hasAttachment +
                (assessedAmount != null ? ",\"assessedAmount\":" + assessedAmount : "") +
                "}");

        // If the actor is the substitute (acting on behalf of an absent/inactive
        // assignee), log STEP_COMPLETED_BY_SUBSTITUTE in addition to STEP_COMPLETED.
        if (!Objects.equals(me.getId(), assigneeUserId) && assigneeUserId != null) {
            Integer absentOrInactive = jdbc.queryForObject(
                    "select count(*) from app_user where id = ? and (is_active = false or (absent_until is not null and absent_until >= current_date))",
                    Integer.class, assigneeUserId);
            if (absentOrInactive != null && absentOrInactive > 0) {
                Integer subMatch = jdbc.queryForObject(
                        "select count(*) from project_role_assignee where project_id = ? and upper(role_code) = upper(?) and substitute_user_id = ?",
                        Integer.class, projectId, assigneeRole, me.getId());
                if (subMatch != null && subMatch > 0) {
                    String mainName = jdbc.queryForObject(
                            "select full_name from app_user where id = ?", String.class, assigneeUserId);
                    audit.log("STEP_COMPLETED_BY_SUBSTITUTE", "process_step_instance", stepInstanceId,
                            "{\"instanceId\":\"" + instanceId +
                            "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                            "\",\"substitute\":\"" + me.getId() + "\"" +
                            ",\"onBehalfOf\":\"" + jsonEscape(mainName) + "\"" +
                            "}");
                }
            }
        }

        if (!nextSteps.isEmpty()) {
            Map<String, Object> next = nextSteps.get(0);
            int nextOrder = ((Number) next.get("step_order")).intValue();
            String nextKey = (String) next.get("step_key");
            String nextName = (String) next.get("name");
            String nextRole = (String) next.get("assignee_role");
            boolean nextRequiresAttachment = Boolean.TRUE.equals(next.get("requires_attachment"));

            UUID nextAssignee = resolveAssigneeByRole(projectId, nextRole);

            UUID newStepId = UUID.randomUUID();
            jdbc.update(
                    """
                    insert into process_step_instance
                      (id, instance_id, step_order, step_key, name,
                       status, assignee_role, assignee_user_id, requires_attachment,
                       started_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, now(), now())
                    """,
                    newStepId, instanceId, nextOrder, nextKey, nextName,
                    nextRole, nextAssignee, nextRequiresAttachment);

            jdbc.update(
                    "update process_instance set current_step_order = ?, updated_at = now() where id = ?",
                    nextOrder, instanceId);

            audit.log("STEP_STARTED", "process_step_instance", newStepId,
                    "{\"instanceId\":\"" + instanceId +
                    "\",\"stepKey\":\"" + jsonEscape(nextKey) +
                    "\",\"stepOrder\":" + nextOrder + "}");
        } else {
            // Last step completed → process COMPLETED
            jdbc.update(
                    "update process_instance set status = 'COMPLETED', updated_at = now() where id = ?",
                    instanceId);

            audit.log("PROCESS_COMPLETED", "process_instance", instanceId,
                    "{\"completedBy\":\"" + me.getId() + "\"}");

            // ──────────────────────────────────────────────────────────────
            // Cash hook — if subject_type=CASH_MOVEMENT, auto-post the linked
            // movement. Uses me.getId() as the postedBy (the user who clicked
            // the last "Valider"). The cashService throws BusinessRuleException
            // on negative-balance guard failure; this bubbles up and rolls back
            // the entire @Transactional (step DONE + instance COMPLETED).
            // ──────────────────────────────────────────────────────────────
            // subjectType / subjectId / orgId already declared above (TASK 1/3 hoist)
            if ("CASH_MOVEMENT".equals(subjectType) && subjectId != null) {
                cashService.onProcessCompletedPostMovement(subjectId, me.getId());
            }
            // ──────────────────────────────────────────────────────────────
            // Ticket hook — if subject_type=TICKET, set ticket status to APPROVED.
            // The ticket table already exists (V1+V6+V13). We do a simple JDBC
            // update rather than injecting TicketService (avoids a circular dep).
            // On REJECTED, the rejectStep method below already sets the instance
            // to REJECTED — the ticket stays in its current status (the caller
            // can see the process was rejected in the timeline).
            // ──────────────────────────────────────────────────────────────
            if ("TICKET".equals(subjectType) && subjectId != null && orgId != null) {
                jdbc.update(
                        "update ticket set status = 'APPROVED', updated_at = now() where id = ? and org_id = ?",
                        subjectId, orgId);
                audit.log("TICKET_APPROVED_BY_PROCESS", "ticket", subjectId,
                        "{\"instanceId\":\"" + instanceId + "\",\"approvedBy\":\"" + me.getId() + "\"}");
            }
        }

        // Domain event: the step is DONE (an ALL-mode approval still waiting for others returned above).
        domainEvents.publish(orgId != null ? orgId : me.getOrgId(), EventType.PROCESS_STEP_COMPLETED,
                "process_step", stepInstanceId, projectId,
                DomainEventService.payload("instanceId", instanceId, "stepInstanceId", stepInstanceId,
                        "stepKey", stepKey, "subjectType", subjectType, "subjectId", subjectId));

        return new StepResult(stepInstanceId, "DONE");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.6  REJECT STEP
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StepResult rejectStep(Authentication auth, UUID instanceId,
                                 UUID stepInstanceId, RejectStepRequest req) {
        if (req.comment() == null || req.comment().isBlank()) {
            throw new BadRequestException("COMMENT_REQUIRED",
                    "Un commentaire est requis pour rejeter une étape");
        }

        AppUser me = currentUser(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id, psi.status, psi.step_key,
                       psi.assignee_user_id, psi.assignee_role,
                       pi.project_id, pi.status as instance_status,
                       pi.subject_type, pi.subject_id, pi.org_id
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                where psi.id = ? and psi.instance_id = ?
                """,
                stepInstanceId, instanceId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND",
                    "Étape introuvable sur cette instance");
        }
        Map<String, Object> step = rows.get(0);

        if (!"RUNNING".equals(step.get("instance_status"))) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "L'instance de processus n'est pas en cours");
        }
        if (!"PENDING".equals(step.get("status"))) {
            throw new BusinessRuleException("STEP_NOT_PENDING",
                    "L'étape n'est pas en attente");
        }

        UUID projectId = (UUID) step.get("project_id");
        UUID assigneeUserId = (UUID) step.get("assignee_user_id");
        String assigneeRole = (String) step.get("assignee_role");
        String stepKey = (String) step.get("step_key");
        String subjectType = (String) step.get("subject_type");
        UUID subjectId = (UUID) step.get("subject_id");
        UUID orgId = (UUID) step.get("org_id");

        requireStepAccess(me, stepInstanceId, assigneeUserId, assigneeRole, projectId);

        // ── TASK 3: hasAttachment + assessedAmount for the audit (cash subjects) ──
        // queryForList (not queryForMap) so a missing movement doesn't throw
        // EmptyResultDataAccessException → 500. Same null-safe pattern as completeStep.
        boolean hasAttachment = false;
        BigDecimal assessedAmount = null;
        if ("CASH_MOVEMENT".equals(subjectType) && subjectId != null) {
            List<Map<String, Object>> mvRows = jdbc.queryForList(
                    "select document_id, amount from cash_movement where id = ? and org_id = ?",
                    subjectId, orgId);
            if (!mvRows.isEmpty()) {
                Map<String, Object> mv = mvRows.get(0);
                hasAttachment = mv.get("document_id") != null
                        || countStepDocuments(stepInstanceId) > 0;
                assessedAmount = (BigDecimal) mv.get("amount");
            }
        }

        jdbc.update(
                """
                update process_step_instance
                set status = 'REJECTED', completed_at = now(), completed_by = ?, comment = ?
                where id = ?
                """,
                me.getId(), req.comment(), stepInstanceId);

        jdbc.update(
                "update process_instance set status = 'REJECTED', updated_at = now() where id = ?",
                instanceId);

        // ── TASK 3: Enriched audit — same shape as STEP_COMPLETED ──
        audit.log("STEP_REJECTED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId +
                "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"" +
                (subjectType != null ? ",\"subjectType\":\"" + jsonEscape(subjectType) + "\"" : "") +
                (subjectId != null ? ",\"subjectId\":\"" + subjectId + "\"" : "") +
                ",\"hasAttachment\":" + hasAttachment +
                (assessedAmount != null ? ",\"assessedAmount\":" + assessedAmount : "") +
                "}");

        audit.log("PROCESS_REJECTED", "process_instance", instanceId,
                "{\"rejectedBy\":\"" + me.getId() +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");

        // ──────────────────────────────────────────────────────────────
        // Cash hook — if subject_type=CASH_MOVEMENT, clear the link on the
        // movement so the user can edit + resubmit. Movement stays DRAFT.
        // Audit CASH_MOVEMENT_REJECTED is emitted by CashService.
        // ──────────────────────────────────────────────────────────────
        if ("CASH_MOVEMENT".equals(subjectType) && subjectId != null && orgId != null) {
            cashService.handleProcessRejected(subjectId, orgId, me.getId());
        }

        return new StepResult(stepInstanceId, "REJECTED");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.7  CANCEL INSTANCE
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public void cancelInstance(Authentication auth, UUID instanceId, CancelRequest req) {
        AppUser me = currentUser(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select pi.status, pi.project_id, pi.org_id
                from process_instance pi
                where pi.id = ?
                """,
                instanceId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND",
                    "Instance de processus introuvable");
        }
        Map<String, Object> inst = rows.get(0);

        // Only owner or project member can cancel
        UUID projectId = (UUID) inst.get("project_id");
        requireProjectAccess(me, projectId);

        // Only RUNNING can be cancelled
        if (!"RUNNING".equals(inst.get("status"))) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "Seule une instance RUNNING peut être annulée");
        }

        // Set open PENDING steps → SKIPPED
        jdbc.update(
                """
                update process_step_instance
                set status = 'SKIPPED', completed_at = now(), completed_by = ?,
                    comment = 'Instance annulée'
                where instance_id = ? and status = 'PENDING'
                """,
                me.getId(), instanceId);

        // Set instance → CANCELLED
        jdbc.update(
                "update process_instance set status = 'CANCELLED', updated_at = now() where id = ?",
                instanceId);

        audit.log("PROCESS_CANCELLED", "process_instance", instanceId,
                "{\"cancelledBy\":\"" + me.getId() +
                "\",\"reason\":\"" + jsonEscape(req != null ? req.reason() : null) + "\"}");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.9  TIMELINE
    // ═══════════════════════════════════════════════════════════════

    public ProcessTimeline getTimeline(Authentication auth, UUID instanceId) {
        currentUser(auth); // ensure authenticated

        List<Map<String, Object>> instRows = jdbc.queryForList(
                """
                select pi.id, pi.project_id, p.name as project_name,
                       pi.status, pt.key as template_key, pt.name as template_name,
                       pi.subject_type, pi.subject_id, pi.created_at,
                       pi.created_by, pi.current_step_order,
                       u.full_name as created_by_name
                from process_instance pi
                join process_template pt on pt.id = pi.template_id
                join project p on p.id = pi.project_id
                left join app_user u on u.id = pi.created_by
                where pi.id = ?
                """,
                instanceId);
        if (instRows.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND",
                    "Instance de processus introuvable");
        }
        Map<String, Object> inst = instRows.get(0);

        List<StepView> steps = jdbc.query(
                """
                select psi.id as step_instance_id, psi.step_order, psi.step_key, psi.name,
                       psi.status, psi.assignee_role,
                       psi.assignee_user_id, u.full_name as assignee_name,
                       psi.requires_attachment, psi.started_at,
                       psi.completed_at, psi.comment
                from process_step_instance psi
                left join app_user u on u.id = psi.assignee_user_id
                where psi.instance_id = ?
                order by psi.step_order asc, psi.created_at asc
                """,
                (rs, rowNum) -> {
                    UUID stepId = (UUID) rs.getObject("step_instance_id");
                    List<UUID> docIds = jdbc.queryForList(
                            "select document_id from process_step_document where step_instance_id = ?",
                            UUID.class, stepId);
                    return new StepView(
                            stepId,
                            rs.getInt("step_order"),
                            rs.getString("step_key"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getString("assignee_role"),
                            (UUID) rs.getObject("assignee_user_id"),
                            rs.getString("assignee_name"),
                            rs.getBoolean("requires_attachment"),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("completed_at")),
                            rs.getString("comment"),
                            docIds);
                },
                instanceId);

        return new ProcessTimeline(
                (UUID) inst.get("id"),
                (UUID) inst.get("project_id"),
                (String) inst.get("project_name"),
                (String) inst.get("status"),
                (String) inst.get("template_key"),
                (String) inst.get("template_name"),
                (String) inst.get("subject_type"),
                (UUID) inst.get("subject_id"),
                toInstant((Timestamp) inst.get("created_at")),
                (UUID) inst.get("created_by"),
                (String) inst.get("created_by_name"),
                inst.get("current_step_order") == null
                        ? 0 : ((Number) inst.get("current_step_order")).intValue(),
                steps);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.7b  APPROVAL DESK (TASK 1) — single payload for an approver
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET /api/process/steps/{stepInstanceId}/approval-desk
     *
     * <p>Returns everything an approver needs to decide on a CASH_MOVEMENT-
     * linked payment step in a single round-trip:
     * <ul>
     *   <li>process step + instance context (template, current step, assignee)</li>
     *   <li>cash movement payload (assessed vs claimed + variance, paymentMode,
     *       voucher, nature, label, date, status, from-pot name)</li>
     *   <li>linked entities (project name, party name, document name) — JOINed</li>
     *   <li>flags: canComplete / canReject / requiresAttachment / hasAttachment</li>
     *   <li>mini-timeline of previous steps (DONE/REJECTED with who/when/comment)</li>
     * </ul>
     *
     * <p>Two queries total (1 main JOIN + 1 step list) — no per-field N+1.
     *
     * <p>Access: caller must be the step assignee OR hold the business role
     * on the project OR be org owner. Non-assignees who pass the access check
     * still get the payload but with {@code canComplete=false, canReject=false}
     * (read-only — useful for oversight roles like BUREAU/QA). The brief says
     * "others can read if project member (or same rules as timeline)".
     *
     * <p>If the subject is not CASH_MOVEMENT, returns 400
     * SUBJECT_NOT_SUPPORTED_FOR_DESK — the desk is purpose-built for payment
     * approvals; other subjects would need a different payload shape.
     */
    public ApprovalDeskResponse getApprovalDesk(Authentication auth, UUID stepInstanceId) {
        AppUser me = currentUser(auth);

        // ── (1) Main JOIN: step + instance + movement + party + project + pot + document ──
        // Single query pulls every field the desk needs. LEFT JOINs so a step
        // with no movement (shouldn't happen for CASH_MOVEMENT, but defensive)
        // still returns the process context.
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id as step_id, psi.step_order, psi.step_key as current_step_key,
                       psi.name as current_step_name, psi.status as step_status,
                       psi.assignee_user_id, psi.assignee_role,
                       psi.requires_attachment,
                       pi.id as instance_id, pi.status as instance_status,
                       pi.current_step_order, pi.subject_type, pi.subject_id,
                       pt.key as template_key, pt.name as template_name,
                       au.full_name as assignee_name,
                       -- cash movement fields (CASH_MOVEMENT subject only)
                       m.id as movement_id, m.label as movement_label,
                       m.movement_date, m.movement_type, m.status as movement_status,
                       m.amount as assessed_amount, m.claimed_amount,
                       m.variance_reason,
                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref,
                       -- linked entities (names)
                       p.id as project_id, p.name as project_name,
                       py.id as party_id, py.name as party_name, py.type as party_type,
                       fp.id as from_pot_id, fp.name as from_pot_name,
                       d.id as document_id, d.title as document_name
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                left join process_template pt on pt.id = pi.template_id
                left join app_user au on au.id = psi.assignee_user_id
                left join cash_movement m on m.id = pi.subject_id and pi.subject_type = 'CASH_MOVEMENT'
                left join project p on p.id = pi.project_id
                left join party py on py.id = m.party_id
                left join cash_pot fp on fp.id = m.from_pot_id
                left join document d on d.id = m.document_id
                where psi.id = ?
                """,
                stepInstanceId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Étape introuvable");
        }
        Map<String, Object> row = rows.get(0);

        String subjectType = (String) row.get("subject_type");
        if (!"CASH_MOVEMENT".equalsIgnoreCase(subjectType)) {
            throw new BadRequestException("SUBJECT_NOT_SUPPORTED_FOR_DESK",
                    "Le bureau de validation ne supporte que les paiements (sujet CASH_MOVEMENT). " +
                    "Sujet actuel: " + subjectType);
        }

        UUID projectId = (UUID) row.get("project_id");
        UUID assigneeUserId = (UUID) row.get("assignee_user_id");
        String assigneeRole = (String) row.get("assignee_role");

        // ── (2) Access check — read for project members, write for assignees ──
        // requireProjectAccess for the read (404 if not a member and not owner).
        if (projectId != null) requireProjectAccess(me, projectId);
        boolean canAct = canActOnStep(me, assigneeUserId, assigneeRole, projectId);
        boolean stepPending = "PENDING".equalsIgnoreCase((String) row.get("step_status"));
        boolean instanceRunning = "RUNNING".equalsIgnoreCase((String) row.get("instance_status"));
        boolean canComplete = canAct && stepPending && instanceRunning;
        boolean canReject = canAct && stepPending && instanceRunning;

        // ── (3) hasAttachment: movement.document_id OR process_step_document row ──
        boolean hasAttachment = row.get("document_id") != null
                || countStepDocuments(stepInstanceId) > 0;

        // ── (4) varianceAmount = claimed - assessed (null-safe) ──
        BigDecimal assessed = (BigDecimal) row.get("assessed_amount");
        BigDecimal claimed = (BigDecimal) row.get("claimed_amount");
        BigDecimal variance = null;
        if (claimed != null && assessed != null) {
            variance = claimed.subtract(assessed);
        }

        // ── (5) Mini-timeline: all steps of this instance (DONE + current + future PENDING) ──
        // Single query — names JOINed for the assignee.
        List<DeskStepView> steps = jdbc.queryForList(
                """
                select psi.step_order, psi.step_key, psi.name, psi.status,
                       psi.assignee_role, au.full_name as assignee_name,
                       psi.completed_at, psi.comment
                from process_step_instance psi
                left join app_user au on au.id = psi.completed_by
                where psi.instance_id = ?
                order by psi.step_order asc
                """,
                row.get("instance_id"))
                .stream()
                .map(r -> new DeskStepView(
                        r.get("step_order") == null ? 0
                                : ((Number) r.get("step_order")).intValue(),
                        (String) r.get("step_key"),
                        (String) r.get("name"),
                        (String) r.get("status"),
                        (String) r.get("assignee_role"),
                        (String) r.get("assignee_name"),
                        toInstant(r.get("completed_at") instanceof Timestamp t ? t : null),
                        (String) r.get("comment")))
                .toList();

        // ── (6) movementDate as ISO string (yyyy-MM-dd) for the front-end ──
        Object dateObj = row.get("movement_date");
        String movementDate = null;
        if (dateObj instanceof java.sql.Date d) {
            movementDate = d.toLocalDate().toString();
        } else if (dateObj != null) {
            movementDate = dateObj.toString();
        }

        return new ApprovalDeskResponse(
                stepInstanceId,
                (UUID) row.get("instance_id"),
                (String) row.get("template_key"),
                (String) row.get("template_name"),
                (String) row.get("instance_status"),
                row.get("current_step_order") == null ? 0
                        : ((Number) row.get("current_step_order")).intValue(),
                (String) row.get("current_step_key"),
                (String) row.get("current_step_name"),
                assigneeRole,
                assigneeUserId,
                (String) row.get("assignee_name"),
                Boolean.TRUE.equals(row.get("requires_attachment")),
                subjectType,
                (UUID) row.get("subject_id"),
                (UUID) row.get("movement_id"),
                (String) row.get("movement_label"),
                movementDate,
                (String) row.get("movement_type"),
                (String) row.get("movement_status"),
                assessed,
                claimed,
                variance,
                (String) row.get("variance_reason"),
                (String) row.get("payment_mode"),
                (String) row.get("payment_ref"),
                (String) row.get("voucher_no"),
                (String) row.get("nature"),
                (String) row.get("invoice_ref"),
                projectId,
                (String) row.get("project_name"),
                (UUID) row.get("party_id"),
                (String) row.get("party_name"),
                (String) row.get("party_type"),
                (UUID) row.get("from_pot_id"),
                (String) row.get("from_pot_name"),
                (UUID) row.get("document_id"),
                (String) row.get("document_name"),
                canComplete,
                canReject,
                hasAttachment,
                steps);
    }

    /** Count documents linked directly to a step instance (process_step_document). */
    private int countStepDocuments(UUID stepInstanceId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from process_step_document where step_instance_id = ?",
                Integer.class, stepInstanceId);
        return cnt == null ? 0 : cnt;
    }

    /**
     * Non-throwing variant of {@link #requireStepAccess} — returns true if the
     * caller can act on the step (assignee / owner / holds business role on
     * the project), false otherwise. Used by the desk to compute canComplete /
     * canReject WITHOUT throwing for read-only viewers.
     */
    private boolean canActOnStep(AppUser me, UUID assigneeUserId, String assigneeRole, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return true;
        if (Objects.equals(me.getId(), assigneeUserId)) return true;
        if (projectId == null || assigneeRole == null) return false;
        // role-based fallback: I'm a project member AND I hold the business role
        List<UUID> roleMatch = jdbc.queryForList(
                """
                select pm.user_id from project_member pm
                join user_role ur on ur.user_id = pm.user_id
                join role r on r.id = ur.role_id
                where pm.project_id = ? and pm.user_id = ? and r.code = ?
                limit 1
                """,
                UUID.class, projectId, me.getId(), assigneeRole);
        return !roleMatch.isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.8  MY TASKS (cross-project)
    // ═══════════════════════════════════════════════════════════════

    public List<MyTaskSummary> listMyTasks(Authentication auth) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        boolean isOwner = Boolean.TRUE.equals(me.getIsOwner());

        /*
         * Inclusion rules:
         *  A. assignee_user_id = me  (directly assigned)
         *  B. assignee_user_id IS NULL AND I hold the business role (via user_role
         *     join) AND I am a project member of this project
         *  C. org owner sees everything in org
         *
         * NOTE: pm.project_role is the project permission level
         * (ADMIN/MANAGER/ENGINEER/VIEWER), NOT the business role
         * (FINANCE/ACHAT/CHEF_CHANTIER). The fallback join must go through
         * user_role + role.code.
         */
        List<MyTaskSummary> tasks = jdbc.query(
                """
                select psi.id as step_instance_id, pi.id as instance_id, pi.project_id,
                       p.name as project_name,
                       pt.key as template_key, pt.name as template_name,
                       pi.subject_type, pi.subject_id,
                       psi.step_key, psi.name as step_name, psi.status,
                       psi.assignee_role, psi.assignee_user_id,
                       u.full_name as assignee_name,
                       psi.requires_attachment, psi.started_at,
                       cm.label as subject_label, cm.amount as subject_amount,
                       (psi.started_at is not null and pts.due_days is not null
                            and psi.started_at < now() - (pts.due_days * interval '1 day')) as late
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                join project p on p.id = pi.project_id
                join process_template pt on pt.id = pi.template_id
                left join process_template_step pts
                       on pts.template_id = pi.template_id and pts.step_order = psi.step_order
                left join app_user u on u.id = psi.assignee_user_id
                left join cash_movement cm
                       on pi.subject_type = 'CASH_MOVEMENT' and cm.id = pi.subject_id
                where psi.status = 'PENDING'
                  and pi.status = 'RUNNING'
                  and pi.org_id = ?
                  and (
                    ? = true                                                 -- owner sees all
                    or psi.assignee_user_id = ?                             -- directly assigned
                    or (psi.assignee_user_id is null                        -- role fallback
                        and exists (
                            select 1 from project_member pm2
                            join user_role ur2 on ur2.user_id = pm2.user_id
                            join role r2 on r2.id = ur2.role_id
                            where pm2.project_id = pi.project_id
                              and pm2.user_id = ?
                              and r2.code = psi.assignee_role
                        ))
                    or (psi.assignee_user_id is not null                    -- substitute path
                        and exists (
                            select 1 from project_role_assignee pra
                            where pra.project_id = pi.project_id
                              and upper(pra.role_code) = upper(psi.assignee_role)
                              and pra.substitute_user_id = ?
                              and pra.user_id = psi.assignee_user_id
                              and exists (
                                  select 1 from app_user au
                                  where au.id = psi.assignee_user_id
                                    and (au.is_active = false
                                         or (au.absent_until is not null and au.absent_until >= current_date))
                              )
                        ))
                  )
                order by psi.started_at asc nulls last
                """,
                (rs, rowNum) -> new MyTaskSummary(
                        (UUID) rs.getObject("step_instance_id"),
                        (UUID) rs.getObject("instance_id"),
                        (UUID) rs.getObject("project_id"),
                        rs.getString("project_name"),
                        rs.getString("template_key"),
                        rs.getString("template_name"),
                        rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        rs.getString("step_key"),
                        rs.getString("step_name"),
                        rs.getString("status"),
                        rs.getString("assignee_role"),
                        (UUID) rs.getObject("assignee_user_id"),
                        rs.getString("assignee_name"),
                        rs.getBoolean("requires_attachment"),
                        toInstant(rs.getTimestamp("started_at")),
                        rs.getString("subject_label"),
                        rs.getBigDecimal("subject_amount"),
                        rs.getBoolean("late"),
                        null  // onBehalfOf — set in post-processing
                ),
                orgId, isOwner, me.getId(), me.getId(), me.getId());

        // Post-process: set onBehalfOf for tasks seen via the substitute path
        // (the main assignee is absent/inactive and I am the substitute).
        if (!isOwner) {
            List<MyTaskSummary> result = new ArrayList<>();
            for (MyTaskSummary t : tasks) {
                if (t.assigneeUserId() != null && !t.assigneeUserId().equals(me.getId())
                        && t.assigneeName() != null) {
                    Integer subMatch = jdbc.queryForObject(
                            """
                            select count(*) from project_role_assignee pra
                            join app_user au on au.id = pra.user_id
                            where pra.project_id = ? and upper(pra.role_code) = upper(?)
                              and pra.substitute_user_id = ? and pra.user_id = ?
                              and (au.is_active = false
                                   or (au.absent_until is not null and au.absent_until >= current_date))
                            """,
                            Integer.class, t.projectId(), t.assigneeRole(), me.getId(), t.assigneeUserId());
                    if (subMatch != null && subMatch > 0) {
                        result.add(t.withOnBehalfOf(t.assigneeName()));
                        continue;
                    }
                }
                result.add(t);
            }
            return result;
        }
        return tasks;
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.10  LIST BY PROJECT
    // ═══════════════════════════════════════════════════════════════

    public List<ProcessInstanceSummary> listProcessesByProject(
            Authentication auth, UUID projectId, String statusFilter) {

        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireProjectAccess(me, projectId);

        // current_step_name = the PENDING step's name (or last DONE if none pending).
        // current_assignee_name = the resolved person for the current step.
        // total_steps = total steps in the template (for progress bar).
        String sql = """
                select pi.id, pt.key as template_key, pt.name as template_name,
                       pi.status, pi.subject_type, pi.subject_id,
                       pi.created_at, pi.current_step_order,
                       coalesce(
                         (select psi2.name from process_step_instance psi2
                          where psi2.instance_id = pi.id and psi2.status = 'PENDING'
                          order by psi2.step_order asc limit 1),
                         (select psi3.name from process_step_instance psi3
                          where psi3.instance_id = pi.id
                          order by psi3.step_order desc limit 1)
                       ) as current_step_name,
                       coalesce(
                         (select u.full_name from process_step_instance psi4
                          left join app_user u on u.id = psi4.assignee_user_id
                          where psi4.instance_id = pi.id and psi4.status = 'PENDING'
                          order by psi4.step_order asc limit 1),
                         ''
                       ) as current_assignee_name,
                       coalesce(
                         (select r.code from process_step_instance psi5
                          left join user_role ur on ur.user_id = psi5.assignee_user_id
                          left join role r on r.id = ur.role_id
                          where psi5.instance_id = pi.id and psi5.status = 'PENDING'
                          order by psi5.step_order asc limit 1),
                         ''
                       ) as current_assignee_role,
                       (select count(*) from process_template_step ts
                        where ts.template_id = pi.template_id) as total_steps
                from process_instance pi
                join process_template pt on pt.id = pi.template_id
                where pi.project_id = ? and pi.org_id = ?
                """ + (statusFilter != null ? " and pi.status = ?" : "") + """
                order by pi.created_at desc
                """;

        Object[] args = statusFilter != null
                ? new Object[]{projectId, orgId, statusFilter}
                : new Object[]{projectId, orgId};

        return jdbc.query(sql,
                (rs, rowNum) -> new ProcessInstanceSummary(
                        (UUID) rs.getObject("id"),
                        rs.getString("template_key"),
                        rs.getString("template_name"),
                        rs.getString("status"),
                        rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        toInstant(rs.getTimestamp("created_at")),
                        rs.getInt("current_step_order"),
                        rs.getString("current_step_name"),
                        rs.getString("current_assignee_name"),
                        rs.getString("current_assignee_role"),
                        rs.getInt("total_steps")
                ),
                args);
    }

    // ═══════════════════════════════════════════════════════════════
    // TEMPLATE MANAGEMENT  (admin / owner)
    // ═══════════════════════════════════════════════════════════════

    public List<TemplateOption> listTemplatesForOrg(Authentication auth) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        return jdbc.query(
                """
                select id, key, name, active, description from process_template
                where active = true and (org_id = ? or org_id is null)
                order by key
                """,
                (rs, rowNum) -> new TemplateOption(
                        (UUID) rs.getObject("id"),
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getBoolean("active"),
                        rs.getString("description")),
                orgId);
    }

    public List<AdminTemplateOption> listAllTemplates(Authentication auth) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        // One query for the steps of every visible template, grouped in memory.
        Map<UUID, List<TemplateStepView>> stepsByTemplate = new HashMap<>();
        jdbc.query(
                """
                select s.template_id, s.id, s.step_order, s.step_key, s.name, s.assignee_role,
                       s.requires_attachment, s.approval_mode, s.due_days
                from process_template_step s
                join process_template t on t.id = s.template_id
                where t.org_id = ? or t.org_id is null
                order by s.template_id, s.step_order asc
                """,
                rs -> {
                    stepsByTemplate.computeIfAbsent((UUID) rs.getObject("template_id"), k -> new ArrayList<>())
                            .add(new TemplateStepView(
                                    (UUID) rs.getObject("id"),
                                    rs.getInt("step_order"),
                                    rs.getString("step_key"),
                                    rs.getString("name"),
                                    rs.getString("assignee_role"),
                                    rs.getBoolean("requires_attachment"),
                                    rs.getString("approval_mode"),
                                    (Integer) rs.getObject("due_days")));
                },
                orgId);

        return jdbc.query(
                """
                select id, key, name, active, description from process_template
                where org_id = ? or org_id is null
                order by key
                """,
                (rs, rowNum) -> {
                    UUID id = (UUID) rs.getObject("id");
                    return new AdminTemplateOption(
                            id,
                            rs.getString("key"),
                            rs.getString("name"),
                            rs.getBoolean("active"),
                            rs.getString("description"),
                            stepsByTemplate.getOrDefault(id, List.of()));
                },
                orgId);
    }

    public TemplateDetail getTemplate(Authentication auth, UUID templateId) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, key, name, org_id, active, description, created_at,
                       subject_type, conditions::text as conditions, priority
                from process_template
                where id = ? and (org_id = ? or org_id is null)
                """,
                templateId, orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable");
        }
        Map<String, Object> t = rows.get(0);

        List<TemplateStepView> steps = jdbc.query(
                """
                select id, step_order, step_key, name, assignee_role, requires_attachment,
                       approval_mode, due_days
                from process_template_step
                where template_id = ?
                order by step_order asc
                """,
                (rs, rowNum) -> new TemplateStepView(
                        (UUID) rs.getObject("id"),
                        rs.getInt("step_order"),
                        rs.getString("step_key"),
                        rs.getString("name"),
                        rs.getString("assignee_role"),
                        rs.getBoolean("requires_attachment"),
                        rs.getString("approval_mode"),
                        (Integer) rs.getObject("due_days")),
                templateId);

        return new TemplateDetail(
                (UUID) t.get("id"),
                (String) t.get("key"),
                (String) t.get("name"),
                (UUID) t.get("org_id"),
                Boolean.TRUE.equals(t.get("active")),
                (String) t.get("description"),
                toInstant((Timestamp) t.get("created_at")),
                steps,
                (String) t.get("subject_type"),
                parseConditionsJson((String) t.get("conditions")),
                t.get("priority") == null ? null : ((Number) t.get("priority")).intValue());
    }

    @Transactional
    public TemplateDetail createTemplate(Authentication auth, CreateTemplateRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        if (req.key() == null || req.key().isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "La clé est obligatoire");
        if (req.name() == null || req.name().isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "Le nom est obligatoire");

        // Rule-builder: only CASH_PAYMENT rules for now; validate conditions.
        validateSubjectType(req.subjectType());
        Map<String, Object> conditions = validateConditions(orgId, req.conditions());
        Integer priority = req.priority() == null ? 100 : req.priority();

        // Uniqueness check (org-scoped)
        Integer exists = jdbc.queryForObject(
                "select count(*) from process_template where key = ? and org_id = ?",
                Integer.class, req.key().toUpperCase().trim(), orgId);
        if (exists != null && exists > 0) {
            throw new BusinessRuleException("TEMPLATE_KEY_EXISTS",
                    "Un template avec cette clé existe déjà dans votre organisation");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_template
                  (id, key, name, org_id, active, description, created_at, updated_at,
                   subject_type, conditions, priority)
                values (?, ?, ?, ?, true, ?, now(), now(), ?, ?::jsonb, ?)
                """,
                id, req.key().toUpperCase().trim(), req.name().trim(), orgId,
                req.description() != null ? req.description().trim() : null,
                req.subjectType(),
                conditionsToJson(conditions),
                priority);

        audit.log("PROCESS_TEMPLATE_CREATED", "process_template", id,
                "{\"key\":\"" + jsonEscape(req.key()) + "\",\"orgId\":\"" + orgId + "\"}");

        return getTemplate(auth, id);
    }

    @Transactional
    public TemplateDetail updateTemplate(Authentication auth, UUID templateId,
                                         UpdateTemplateRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        // Ensure template belongs to this org (not a system template)
        List<UUID> owned = jdbc.queryForList(
                "select id from process_template where id = ? and org_id = ?",
                UUID.class, templateId, orgId);
        if (owned.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou non modifiable (template système)");
        }

        // Cannot deactivate if RUNNING instances
        if (Boolean.FALSE.equals(req.active())) {
            Integer runningCnt = jdbc.queryForObject(
                    "select count(*) from process_instance where template_id = ? and status = 'RUNNING'",
                    Integer.class, templateId);
            if (runningCnt != null && runningCnt > 0) {
                throw new BusinessRuleException("RUNNING_INSTANCES_EXIST",
                        "Impossible de désactiver : des instances sont en cours d'exécution");
            }
        }

        if (req.name() != null && !req.name().isBlank()) {
            jdbc.update(
                    "update process_template set name = ?, updated_at = now() where id = ?",
                    req.name().trim(), templateId);
        }
        if (req.active() != null) {
            jdbc.update(
                    "update process_template set active = ?, updated_at = now() where id = ?",
                    req.active(), templateId);
        }
        if (req.description() != null) {
            jdbc.update(
                    "update process_template set description = ?, updated_at = now() where id = ?",
                    req.description().isBlank() ? null : req.description().trim(),
                    templateId);
        }
        // Rule-builder fields (validated). System templates are already rejected
        // above (org_id filter), so edits only apply to org-scoped templates.
        if (req.subjectType() != null && !req.subjectType().isBlank()) {
            validateSubjectType(req.subjectType());
            jdbc.update(
                    "update process_template set subject_type = ?, updated_at = now() where id = ?",
                    req.subjectType(), templateId);
        }
        if (req.conditions() != null) {
            Map<String, Object> conditions = validateConditions(orgId, req.conditions());
            jdbc.update(
                    "update process_template set conditions = ?::jsonb, updated_at = now() where id = ?",
                    conditionsToJson(conditions), templateId);
        }
        if (req.priority() != null) {
            jdbc.update(
                    "update process_template set priority = ?, updated_at = now() where id = ?",
                    req.priority(), templateId);
        }

        audit.log("PROCESS_TEMPLATE_UPDATED", "process_template", templateId,
                "{\"active\":\"" + req.active() + "\"}");

        return getTemplate(auth, templateId);
    }

    /**
     * Delete a template (org-scoped only — system templates with org_id IS NULL
     * cannot be deleted). Cannot delete if RUNNING instances exist.
     */
    @Transactional
    public void deleteTemplate(Authentication auth, UUID templateId) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        List<UUID> owned = jdbc.queryForList(
                "select id from process_template where id = ? and org_id = ?",
                UUID.class, templateId, orgId);
        if (owned.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou non supprimable (template système)");
        }

        Integer runningCnt = jdbc.queryForObject(
                "select count(*) from process_instance where template_id = ? and status = 'RUNNING'",
                Integer.class, templateId);
        if (runningCnt != null && runningCnt > 0) {
            throw new BusinessRuleException("RUNNING_INSTANCES_EXIST",
                    "Impossible de supprimer : des instances sont en cours d'exécution");
        }

        // Cascade: delete steps first, then project_process_template, then template
        jdbc.update("delete from process_template_step where template_id = ?", templateId);
        jdbc.update("delete from project_process_template where template_id = ?", templateId);
        jdbc.update("delete from process_template where id = ?", templateId);

        audit.log("PROCESS_TEMPLATE_DELETED", "process_template", templateId,
                "{\"orgId\":\"" + orgId + "\"}");
    }

    /**
     * Reorder steps within a template. Body: list of { stepId, stepOrder }.
     * Updates step_order for each step in a single batch.
     */
    @Transactional
    public TemplateDetail reorderSteps(Authentication auth, UUID templateId,
                                        List<ReorderStepItem> items) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        List<UUID> owned = jdbc.queryForList(
                "select id from process_template where id = ? and org_id = ?",
                UUID.class, templateId, orgId);
        if (owned.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou non modifiable (template système)");
        }

        // Validate: the request must contain every step of the template exactly once.
        List<UUID> templateStepIds = jdbc.queryForList(
                "select id from process_template_step where template_id = ?",
                UUID.class, templateId);
        if (items == null || items.size() != templateStepIds.size()) {
            throw new BadRequestException("REORDER_INCOMPLETE",
                    "La liste doit contenir toutes les étapes");
        }
        Set<UUID> itemIds = new HashSet<>();
        for (ReorderStepItem item : items) {
            if (!templateStepIds.contains(item.stepId()) || !itemIds.add(item.stepId())) {
                throw new BadRequestException("REORDER_INCOMPLETE",
                        "La liste doit contenir toutes les étapes");
            }
        }

        // Two-phase in one transaction: first shift ALL steps of the template to a
        // temp range so the (template_id, step_order) unique constraint cannot
        // clash during the swap, then set the final orders from the items.
        jdbc.update("update process_template_step set step_order = step_order + 100000 where template_id = ?",
                templateId);
        if (items != null) {
            for (ReorderStepItem item : items) {
                jdbc.update(
                        "update process_template_step set step_order = ? where id = ? and template_id = ?",
                        item.stepOrder(), item.stepId(), templateId);
            }
        }

        audit.log("PROCESS_STEPS_REORDERED", "process_template", templateId,
                "{\"count\":" + (items != null ? items.size() : 0) + "}");

        return getTemplate(auth, templateId);
    }

    /** One item in the reorder batch. */
    public record ReorderStepItem(UUID stepId, int stepOrder) {}

    @Transactional
    public TemplateDetail addStep(Authentication auth, UUID templateId, CreateStepRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        requireOwnedTemplate(templateId, orgId);

        if (req.stepKey() == null || req.stepKey().isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "La clé de l'étape est obligatoire");

        UUID stepId = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                    insert into process_template_step
                      (id, template_id, step_order, step_key, name, assignee_role,
                       requires_attachment, approval_mode, due_days)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    stepId, templateId, req.stepOrder(),
                    req.stepKey().toUpperCase().trim(),
                    req.name(), req.assigneeRole(), req.requiresAttachment(),
                    normalizeApprovalMode(req.approvalMode()),
                    normalizeDueDays(req.dueDays()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessRuleException("STEP_EXISTS",
                    "Un step_order ou step_key en conflit existe déjà sur ce template");
        }

        audit.log("PROCESS_STEP_ADDED", "process_template_step", templateId,
                "{\"stepKey\":\"" + jsonEscape(req.stepKey()) +
                "\",\"stepOrder\":" + req.stepOrder() + "}");

        return getTemplate(auth, templateId);
    }

    @Transactional
    public TemplateDetail updateStep(Authentication auth, UUID templateId,
                                     UUID stepId, UpdateStepRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        requireOwnedTemplate(templateId, orgId);

        List<UUID> found = jdbc.queryForList(
                "select id from process_template_step where id = ? and template_id = ?",
                UUID.class, stepId, templateId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Étape introuvable");
        }

        if (req.name() != null)
            jdbc.update("update process_template_step set name = ? where id = ?",
                    req.name(), stepId);
        if (req.assigneeRole() != null)
            jdbc.update("update process_template_step set assignee_role = ? where id = ?",
                    req.assigneeRole(), stepId);
        jdbc.update("update process_template_step set requires_attachment = ? where id = ?",
                req.requiresAttachment(), stepId);
        if (req.stepOrder() != null) {
            try {
                jdbc.update("update process_template_step set step_order = ? where id = ?",
                        req.stepOrder(), stepId);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new BusinessRuleException("STEP_ORDER_CONFLICT",
                        "Un autre step utilise déjà cet ordre");
            }
        }
        // Rule-builder fields.
        if (req.approvalMode() != null && !req.approvalMode().isBlank()) {
            jdbc.update("update process_template_step set approval_mode = ? where id = ?",
                    normalizeApprovalMode(req.approvalMode()), stepId);
        }
        if (req.dueDays() != null) {
            // dueDays = 0 clears the delay (stores null); negative is rejected.
            Integer normalized = normalizeDueDays(req.dueDays());
            jdbc.update("update process_template_step set due_days = ? where id = ?",
                    normalized, stepId);
        }

        audit.log("PROCESS_STEP_UPDATED", "process_template_step", stepId,
                "{\"templateId\":\"" + templateId + "\"}");

        return getTemplate(auth, templateId);
    }

    @Transactional
    public void deleteStep(Authentication auth, UUID templateId, UUID stepId) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        requireOwnedTemplate(templateId, orgId);

        // Cannot delete if RUNNING instances use this template
        Integer runningCnt = jdbc.queryForObject(
                "select count(*) from process_instance where template_id = ? and status = 'RUNNING'",
                Integer.class, templateId);
        if (runningCnt != null && runningCnt > 0) {
            throw new BusinessRuleException("RUNNING_INSTANCES_EXIST",
                    "Impossible de supprimer l'étape : des instances utilisent ce template en cours");
        }

        jdbc.update("delete from process_template_step where id = ? and template_id = ?",
                stepId, templateId);

        audit.log("PROCESS_STEP_DELETED", "process_template_step", stepId,
                "{\"templateId\":\"" + templateId + "\"}");
    }

    private void requireOwnedTemplate(UUID templateId, UUID orgId) {
        List<UUID> owned = jdbc.queryForList(
                "select id from process_template where id = ? and org_id = ?",
                UUID.class, templateId, orgId);
        if (owned.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou non modifiable (template système)");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RULE-BUILDER — copy / sentence / test + condition validation
    // ═══════════════════════════════════════════════════════════════

    /** Body for POST /api/processes/templates/{id}/test. */
    public record TestRuleRequest(UUID projectId, BigDecimal amount,
                                  String nature, String paymentMode) {}

    /** Response for POST /api/processes/templates/{id}/test. */
    public record TestRuleResponse(UUID chosenTemplateId, String chosenTemplateKey,
                                   String chosenTemplateName, Integer chosenPriority,
                                   UUID testedTemplateId, boolean testedMatched,
                                   String reason,
                                   List<ApprovalRuleService.CandidateResult> candidates) {}

    /**
     *  Copy a system (or any visible) template into an org-scoped one with the
     *  same steps + conditions + subject_type + priority. The copy is created
     *  inactive (activate via PUT). POST /api/processes/templates/{id}/copy.
     */
    @Transactional
    public TemplateDetail copyTemplate(Authentication auth, UUID sourceId) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        List<Map<String, Object>> src = jdbc.queryForList(
                """
                select id, key, name, subject_type, conditions::text as conditions, priority
                from process_template
                where id = ? and (org_id = ? or org_id is null)
                """,
                sourceId, orgId);
        if (src.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND", "Template introuvable");
        }
        Map<String, Object> s = src.get(0);
        String baseKey = (String) s.get("key");
        String copyKey = baseKey + "_COPIE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Integer priority = s.get("priority") == null ? 100 : ((Number) s.get("priority")).intValue();

        UUID newId = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_template
                  (id, key, name, org_id, active, description, created_at, updated_at,
                   subject_type, conditions, priority)
                values (?, ?, ?, ?, false, null, now(), now(), ?, ?::jsonb, ?)
                """,
                newId, copyKey, ((String) s.get("name")) + " (copie)", orgId,
                s.get("subject_type"),
                s.get("conditions") == null ? "{}" : s.get("conditions"),
                priority);

        // Copy steps verbatim (same step_order/step_key/name/assignee_role/
        // requires_attachment/approval_mode/due_days).
        jdbc.update(
                """
                insert into process_template_step
                  (id, template_id, step_order, step_key, name, assignee_role,
                   requires_attachment, approval_mode, due_days)
                select gen_random_uuid(), ?, step_order, step_key, name, assignee_role,
                       requires_attachment, approval_mode, due_days
                from process_template_step
                where template_id = ?
                """,
                newId, sourceId);

        audit.log("PROCESS_TEMPLATE_COPIED", "process_template", newId,
                "{\"sourceId\":\"" + sourceId + "\",\"orgId\":\"" + orgId + "\"}");

        return getTemplate(auth, newId);
    }

    /**
     *  GET /api/processes/templates/{id}/sentence — the rule as a French sentence.
     *  Example: "Quand un paiement à partir de 5 000,000 TND est soumis,
     *  validation par : Finance, puis Gérant (2 jours chacun)."
     */
    public String getTemplateSentence(Authentication auth, UUID templateId) {
        TemplateDetail t = getTemplate(auth, templateId);
        String cond = conditionClause(t.conditions(), t.orgId());
        StringBuilder sb = new StringBuilder("Quand un paiement ");
        if (cond.isEmpty()) {
            sb.append("sans condition de montant, de nature ou de mode ");
        } else {
            sb.append(cond).append(" ");
        }
        sb.append("est soumis, validation par : ");
        List<TemplateStepView> steps = t.steps();
        // due_days: if every step has a non-null due_days and they are all equal
        // -> suffix "(N jours chacun)"; otherwise each step carries its own delay.
        Integer common = null;
        boolean allSame = !steps.isEmpty();
        for (TemplateStepView step : steps) {
            if (step.dueDays() == null) { allSame = false; break; }
            if (common == null) common = step.dueDays();
            else if (!common.equals(step.dueDays())) { allSame = false; break; }
        }
        for (int i = 0; i < steps.size(); i++) {
            TemplateStepView step = steps.get(i);
            if (i > 0) sb.append(", puis ");
            sb.append(stepRender(step, t.orgId()));
            if (!allSame && step.dueDays() != null) {
                sb.append(" (").append(daysLabel(step.dueDays())).append(")");
            }
        }
        if (allSame && common != null) {
            sb.append(" (").append(daysLabel(common)).append(" chacun)");
        }
        sb.append(".");
        return sb.toString();
    }

    /** "1 jour" (singular) or "N jours". */
    private static String daysLabel(int n) {
        return n == 1 ? "1 jour" : n + " jours";
    }

    /** Render one step: ANY -> role label; ALL -> "tous les {plural}". */
    private String stepRender(TemplateStepView step, UUID orgId) {
        String label = roleLabel(step.assigneeRole(), orgId);
        if ("ALL".equals(step.approvalMode())) {
            return "tous les membres " + label;
        }
        return label;
    }

    /** Pluralize the first word of a French role label (Chef -> Chefs, Gérant -> Gérants). */
    private static String pluralRole(String label) {
        if (label == null || label.isBlank()) return "approbateurs";
        String[] parts = label.split(" ", 2);
        String first = parts[0];
        if (first.endsWith("s") || first.endsWith("x") || first.endsWith("z")) {
            return label; // already plural-looking
        }
        return first + "s" + (parts.length > 1 ? " " + parts[1] : "");
    }

    /** Build the condition clause (e.g. "à partir de 5 000,000 TND"), empty if none. */
    private String conditionClause(Map<String, Object> conditions, UUID orgId) {
        if (conditions == null || conditions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        BigDecimal min = toBdQuiet(conditions.get("amount_min"));
        BigDecimal max = toBdQuiet(conditions.get("amount_max"));
        if (min != null && max != null) {
            sb.append("de ").append(formatAmount(min)).append(" à ").append(formatAmount(max)).append(" TND");
        } else if (min != null) {
            sb.append("à partir de ").append(formatAmount(min)).append(" TND");
        } else if (max != null) {
            sb.append("jusqu'à ").append(formatAmount(max)).append(" TND");
        }
        List<String> natures = toStringListQuiet(conditions.get("natures"));
        if (!natures.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("de nature ").append(String.join(" ou ", natures));
        }
        List<String> modes = toStringListQuiet(conditions.get("payment_modes"));
        if (!modes.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("réglé par ").append(String.join(" ou ", modes));
        }
        List<String> pids = toStringListQuiet(conditions.get("project_ids"));
        if (!pids.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            List<String> names = new ArrayList<>();
            for (String pid : pids) {
                String name = pid;
                try {
                    UUID u = UUID.fromString(pid);
                    List<String> rows = jdbc.query("select name from project where id = ?",
                            (rs, rn) -> rs.getString(1), u);
                    if (!rows.isEmpty()) name = rows.get(0);
                } catch (Exception ignored) { }
                names.add(name);
            }
            sb.append("sur le chantier ").append(String.join(" ou ", names));
        }
        return sb.toString();
    }

    /**
     *  POST /api/processes/templates/{id}/test — given a payment description,
     *  returns which rule would be chosen today and why. The template {id} is
     *  tested AS IF it were active (even when inactive/draft), alongside the
     *  other active rules. No data is written.
     */
    public TestRuleResponse testRule(Authentication auth, UUID templateId, TestRuleRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();
        requireTemplateVisible(templateId, orgId);

        if (req.amount() == null || req.amount().signum() < 0)
            throw new BadRequestException("INVALID_TEST_INPUT", "Un montant positif est obligatoire");

        RuleMatcher.PaymentFacts facts = new RuleMatcher.PaymentFacts(
                req.projectId(), req.amount(), req.nature(), req.paymentMode());
        // Force-include {id} as if active so a draft rule can be tested.
        ApprovalRuleService.RuleExplanation ex = approvalRuleService.explainRule(
                orgId, "CASH_PAYMENT", facts, templateId);

        String chosenKey = null, chosenName = null;
        Integer chosenPriority = null;
        boolean testedMatched = false;
        for (ApprovalRuleService.CandidateResult c : ex.candidates()) {
            if (c.templateId() != null && c.templateId().equals(ex.chosenId())) {
                chosenKey = c.key();
                chosenName = c.name();
                chosenPriority = c.priority();
            }
            if (c.templateId() != null && c.templateId().equals(templateId)) {
                testedMatched = c.matched();
            }
        }
        return new TestRuleResponse(ex.chosenId(), chosenKey, chosenName, chosenPriority,
                templateId, testedMatched, buildTestReason(ex), ex.candidates());
    }

    private String buildTestReason(ApprovalRuleService.RuleExplanation ex) {
        StringBuilder sb = new StringBuilder();
        for (ApprovalRuleService.CandidateResult c : ex.candidates()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(c.key()).append(" : ")
              .append(c.matched() ? "correspond" : c.reason())
              .append(" (priorité ").append(c.priority()).append(").");
        }
        if (ex.chosenId() == null) {
            sb.append(" Aucune règle ne correspond à ce paiement.");
        } else {
            for (ApprovalRuleService.CandidateResult c : ex.candidates()) {
                if (c.templateId() != null && c.templateId().equals(ex.chosenId())) {
                    sb.append(" Règle choisie : ").append(c.key()).append(".");
                    break;
                }
            }
        }
        return sb.toString();
    }

    private void requireTemplateVisible(UUID templateId, UUID orgId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from process_template where id = ? and (org_id = ? or org_id is null)",
                Integer.class, templateId, orgId);
        if (cnt == null || cnt == 0) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND", "Template introuvable");
        }
    }

    // ── Condition validation (point 2) ────────────────────────────

    private void validateSubjectType(String subjectType) {
        if (subjectType == null || subjectType.isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "Le type de règle est obligatoire");
        if (!"CASH_PAYMENT".equals(subjectType))
            throw new BusinessRuleException("RULE_SUBJECT_TYPE_UNAVAILABLE",
                    "Ce type de règle n'est pas encore disponible");
    }

    private String normalizeApprovalMode(String mode) {
        if (mode == null || mode.isBlank()) return "ANY";
        String m = mode.trim().toUpperCase();
        if (!"ANY".equals(m) && !"ALL".equals(m))
            throw new BadRequestException("INVALID_APPROVAL_MODE",
                    "Le mode d'approbation doit être ANY ou ALL");
        return m;
    }

    /**
     *  Normalize a step due_days: null stays null (no delay); 0 clears the
     *  delay (returns null so null is stored); negative is rejected with a
     *  French error. Positive values pass through.
     */
    private static Integer normalizeDueDays(Integer dueDays) {
        if (dueDays == null) return null;
        int d = dueDays;
        if (d < 0)
            throw new BadRequestException("INVALID_DUE_DAYS",
                    "Le délai de l'étape (en jours) ne peut pas être négatif");
        return d == 0 ? null : d;
    }

    private static final String EMPTY_CONDITION_LIST_MSG =
            "Choisissez au moins une valeur ou désactivez cette condition";

    private Map<String, Object> validateConditions(UUID orgId, Map<String, Object> conditions) {
        if (conditions == null || conditions.isEmpty()) return new HashMap<>();
        Map<String, Object> normalized = new HashMap<>();
        BigDecimal amountMin = null, amountMax = null;
        for (Map.Entry<String, Object> e : conditions.entrySet()) {
            String key = e.getKey();
            switch (key) {
                case "amount_min" -> {
                    BigDecimal bd = toBd(e.getValue(), "INVALID_AMOUNT_MIN",
                            "Le montant minimum doit être un nombre");
                    if (bd.signum() < 0) throw new BadRequestException("INVALID_AMOUNT_MIN",
                            "Le montant minimum ne peut pas être négatif");
                    if (bd.signum() == 0) throw new BadRequestException("INVALID_AMOUNT_MIN",
                            "Le montant minimum doit être supérieur à 0");
                    if (bd.scale() > 3) throw new BadRequestException("INVALID_AMOUNT_MIN",
                            "Le montant minimum doit avoir au plus 3 décimales");
                    amountMin = bd;
                    normalized.put(key, bd);
                }
                case "amount_max" -> {
                    BigDecimal bd = toBd(e.getValue(), "INVALID_AMOUNT_MAX",
                            "Le montant maximum doit être un nombre");
                    if (bd.signum() < 0) throw new BadRequestException("INVALID_AMOUNT_MAX",
                            "Le montant maximum ne peut pas être négatif");
                    if (bd.signum() == 0) throw new BadRequestException("INVALID_AMOUNT_MAX",
                            "Le montant maximum doit être supérieur à 0");
                    if (bd.scale() > 3) throw new BadRequestException("INVALID_AMOUNT_MAX",
                            "Le montant maximum doit avoir au plus 3 décimales");
                    amountMax = bd;
                    normalized.put(key, bd);
                }
                case "natures" -> {
                    List<String> codes = toStringList(e.getValue(), "INVALID_NATURES", "Les natures doivent être une liste");
                    if (codes.isEmpty())
                        throw new BadRequestException("INVALID_NATURES", EMPTY_CONDITION_LIST_MSG);
                    for (String code : codes) {
                        if (natureCode(code) == null)
                            throw new BadRequestException("INVALID_NATURES", "Nature inconnue : " + code);
                    }
                    normalized.put(key, codes);
                }
                case "payment_modes" -> {
                    List<String> codes = toStringList(e.getValue(), "INVALID_PAYMENT_MODES", "Les modes de règlement doivent être une liste");
                    if (codes.isEmpty())
                        throw new BadRequestException("INVALID_PAYMENT_MODES", EMPTY_CONDITION_LIST_MSG);
                    for (String code : codes) {
                        if (paymentModeCode(code) == null)
                            throw new BadRequestException("INVALID_PAYMENT_MODES", "Mode de règlement inconnu : " + code);
                    }
                    normalized.put(key, codes);
                }
                case "project_ids" -> {
                    List<String> ids = toStringList(e.getValue(), "INVALID_PROJECT_IDS", "Les chantiers doivent être une liste");
                    for (String idStr : ids) {
                        UUID pid = toUuid(idStr);
                        Integer cnt = jdbc.queryForObject(
                                "select count(*) from project where id = ? and org_id = ?",
                                Integer.class, pid, orgId);
                        if (cnt == null || cnt == 0)
                            throw new BadRequestException("INVALID_PROJECT_IDS",
                                    "Chantier introuvable dans votre organisation : " + idStr);
                    }
                    normalized.put(key, ids);
                }
                default -> throw new BadRequestException("UNKNOWN_CONDITION_KEY",
                        "Clé de condition inconnue : " + key
                                + " (clés valides : amount_min, amount_max, project_ids, natures, payment_modes)");
            }
        }
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0)
            throw new BadRequestException("INVALID_AMOUNT_RANGE",
                    "Le montant minimum ne peut pas dépasser le montant maximum");
        return normalized;
    }

    private static String natureCode(String code) {
        if (code == null || code.isBlank()) return null;
        try { return MovementNature.valueOf(code.trim().toUpperCase()).name(); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static String paymentModeCode(String code) {
        if (code == null || code.isBlank()) return null;
        try { return PaymentMode.valueOf(code.trim().toUpperCase()).name(); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal toBd(Object value, String code, String errMsg) {
        if (value == null) throw new BadRequestException(code, errMsg);
        try {
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number n) return new BigDecimal(n.toString());
            if (value instanceof String s) return new BigDecimal(s.trim());
            throw new IllegalArgumentException();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException(code, errMsg);
        }
    }

    private static List<String> toStringList(Object value, String code, String errMsg) {
        if (value == null) throw new BadRequestException(code, errMsg);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> col) {
            for (Object o : col) out.add(o == null ? null : o.toString());
        } else {
            out.add(value.toString());
        }
        return out;
    }

    private static UUID toUuid(String s) {
        try { return UUID.fromString(s.trim()); }
        catch (Exception e) {
            throw new BadRequestException("INVALID_PROJECT_IDS", "Identifiant de chantier invalide : " + s);
        }
    }

    private static BigDecimal toBdQuiet(Object value) {
        try { return toBd(value, "INVALID_CONDITION", "x"); } catch (Exception e) { return null; }
    }

    private static List<String> toStringListQuiet(Object value) {
        try { return toStringList(value, "INVALID_CONDITION", "x"); } catch (Exception e) { return List.of(); }
    }

    // ── conditions JSON <-> Map ───────────────────────────────────

    private Map<String, Object> parseConditionsJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return new HashMap<>();
        try {
            return objectMapper.readValue(json,
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String conditionsToJson(Map<String, Object> conditions) {
        if (conditions == null || conditions.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(conditions);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ── French formatting + role labels ───────────────────────────

    private static final DecimalFormat AMOUNT_FORMAT;
    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        AMOUNT_FORMAT = new DecimalFormat("#,##0.000", symbols);
        AMOUNT_FORMAT.setGroupingUsed(true);
    }

    private static String formatAmount(BigDecimal amount) {
        return amount == null ? "" : AMOUNT_FORMAT.format(amount);
    }

    /** Role label from the role table (org-scoped preferred over system); OWNER -> "Gérant"; unknown -> the code. */
    private String roleLabel(String roleCode, UUID orgId) {
        if (roleCode == null || roleCode.isBlank()) return "approbateur";
        String code = roleCode.trim();
        if ("OWNER".equalsIgnoreCase(code)) return "Gérant";
        List<String> rows = jdbc.query(
                "select label from role where code = ? and (org_id = ? or org_id is null) " +
                        "order by (org_id = ?) desc nulls last limit 1",
                (rs, rn) -> rs.getString(1), code, orgId, orgId);
        return rows.isEmpty() ? code : rows.get(0);
    }

    // ═══════════════════════════════════════════════════════════════
    // PROJECT ↔ TEMPLATE CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    public List<ProjectTemplateConfig> listProjectTemplates(Authentication auth, UUID projectId) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireProjectAccess(me, projectId);

        return jdbc.query(
                """
                select pt.id as template_id, pt.key as template_key, pt.name as template_name,
                       ppt.enabled, ppt.default_for_subject_type, ppt.amount_threshold_override
                from process_template pt
                left join project_process_template ppt
                       on ppt.template_id = pt.id and ppt.project_id = ?
                where pt.active = true and (pt.org_id = ? or pt.org_id is null)
                order by pt.key
                """,
                (rs, rowNum) -> new ProjectTemplateConfig(
                        (UUID) rs.getObject("template_id"),
                        rs.getString("template_key"),
                        rs.getString("template_name"),
                        Boolean.TRUE.equals(rs.getObject("enabled")),
                        rs.getString("default_for_subject_type"),
                        rs.getBigDecimal("amount_threshold_override")),
                projectId, orgId);
    }

    @Transactional
    public void assignTemplateToProject(Authentication auth, UUID projectId,
                                        AssignTemplateRequest req) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireOwner(me);

        UUID templateId = jdbc.queryForObject(
                "select id from process_template where key = ? and active = true",
                UUID.class, req.templateKey());
        if (templateId == null) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou inactif: " + req.templateKey());
        }

        Integer projCnt = jdbc.queryForObject(
                "select count(*) from project where id = ? and org_id = ?",
                Integer.class, projectId, orgId);
        if (projCnt == null || projCnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Projet introuvable");
        }

        // Refuse disabling the last unconditional (empty conditions) CASH_PAYMENT
        // rule on this chantier. Also refuse setting a non-null threshold override
        // on the last unconditional CASH_PAYMENT rule (a threshold would make it
        // miss small payments with no other unconditional rule to cover them).
        {
            List<Map<String, Object>> tmpl = jdbc.queryForList(
                    "select subject_type, conditions::text as conditions from process_template where id = ?",
                    templateId);
            if (!tmpl.isEmpty()) {
                String st = (String) tmpl.get(0).get("subject_type");
                String cond = (String) tmpl.get(0).get("conditions");
                boolean emptyCond = cond == null || cond.isBlank() || "{}".equals(cond.trim());
                if ("CASH_PAYMENT".equals(st) && emptyCond) {
                    // Count OTHER enabled unconditional CASH_PAYMENT rules on this chantier.
                    Integer others = jdbc.queryForObject(
                            """
                            select count(*) from process_template pt
                            where pt.subject_type = 'CASH_PAYMENT' and pt.active = true
                              and (pt.org_id = ? or pt.org_id is null)
                              and pt.id <> ?
                              and pt.conditions::text = '{}'
                              and not exists (
                                  select 1 from project_process_template p2
                                  where p2.project_id = ? and p2.template_id = pt.id and p2.enabled = false
                              )
                            """, Integer.class, orgId, templateId, projectId);
                    boolean isLast = others == null || others == 0;
                    // Refuse disabling the last one.
                    if (isLast && Boolean.FALSE.equals(req.enabled())) {
                        throw new BusinessRuleException("LAST_UNCONDITIONAL_RULE",
                                "Au moins une règle de paiement sans condition doit rester active sur ce chantier");
                    }
                    // Refuse setting a non-null threshold on the last one.
                    if (isLast && req.amountThresholdOverride() != null) {
                        throw new BusinessRuleException("LAST_UNCONDITIONAL_RULE",
                                "La règle de paiement sans condition ne peut pas avoir de seuil sur ce chantier : aucune autre règle ne couvrirait les petits montants");
                    }
                }
            }
        }

        jdbc.update(
                """
                insert into project_process_template
                  (project_id, template_id, enabled, default_for_subject_type, amount_threshold_override)
                values (?, ?, ?, ?, ?)
                on conflict (project_id, template_id)
                do update set enabled = excluded.enabled,
                              default_for_subject_type = excluded.default_for_subject_type,
                              amount_threshold_override = excluded.amount_threshold_override
                """,
                projectId, templateId, req.enabled(), req.defaultForSubjectType(), req.amountThresholdOverride());

        audit.log("PROCESS_TEMPLATE_ASSIGNED", "project_process_template", projectId,
                "{\"templateKey\":\"" + jsonEscape(req.templateKey()) +
                "\",\"enabled\":\"" + req.enabled() +
                "\",\"defaultFor\":\"" + jsonEscape(req.defaultForSubjectType()) + "\"}");
    }

    // Kept for backwards-compatibility with the old single-template assign API
    @Transactional
    public void assignTemplateToProject(Authentication auth, UUID projectId,
                                        String templateKey, boolean enabled) {
        assignTemplateToProject(auth, projectId,
                new AssignTemplateRequest(templateKey, enabled, null, null));
    }

    /**
     *  GET /api/projects/{projectId}/pending-approvals — pending approval steps
     *  for this chantier (the "En attente" block): who, days waiting, due_days,
     *  and a late flag (started more than due_days days ago).
     */
    public List<PendingApproval> listPendingApprovals(Authentication auth, UUID projectId) {
        AppUser me = currentUser(auth);
        requireProjectAccess(me, projectId);
        return jdbc.query(
                """
                select psi.id as step_instance_id, pi.id as instance_id,
                       pt.name as template_name, psi.name as step_name,
                       psi.assignee_role, u.full_name as assignee_name,
                       cm.label as subject_label, cm.amount as subject_amount,
                       psi.started_at, pts.due_days,
                       (psi.started_at is not null and pts.due_days is not null
                            and psi.started_at < now() - (pts.due_days * interval '1 day')) as late,
                       (case when psi.started_at is not null
                             then (extract(epoch from now() - psi.started_at) / 86400)::int
                             else null end) as days_waiting
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                join process_template pt on pt.id = pi.template_id
                left join process_template_step pts
                       on pts.template_id = pi.template_id and pts.step_order = psi.step_order
                left join app_user u on u.id = psi.assignee_user_id
                left join cash_movement cm
                       on pi.subject_type = 'CASH_MOVEMENT' and cm.id = pi.subject_id
                where psi.status = 'PENDING' and pi.status = 'RUNNING' and pi.project_id = ?
                order by psi.started_at asc nulls last
                """,
                (rs, rowNum) -> new PendingApproval(
                        (UUID) rs.getObject("step_instance_id"),
                        (UUID) rs.getObject("instance_id"),
                        rs.getString("template_name"),
                        rs.getString("step_name"),
                        rs.getString("assignee_role"),
                        rs.getString("assignee_name"),
                        rs.getString("subject_label"),
                        rs.getBigDecimal("subject_amount"),
                        toInstant(rs.getTimestamp("started_at")),
                        (Integer) rs.getObject("due_days"),
                        rs.getBoolean("late"),
                        (Integer) rs.getObject("days_waiting")),
                projectId);
    }

    // ═══════════════════════════════════════════════════════════════
    // V27 (TASK 1) — Project role assignee API
    //   GET    /api/projects/{projectId}/process-role-assignees
    //   PUT    /api/projects/{projectId}/process-role-assignees  (batch upsert)
    //   DELETE /api/projects/{projectId}/process-role-assignees/{roleCode}
    //   GET    /api/projects/{projectId}/process-roles-needed
    // ═══════════════════════════════════════════════════════════════

    /**
     * V44 helper — normalises a raw app_user.absent_until value (read via
     * JdbcTemplate, so typically a java.sql.Date) into a LocalDate, and
     * returns null when the person is not absent (null column) or when the
     * stored date is before today (the absence has already ended). A date
     * equal to today is still considered absent (absent_until &gt;= today).
     */
    private LocalDate effectiveAbsence(Object raw) {
        if (raw == null) return null;
        LocalDate d;
        if (raw instanceof LocalDate ld) {
            d = ld;
        } else if (raw instanceof java.sql.Date sd) {
            d = sd.toLocalDate();
        } else if (raw instanceof java.util.Date ud) {
            d = ud.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        } else {
            d = LocalDate.parse(raw.toString());
        }
        return d.isBefore(LocalDate.now()) ? null : d;
    }

    /** GET — list all role→user mappings for this project. */
    public List<RoleAssigneeResponse> listRoleAssignees(Authentication auth, UUID projectId) {
        AppUser me = currentUser(auth);
        requireProjectAccess(me, projectId);
        return jdbc.queryForList(
                """
                select pra.role_code, pra.user_id, u.full_name as user_name, u.email as user_email,
                       pra.substitute_user_id, su.full_name as substitute_name,
                       u.absent_until as user_absent_until,
                       su.absent_until as substitute_absent_until
                from project_role_assignee pra
                join app_user u on u.id = pra.user_id
                left join app_user su on su.id = pra.substitute_user_id
                where pra.project_id = ?
                order by pra.role_code asc
                """,
                projectId)
                .stream()
                .map(r -> new RoleAssigneeResponse(
                        (String) r.get("role_code"),
                        (UUID) r.get("user_id"),
                        (String) r.get("user_name"),
                        (String) r.get("user_email"),
                        (UUID) r.get("substitute_user_id"),
                        (String) r.get("substitute_name"),
                        effectiveAbsence(r.get("user_absent_until")),
                        effectiveAbsence(r.get("substitute_absent_until"))))
                .toList();
    }

    /**
     * PUT — upsert batch. Body: { items: [ { roleCode, userId } ] }.
     *
     * Validation:
     *   - userId must exist + belong to caller's org
     *   - For non-OWNER roles, userId must be a project_member of the project
     *   - For OWNER role, userId may be the org owner OR a project_member
     *
     * Auth: org owner OR project ADMIN. We delegate to {@link #requireProjectAccess}
     * for read access, then check project ADMIN/owner for the write itself.
     */
    @Transactional
    public List<RoleAssigneeResponse> upsertRoleAssignees(Authentication auth, UUID projectId,
                                                            UpsertRoleAssigneesRequest req) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireProjectAccess(me, projectId);
        requireProjectAdminOrOwner(me, projectId);

        if (req == null || req.items() == null) {
            throw new BadRequestException("EMPTY_BODY", "Aucun mappage fourni (items manquant)");
        }

        // Cache: all project members for this project (single query, not per item)
        Set<UUID> projectMembers = new HashSet<>(jdbc.queryForList(
                "select user_id from project_member where project_id = ?",
                UUID.class, projectId));

        // Cache: org owner id (for OWNER-role validation)
        UUID orgOwnerId = jdbc.queryForObject(
                "select id from app_user where org_id = ? and is_owner = true limit 1",
                UUID.class, orgId);

        for (RoleAssigneeItem item : req.items()) {
            if (item.roleCode() == null || item.roleCode().isBlank()) {
                throw new BadRequestException("ROLE_CODE_REQUIRED",
                        "Le code rôle est obligatoire pour chaque mappage");
            }
            if (item.userId() == null) {
                throw new BadRequestException("USER_ID_REQUIRED",
                        "L'utilisateur est obligatoire pour le rôle " + item.roleCode());
            }
            // (a) user exists + in org
            Integer userCnt = jdbc.queryForObject(
                    "select count(*) from app_user where id = ? and org_id = ?",
                    Integer.class, item.userId(), orgId);
            if (userCnt == null || userCnt == 0) {
                throw new BadRequestException("UNKNOWN_USER",
                        "Utilisateur introuvable ou hors de votre organisation");
            }
            // (b) For non-OWNER roles, user must be a project_member
            boolean isOwnerRole = "OWNER".equalsIgnoreCase(item.roleCode());
            if (!isOwnerRole && !projectMembers.contains(item.userId())) {
                throw new BadRequestException("USER_NOT_PROJECT_MEMBER",
                        "L'utilisateur n'est pas membre de ce projet (rôle " + item.roleCode() + ")");
            }
            // (c) For OWNER role, user may be org owner OR project_member
            if (isOwnerRole
                    && !Boolean.TRUE.equals(item.userId().equals(orgOwnerId))
                    && !projectMembers.contains(item.userId())) {
                throw new BadRequestException("USER_NOT_PROJECT_MEMBER",
                        "Pour le rôle OWNER : l'utilisateur doit être gérant de l'organisation ou membre du projet");
            }

            // Validate the substitute (if any): must be in the org; for non-OWNER
            // roles, must be a project member (same rules as the main user).
            UUID substituteId = item.substituteUserId();
            if (substituteId != null) {
                Integer subCnt = jdbc.queryForObject(
                        "select count(*) from app_user where id = ? and org_id = ?",
                        Integer.class, substituteId, orgId);
                if (subCnt == null || subCnt == 0) {
                    throw new BadRequestException("UNKNOWN_USER",
                            "Remplaçant introuvable ou hors de votre organisation");
                }
                if (!isOwnerRole && !projectMembers.contains(substituteId)) {
                    throw new BadRequestException("USER_NOT_PROJECT_MEMBER",
                            "Le remplaçant n'est pas membre de ce projet (rôle " + item.roleCode() + ")");
                }
            }

            // Upsert: ON CONFLICT (project_id, role_code) DO UPDATE
            jdbc.update(
                    """
                    insert into project_role_assignee (project_id, role_code, user_id, substitute_user_id, updated_at)
                    values (?, upper(?), ?, ?, now())
                    on conflict (project_id, role_code)
                    do update set user_id = excluded.user_id,
                                  substitute_user_id = excluded.substitute_user_id,
                                  updated_at = now()
                    """,
                    projectId, item.roleCode().trim(), item.userId(), substituteId);

            audit.log("PROJECT_ROLE_ASSIGNED", "project_role_assignee", projectId,
                    "{\"roleCode\":\"" + item.roleCode().trim().toUpperCase() +
                    "\",\"userId\":\"" + item.userId() + "\"}");
        }

        return listRoleAssignees(auth, projectId);
    }

    /** DELETE — drop a single mapping (e.g. "remove the FINANCE → userA override"). */
    @Transactional
    public void deleteRoleAssignee(Authentication auth, UUID projectId, String roleCode) {
        AppUser me = currentUser(auth);
        requireProjectAccess(me, projectId);
        requireProjectAdminOrOwner(me, projectId);

        if (roleCode == null || roleCode.isBlank()) {
            throw new BadRequestException("ROLE_CODE_REQUIRED", "Le code rôle est obligatoire");
        }
        int rows = jdbc.update(
                "delete from project_role_assignee where project_id = ? and upper(role_code) = upper(?)",
                projectId, roleCode.trim());
        if (rows > 0) {
            audit.log("PROJECT_ROLE_UNASSIGNED", "project_role_assignee", projectId,
                    "{\"roleCode\":\"" + roleCode.trim().toUpperCase() + "\"}");
        }
    }

    /**
     * GET — composite view: distinct assignee_role from steps of templates
     * enabled on this project (or all active org/system templates when no
     * project_process_template rows exist), each carrying the current assignee
     * (if any) + the candidate users the owner can pick from.
     *
     * Designed for the UI: a single round-trip returns the whole "Qui valide
     * quoi" table. No N+1 — candidates are resolved once per call (not per role).
     */
    public List<RoleNeeded> listRolesNeeded(Authentication auth, UUID projectId) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireProjectAccess(me, projectId);

        // ── (1) distinct assignee_role from steps of enabled templates ──
        // If project_process_template has rows for this project, restrict to
        // those that are enabled=true; otherwise all active org/system templates.
        Integer pptCount = jdbc.queryForObject(
                "select count(*) from project_process_template where project_id = ?",
                Integer.class, projectId);
        boolean hasProjectConfig = pptCount != null && pptCount > 0;

        String roleSql = hasProjectConfig
                ? """
                  select distinct pts.assignee_role
                  from process_template_step pts
                  join process_template pt on pt.id = pts.template_id
                  join project_process_template ppt
                       on ppt.template_id = pt.id and ppt.project_id = ?
                  where pt.active = true and ppt.enabled = true
                     and (pt.org_id = ? or pt.org_id is null)
                  order by pts.assignee_role asc
                  """
                : """
                  select distinct pts.assignee_role
                  from process_template_step pts
                  join process_template pt on pt.id = pts.template_id
                  where pt.active = true
                     and (pt.org_id = ? or pt.org_id is null)
                  order by pts.assignee_role asc
                  """;
        List<String> roleCodes = hasProjectConfig
                ? jdbc.queryForList(roleSql, String.class, projectId, orgId)
                : jdbc.queryForList(roleSql, String.class, orgId);

        if (roleCodes.isEmpty()) {
            return List.of();
        }

        // ── (2) current mappings for this project (single query) ──
        Map<String, RoleAssigneeResponse> current = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                """
                select pra.role_code, pra.user_id, u.full_name as user_name, u.email as user_email,
                       pra.substitute_user_id, su.full_name as substitute_name,
                       u.absent_until as user_absent_until,
                       su.absent_until as substitute_absent_until
                from project_role_assignee pra
                join app_user u on u.id = pra.user_id
                left join app_user su on su.id = pra.substitute_user_id
                where pra.project_id = ?
                """, projectId)) {
            current.put(
                    String.valueOf(r.get("role_code")).toUpperCase(),
                    new RoleAssigneeResponse(
                            (String) r.get("role_code"),
                            (UUID) r.get("user_id"),
                            (String) r.get("user_name"),
                            (String) r.get("user_email"),
                            (UUID) r.get("substitute_user_id"),
                            (String) r.get("substitute_name"),
                            effectiveAbsence(r.get("user_absent_until")),
                            effectiveAbsence(r.get("substitute_absent_until"))));
        }

        // ── (3) candidate users — project members + org owner (single query) ──
        // The owner picks any project_member for any role; for OWNER role the
        // org owner is also a candidate. We deduplicate on user_id.
        List<RoleCandidate> allCandidates = jdbc.queryForList(
                """
                select u.id, u.full_name, u.email, u.is_owner,
                       (pm.user_id is not null) as is_project_member,
                       u.absent_until
                from app_user u
                left join project_member pm on pm.project_id = ? and pm.user_id = u.id
                where u.org_id = ?
                  and (u.is_owner = true or pm.user_id is not null)
                  and coalesce(u.is_active, true) = true
                order by u.full_name asc
                """,
                projectId, orgId)
                .stream()
                .map(r -> new RoleCandidate(
                        (UUID) r.get("id"),
                        (String) r.get("full_name"),
                        (String) r.get("email"),
                        Boolean.TRUE.equals(r.get("is_owner")),
                        Boolean.TRUE.equals(r.get("is_project_member")),
                        effectiveAbsence(r.get("absent_until"))))
                .toList();

        // ── (4) assemble per role ──
        // For OWNER role, candidates include the org owner + project_members.
        // For other roles, candidates = project_members only (the OWNER role
        // explicitly allows non-member org owners; other roles require project
        // membership — enforced at upsert time).
        List<RoleNeeded> out = new ArrayList<>();
        for (String roleCode : roleCodes) {
            RoleAssigneeResponse currentAssignee = current.get(roleCode.toUpperCase());
            List<RoleCandidate> candidates = "OWNER".equalsIgnoreCase(roleCode)
                    ? allCandidates
                    : allCandidates.stream().filter(RoleCandidate::isProjectMember).toList();
            out.add(new RoleNeeded(roleCode, currentAssignee, candidates));
        }
        return out;
    }

    /**
     * Write-guard: caller must be org owner OR project_member with
     * project_role='ADMIN'. Mirrors STANDING RULE 5: "Only project admin /
     * org owner may set mappings." Project MANAGER is not enough — config
     * changes should require ADMIN (mirrors the existing assignTemplateToProject
     * which uses requireOwner).
     */
    private void requireProjectAdminOrOwner(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        String role = jdbc.queryForObject(
                "select project_role from project_member where project_id = ? and user_id = ?",
                String.class, projectId, me.getId());
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Seul le gérant ou un administrateur du projet peut configurer les responsables processus");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TASK 3 — REASSIGN a RUNNING instance's current PENDING step
    //   POST /api/processes/{instanceId}/steps/{stepInstanceId}/reassign
    //   Body: { userId, comment? }
    //
    // Rules:
    //   - Caller is org owner OR project ADMIN (mirrors the role-assignee PUT).
    //   - Instance must be RUNNING (can't reassign a COMPLETED/REJECTED/
    //     CANCELLED process — those have no live step).
    //   - The target step must be PENDING (reassigning a DONE step is a no-op
    //     and confusing — reject with French 409 instead).
    //   - Target user must be a project_member (or, for OWNER role, may be
    //     the org owner) — same rule as upsertRoleAssignees.
    //   - Audit PROCESS_STEP_REASSIGNED.
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StepResult reassignStep(Authentication auth, UUID instanceId,
                                    UUID stepInstanceId, ReassignStepRequest req) {
        AppUser me = currentUser(auth);
        if (req == null || req.userId() == null) {
            throw new BadRequestException("USER_ID_REQUIRED",
                    "L'utilisateur cible est obligatoire (userId)");
        }
        UUID targetUserId = req.userId();

        // Load the instance + step in one JOIN (we need project_id for the
        // project_member check + the assignee_role for the OWNER special case).
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select pi.id as instance_id, pi.org_id, pi.project_id, pi.status as instance_status,
                       psi.id as step_id, psi.status as step_status, psi.assignee_role
                from process_instance pi
                join process_step_instance psi on psi.instance_id = pi.id
                where pi.id = ? and psi.id = ?
                """,
                instanceId, stepInstanceId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND",
                    "Étape ou instance introuvable");
        }
        Map<String, Object> row = rows.get(0);
        UUID orgId = (UUID) row.get("org_id");
        UUID projectId = (UUID) row.get("project_id");
        String instanceStatus = (String) row.get("instance_status");
        String stepStatus = (String) row.get("step_status");
        String assigneeRole = (String) row.get("assignee_role");

        // (a) caller has org/project write access
        requireProjectAccess(me, projectId);
        requireProjectAdminOrOwner(me, projectId);

        // (b) instance RUNNING — otherwise there's no live step to reassign
        if (!"RUNNING".equalsIgnoreCase(instanceStatus)) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "Impossible de réaffecter une étape d'un processus " + instanceStatus);
        }

        // (c) step PENDING — DONE/REJECTED/SKIPPED steps are immutable
        if (!"PENDING".equalsIgnoreCase(stepStatus)) {
            throw new BusinessRuleException("STEP_NOT_PENDING",
                    "Seule une étape en attente peut être réaffectée (statut actuel: " + stepStatus + ")");
        }

        // (d) target user: in org + active
        Integer userCnt = jdbc.queryForObject(
                "select count(*) from app_user where id = ? and org_id = ? and coalesce(is_active, true) = true",
                Integer.class, targetUserId, orgId);
        if (userCnt == null || userCnt == 0) {
            throw new BadRequestException("UNKNOWN_USER",
                    "Utilisateur introuvable ou inactif");
        }

        // (e) For non-OWNER roles, target must be a project_member.
        // For OWNER role, target may be org owner OR project_member.
        boolean isOwnerRole = "OWNER".equalsIgnoreCase(assigneeRole);
        if (!isOwnerRole) {
            Integer pmCnt = jdbc.queryForObject(
                    "select count(*) from project_member where project_id = ? and user_id = ?",
                    Integer.class, projectId, targetUserId);
            if (pmCnt == null || pmCnt == 0) {
                throw new BadRequestException("USER_NOT_PROJECT_MEMBER",
                        "L'utilisateur n'est pas membre de ce projet (rôle " + assigneeRole + ")");
            }
        } else {
            // OWNER role: target may be the org owner OR a project_member
            Integer ownerOrPmCnt = jdbc.queryForObject(
                    """
                    select count(*) from (
                      select 1 from app_user u where u.id = ? and u.org_id = ? and u.is_owner = true
                      union all
                      select 1 from project_member pm where pm.project_id = ? and pm.user_id = ?
                    ) x
                    """,
                    Integer.class, targetUserId, orgId, projectId, targetUserId);
            if (ownerOrPmCnt == null || ownerOrPmCnt == 0) {
                throw new BadRequestException("USER_NOT_PROJECT_MEMBER",
                        "Pour le rôle OWNER : l'utilisateur doit être gérant de l'organisation ou membre du projet");
            }
        }

        // (f) Update the step's assignee_user_id
        jdbc.update(
                "update process_step_instance set assignee_user_id = ? where id = ?",
                targetUserId, stepInstanceId);

        // (g) Audit — PROCESS_STEP_REASSIGNED
        String comment = req.comment() == null ? "" :
                req.comment().replace("\\", "\\\\").replace("\"", "\\\"");
        audit.log("PROCESS_STEP_REASSIGNED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId +
                "\",\"stepInstanceId\":\"" + stepInstanceId +
                "\",\"toUserId\":\"" + targetUserId + "\"" +
                (!comment.isBlank() ? ",\"comment\":\"" + comment + "\"" : "") + "}");

        return new StepResult(stepInstanceId, "PENDING");
    }
}
