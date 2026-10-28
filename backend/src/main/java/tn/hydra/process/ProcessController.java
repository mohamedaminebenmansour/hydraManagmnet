package tn.hydra.process;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Process engine REST controller.
 *
 * Base routes:
 *   /api/processes/...                          — templates + runtime
 *   /api/projects/{projectId}/...               — project-scoped endpoints
 *
 * All endpoints require authentication (enforced globally in SecurityConfig).
 * Never returns 500 with an empty body — GlobalExceptionHandler handles that.
 */
@RestController
public class ProcessController {

    private final ProcessService svc;

    public ProcessController(ProcessService svc) {
        this.svc = svc;
    }

    // ══════════════════════════════════════════════════════════════
    //  TEMPLATE MANAGEMENT  (admin / owner)
    // ══════════════════════════════════════════════════════════════

    /** List all active templates visible to the caller's org (for dropdowns). */
    @GetMapping("/api/processes/templates")
    public ResponseEntity<List<ProcessService.TemplateOption>> listTemplates() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listTemplatesForOrg(auth));
    }

    /** List ALL templates (incl. inactive) — owner only. */
    @GetMapping("/api/processes/templates/admin")
    public ResponseEntity<List<ProcessService.TemplateOption>> listAllTemplates() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listAllTemplates(auth));
    }

    /** Get full template detail with steps — owner only. */
    @GetMapping("/api/processes/templates/{id}")
    public ResponseEntity<ProcessService.TemplateDetail> getTemplate(@PathVariable UUID id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.getTemplate(auth, id));
    }

    /** Create a new org-scoped template — owner only. */
    @PostMapping("/api/processes/templates")
    public ResponseEntity<ProcessService.TemplateDetail> createTemplate(
            @RequestBody ProcessService.CreateTemplateRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.TemplateDetail created = svc.createTemplate(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Update name / active flag of an org-scoped template — owner only. */
    @PutMapping("/api/processes/templates/{id}")
    public ResponseEntity<ProcessService.TemplateDetail> updateTemplate(
            @PathVariable UUID id,
            @RequestBody ProcessService.UpdateTemplateRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.updateTemplate(auth, id, req));
    }

    // ── Steps within a template ────────────────────────────────────

    /** Add a step to an org-scoped template — owner only. */
    @PostMapping("/api/processes/templates/{id}/steps")
    public ResponseEntity<ProcessService.TemplateDetail> addStep(
            @PathVariable UUID id,
            @RequestBody ProcessService.CreateStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.TemplateDetail updated = svc.addStep(auth, id, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(updated);
    }

    /** Update an existing step — owner only. */
    @PutMapping("/api/processes/templates/{id}/steps/{stepId}")
    public ResponseEntity<ProcessService.TemplateDetail> updateStep(
            @PathVariable UUID id,
            @PathVariable UUID stepId,
            @RequestBody ProcessService.UpdateStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.updateStep(auth, id, stepId, req));
    }

    /**
     * Delete a step — owner only.
     * Rejected if any RUNNING instance uses the template.
     */
    @DeleteMapping("/api/processes/templates/{id}/steps/{stepId}")
    public ResponseEntity<Void> deleteStep(
            @PathVariable UUID id,
            @PathVariable UUID stepId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        svc.deleteStep(auth, id, stepId);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════
    //  PROJECT ↔ TEMPLATE CONFIGURATION
    // ══════════════════════════════════════════════════════════════

    /** List templates and their enabled/default status for a project. */
    @GetMapping("/api/projects/{projectId}/process-templates")
    public ResponseEntity<List<ProcessService.ProjectTemplateConfig>> listProjectTemplates(
            @PathVariable UUID projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listProjectTemplates(auth, projectId));
    }

    /**
     * Enable / disable a template on a project, and optionally set it as
     * the default for a subject type (e.g. PAYMENT).
     * Owner only.
     */
    @PutMapping("/api/projects/{projectId}/process-templates")
    public ResponseEntity<Void> assignTemplate(
            @PathVariable UUID projectId,
            @RequestBody ProcessService.AssignTemplateRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        svc.assignTemplateToProject(auth, projectId, req);
        return ResponseEntity.ok().build();
    }

    // ══════════════════════════════════════════════════════════════
    //  RUNTIME — start / complete / reject / cancel
    // ══════════════════════════════════════════════════════════════

    /**
     * Start a new process instance.
     * POST /api/processes/start
     * Body: { projectId, templateKey, subjectType, subjectId }
     * Returns 201 with the new instance summary.
     */
    @PostMapping("/api/processes/start")
    public ResponseEntity<ProcessService.StartProcessResponse> start(
            @RequestBody ProcessService.StartProcessCommand cmd) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.StartProcessResponse resp = svc.start(auth, cmd);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * Complete the current PENDING step.
     * POST /api/processes/{instanceId}/steps/{stepInstanceId}/complete
     * Body: { comment?, documentIds? }
     */
    @PostMapping("/api/processes/{instanceId}/steps/{stepInstanceId}/complete")
    public ResponseEntity<ProcessService.StepResult> completeStep(
            @PathVariable UUID instanceId,
            @PathVariable UUID stepInstanceId,
            @RequestBody ProcessService.CompleteStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.StepResult result = svc.completeStep(auth, instanceId, stepInstanceId, req);
        return ResponseEntity.ok(result);
    }

    /**
     * Reject the current PENDING step (comment required).
     * POST /api/processes/{instanceId}/steps/{stepInstanceId}/reject
     * Body: { comment: "required non-blank" }
     */
    @PostMapping("/api/processes/{instanceId}/steps/{stepInstanceId}/reject")
    public ResponseEntity<ProcessService.StepResult> rejectStep(
            @PathVariable UUID instanceId,
            @PathVariable UUID stepInstanceId,
            @RequestBody ProcessService.RejectStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.StepResult result = svc.rejectStep(auth, instanceId, stepInstanceId, req);
        return ResponseEntity.ok(result);
    }

    /**
     * Cancel a RUNNING process instance (sets open steps to SKIPPED).
     * POST /api/processes/{instanceId}/cancel
     * Body: { reason? }
     */
    @PostMapping("/api/processes/{instanceId}/cancel")
    public ResponseEntity<Void> cancelInstance(
            @PathVariable UUID instanceId,
            @RequestBody(required = false) ProcessService.CancelRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        svc.cancelInstance(auth, instanceId, req);
        return ResponseEntity.ok().build();
    }

    // ══════════════════════════════════════════════════════════════
    //  READ / QUERY ENDPOINTS
    // ══════════════════════════════════════════════════════════════

    /**
     * Full traceability timeline for one process instance.
     * GET /api/processes/{instanceId}/timeline
     */
    @GetMapping("/api/processes/{instanceId}/timeline")
    public ResponseEntity<ProcessService.ProcessTimeline> getTimeline(
            @PathVariable UUID instanceId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.getTimeline(auth, instanceId));
    }

    /**
     * Cross-project pending tasks for the authenticated user.
     * GET /api/processes/my-tasks
     */
    @GetMapping("/api/processes/my-tasks")
    public ResponseEntity<List<ProcessService.MyTaskSummary>> getMyTasks() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listMyTasks(auth));
    }

    /**
     * All process instances for a project, with optional status filter.
     * GET /api/projects/{projectId}/processes?status=RUNNING
     */
    @GetMapping("/api/projects/{projectId}/processes")
    public ResponseEntity<List<ProcessService.ProcessInstanceSummary>> listByProject(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String status) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listProcessesByProject(auth, projectId, status));
    }

    // ══════════════════════════════════════════════════════════════
    //  V27 (TASK 1) — PROJECT ROLE ASSIGNEES
    //  Per-project business role → user mapping. Powers the "Qui valide quoi"
    //  UI section: the owner configures, for this project, who plays FINANCE,
    //  who is the OWNER approver, who is ACHAT, etc.
    // ══════════════════════════════════════════════════════════════

    /**
     * GET /api/projects/{projectId}/process-role-assignees
     * Returns the current role→user mappings for this project.
     */
    @GetMapping("/api/projects/{projectId}/process-role-assignees")
    public ResponseEntity<List<ProcessService.RoleAssigneeResponse>> listRoleAssignees(
            @PathVariable UUID projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listRoleAssignees(auth, projectId));
    }

    /**
     * PUT /api/projects/{projectId}/process-role-assignees
     * Body: { items: [ { roleCode, userId } ] } — upsert batch.
     * Auth: org owner OR project ADMIN.
     * Returns the full updated mapping list.
     */
    @PutMapping("/api/projects/{projectId}/process-role-assignees")
    public ResponseEntity<List<ProcessService.RoleAssigneeResponse>> upsertRoleAssignees(
            @PathVariable UUID projectId,
            @RequestBody ProcessService.UpsertRoleAssigneesRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.upsertRoleAssignees(auth, projectId, req));
    }

    /**
     * DELETE /api/projects/{projectId}/process-role-assignees/{roleCode}
     * Drops a single mapping (e.g. "remove the FINANCE → userA override").
     * Auth: org owner OR project ADMIN.
     */
    @DeleteMapping("/api/projects/{projectId}/process-role-assignees/{roleCode}")
    public ResponseEntity<Void> deleteRoleAssignee(
            @PathVariable UUID projectId,
            @PathVariable String roleCode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        svc.deleteRoleAssignee(auth, projectId, roleCode);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/projects/{projectId}/process-roles-needed
     * Composite view for the "Qui valide quoi" UI: distinct assignee_role
     * from steps of templates enabled on this project (or all active
     * org/system templates when no project_process_template rows exist),
     * each with the current assignee (if any) + the candidate users
     * the owner can pick from.
     */
    @GetMapping("/api/projects/{projectId}/process-roles-needed")
    public ResponseEntity<List<ProcessService.RoleNeeded>> listRolesNeeded(
            @PathVariable UUID projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(svc.listRolesNeeded(auth, projectId));
    }
}
