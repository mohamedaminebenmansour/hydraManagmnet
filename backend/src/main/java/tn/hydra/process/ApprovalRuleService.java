package tn.hydra.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ============================================================================
 *  ApprovalRuleService — loads rule candidates from the DB and delegates the
 *  pick to the pure-Java {@link RuleMatcher} (feature/approval-rules, Step 5).
 * ============================================================================
 *
 *  findRule(orgId, subjectType, facts):
 *   1. Load every active template whose subject_type matches and that is
 *      visible to the org: org-scoped (org_id = orgId) OR system (org_id NULL).
 *   2. Per-chantier enablement uses the SAME whitelist rule as
 *      ProcessService.start: if the chantier has NO project_process_template
 *      row, every active template is enabled; if it has ANY row, only
 *      templates with an enabled = true row are enabled (and a row may carry
 *      amount_threshold_override, which replaces amount_min locally).
 *   3. Build a RuleCandidate per template (carrying orgScoped + createdAt for
 *      the tie-break) and call RuleMatcher.pick.
 *
 *  Money (AI-RULES 3): amount_threshold_override is read as BigDecimal
 *  (numeric(18,3)) and forwarded unchanged.
 *  Tenancy (AI-RULES 4): both queries filter by org_id / project_id; the
 *  system-template branch uses org_id IS NULL by design (shared fallback).
 * ============================================================================
 */
@Service
public class ApprovalRuleService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRuleService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApprovalRuleService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Explanation types (used by the /test endpoint) ────────────
    public record CandidateResult(UUID templateId, String key, String name,
                                  int priority, boolean matched, String reason) {}
    public record RuleExplanation(UUID chosenId, List<CandidateResult> candidates) {}

    /** A loaded candidate + its display key/name (RuleCandidate itself has no key/name). */
    private record LoadedCandidate(RuleMatcher.RuleCandidate candidate, String key, String name) {}

    /**
     * @return the chosen template id, or empty if no rule matches.
     */
    public Optional<UUID> findRule(UUID orgId, String subjectType, RuleMatcher.PaymentFacts facts) {
        if (orgId == null || subjectType == null || facts == null) {
            return Optional.empty();
        }
        List<RuleMatcher.RuleCandidate> cands = loadedCandidates(orgId, subjectType, facts).stream()
                .map(LoadedCandidate::candidate).toList();
        return RuleMatcher.pick(cands, facts);
    }

    /**
     *  Runs the rule engine and returns, for every active candidate visible to
     *  the org, whether it matched the facts + a short French reason, plus the
     *  chosen template id. Used by POST /api/process/templates/{id}/test (no
     *  data written).
     */
    public RuleExplanation explainRule(UUID orgId, String subjectType, RuleMatcher.PaymentFacts facts) {
        if (orgId == null || subjectType == null || facts == null) {
            return new RuleExplanation(null, List.of());
        }
        List<LoadedCandidate> loaded = loadedCandidates(orgId, subjectType, facts);
        List<RuleMatcher.RuleCandidate> cands = loaded.stream().map(LoadedCandidate::candidate).toList();
        UUID chosen = RuleMatcher.pick(cands, facts).orElse(null);
        List<CandidateResult> results = new ArrayList<>();
        for (LoadedCandidate lc : loaded) {
            RuleMatcher.MatchResult mr = RuleMatcher.explainMatch(lc.candidate(), facts);
            results.add(new CandidateResult(lc.candidate().templateId(), lc.key(), lc.name(),
                    lc.candidate().priority(), mr.matched(), mr.reason()));
        }
        return new RuleExplanation(chosen, results);
    }

    /** Load + build RuleCandidates for every active template visible to the org. */
    private List<LoadedCandidate> loadedCandidates(UUID orgId, String subjectType, RuleMatcher.PaymentFacts facts) {
        List<Map<String, Object>> templates = jdbc.queryForList("""
                select id, key, name, priority, conditions::text as conditions,
                       (org_id is not null) as org_scoped, created_at
                from process_template
                where subject_type = ? and active = true
                  and (org_id = ? or org_id is null)
                """, subjectType, orgId);

        if (templates.isEmpty()) {
            return List.of();
        }

        // Whitelist enablement — SAME rule as ProcessService.start:
        // no rows for the chantier => all active templates enabled;
        // any row => only templates with an enabled = true row are enabled.
        boolean projectHasRows = false;
        Map<UUID, Map<String, Object>> perProject = new HashMap<>();
        if (facts.projectId() != null) {
            Integer rowCount = jdbc.queryForObject(
                    "select count(*) from project_process_template where project_id = ?",
                    Integer.class, facts.projectId());
            projectHasRows = rowCount != null && rowCount > 0;
            if (projectHasRows) {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        select template_id, enabled, amount_threshold_override
                        from project_process_template
                        where project_id = ?
                        """, facts.projectId());
                for (Map<String, Object> r : rows) {
                    perProject.put((UUID) r.get("template_id"), r);
                }
            }
        }

        List<LoadedCandidate> out = new ArrayList<>();
        for (Map<String, Object> t : templates) {
            UUID templateId = (UUID) t.get("id");
            int priority = t.get("priority") == null
                    ? 100 : ((Number) t.get("priority")).intValue();
            Map<String, Object> conditions;
            try {
                conditions = parseConditions((String) t.get("conditions"));
            } catch (Exception e) {
                log.warn("Unparseable conditions on template {}; skipping candidate", templateId, e);
                continue;
            }

            boolean enabledForProject;
            BigDecimal override = null;
            if (!projectHasRows) {
                enabledForProject = true; // whitelist open
            } else {
                Map<String, Object> row = perProject.get(templateId);
                enabledForProject = row != null
                        && (row.get("enabled") == null || Boolean.TRUE.equals(row.get("enabled")));
                if (row != null) {
                    override = toBigDecimal(row.get("amount_threshold_override"));
                }
            }
            boolean orgScoped = Boolean.TRUE.equals(t.get("org_scoped"));
            Instant createdAt = toInstant(t.get("created_at"));
            RuleMatcher.RuleCandidate cand = new RuleMatcher.RuleCandidate(
                    templateId, priority, conditions, override, enabledForProject, orgScoped, createdAt);
            out.add(new LoadedCandidate(cand, (String) t.get("key"), (String) t.get("name")));
        }
        return out;
    }

    private Map<String, Object> parseConditions(String json) throws Exception {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        return objectMapper.readValue(json, MAP_TYPE);
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return null;
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.util.Date d) return d.toInstant();
        return null;
    }
}
