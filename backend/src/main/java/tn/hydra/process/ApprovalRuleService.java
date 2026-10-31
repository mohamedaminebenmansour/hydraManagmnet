package tn.hydra.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
 *   2. Load project_process_template rows for the chantier (facts.projectId).
 *      Absent row => the template is enabled by default. A present row with
 *      enabled = false disables the template for that chantier; it may also
 *      carry amount_threshold_override, which replaces amount_min locally.
 *   3. Build a RuleCandidate per template and call RuleMatcher.pick.
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

    /**
     * @return the chosen template id, or empty if no rule matches.
     */
    public Optional<UUID> findRule(UUID orgId, String subjectType, RuleMatcher.PaymentFacts facts) {
        if (orgId == null || subjectType == null || facts == null) {
            return Optional.empty();
        }

        // 1. Active templates visible to the org with this subject_type.
        List<Map<String, Object>> templates = jdbc.queryForList("""
                select id, priority, conditions::text as conditions
                from process_template
                where subject_type = ? and active = true
                  and (org_id = ? or org_id is null)
                """, subjectType, orgId);

        if (templates.isEmpty()) {
            return Optional.empty();
        }

        // 2. Per-project enablement + amount-threshold override rows.
        Map<UUID, Map<String, Object>> perProject = new HashMap<>();
        if (facts.projectId() != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select template_id, enabled, amount_threshold_override
                    from project_process_template
                    where project_id = ?
                    """, facts.projectId());
            for (Map<String, Object> r : rows) {
                perProject.put((UUID) r.get("template_id"), r);
            }
        }

        // 3. Build candidates. A template with unparseable conditions is
        //    skipped (a corrupted rule blob must not silently match every
        //    payment).
        List<RuleMatcher.RuleCandidate> candidates = new ArrayList<>();
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

            boolean enabledForProject = true;
            BigDecimal override = null;
            Map<String, Object> row = perProject.get(templateId);
            if (row != null) {
                Object en = row.get("enabled");
                enabledForProject = en == null || Boolean.TRUE.equals(en);
                override = toBigDecimal(row.get("amount_threshold_override"));
            }
            candidates.add(new RuleMatcher.RuleCandidate(
                    templateId, priority, conditions, override, enabledForProject));
        }

        return RuleMatcher.pick(candidates, facts);
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
}
