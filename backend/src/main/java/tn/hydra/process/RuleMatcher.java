package tn.hydra.process;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ============================================================================
 *  RuleMatcher — pure-Java selector for the approval-rule that governs a
 *  given payment (feature/approval-rules, Step 3).
 * ============================================================================
 *
 *  No Spring, no JDBC, no I/O: deterministic function of (candidates, facts).
 *  ApprovalRuleService (Step 5) loads candidates from the DB, builds the
 *  {@link RuleCandidate} list, and delegates the pick to {@link #pick}.
 *
 *  Semantics (from the handoff):
 *   * Keep candidates where enabledForProject == true AND every condition key
 *     matches the supplied PaymentFacts.
 *   * Supported condition keys (all optional, all evaluated per candidate):
 *       - amount_min      : inclusive lower bound on facts.amount
 *       - amount_max      : inclusive upper bound on facts.amount
 *       - project_ids     : list membership on facts.projectId
 *       - natures         : list membership on facts.nature
 *       - payment_modes   : list membership on facts.paymentMode
 *   * Unknown condition key -> the candidate does NOT match (defensive: a typo
 *     in a rule never silently matches everything).
 *   * amountThresholdOverride: when non-null on a candidate, it REPLACES
 *     amount_min for that candidate (per-project override of the lower bound).
 *     amount_max is NOT affected.
 *   * Among matching candidates, return the one with the LOWEST priority int
 *     (1000 is the seeded fallback -> matches last). Ties broken by:
 *       1. org-scoped template (orgScoped == true) before system template;
 *       2. older created_at first (nulls last);
 *       3. templateId natural order (final determinism).
 *   * Empty list, or no match -> Optional.empty().
 *
 *  Money (AI-RULES 3): all amount comparisons use BigDecimal; condition values
 *  are coerced via {@link #toBigDecimal}, never via double/float.
 * ============================================================================
 */
public final class RuleMatcher {

    /** Facts about the payment being submitted. */
    public record PaymentFacts(UUID projectId, BigDecimal amount,
                               String nature, String paymentMode) {}

    /**
     * A rule candidate loaded by ApprovalRuleService.
     *
     * @param templateId              process_template.id
     * @param priority                lower wins; 1000 = fallback
     * @param conditions              jsonb conditions map (may be null/empty)
     * @param amountThresholdOverride per-project override; replaces amount_min
     *                                when non-null
     * @param enabledForProject       false => the template is disabled on the
     *                                chantier; the candidate is ignored
     * @param orgScoped               true => org-scoped template (org_id not null);
     *                                wins tie-break over system templates
     * @param createdAt               process_template.created_at; older wins on
     *                                tie-break (nulls last)
     */
    public record RuleCandidate(UUID templateId, int priority,
                                Map<String, Object> conditions,
                                BigDecimal amountThresholdOverride,
                                boolean enabledForProject,
                                boolean orgScoped,
                                Instant createdAt) {}

    private RuleMatcher() {}

    /**
     * @return the matching rule with the lowest priority value, or empty if none.
     */
    public static Optional<UUID> pick(List<RuleCandidate> rules, PaymentFacts facts) {
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }
        RuleCandidate best = null;
        for (RuleCandidate c : rules) {
            if (c == null || !c.enabledForProject()) {
                continue;
            }
            if (!matches(c, facts)) {
                continue;
            }
            if (best == null || TIE_BREAK.compare(c, best) < 0) {
                best = c;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.templateId());
    }

    /**
     * Order: priority asc, then org-scoped before system, then older
     * created_at first (nulls last), then templateId for final determinism.
     */
    private static final Comparator<RuleCandidate> TIE_BREAK =
            Comparator.comparingInt(RuleCandidate::priority)
                    .thenComparingInt(c -> c.orgScoped() ? 0 : 1)
                    .thenComparing(RuleCandidate::createdAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RuleCandidate::templateId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    // ══════════════════════════════════════════════════════════════
    //  Condition evaluation
    // ══════════════════════════════════════════════════════════════

    private static boolean matches(RuleCandidate c, PaymentFacts facts) {
        Map<String, Object> conditions = c.conditions();
        if (conditions == null || conditions.isEmpty()) {
            return true; // no conditions => matches everything
        }
        for (Map.Entry<String, Object> e : conditions.entrySet()) {
            if (!conditionMatches(e.getKey(), e.getValue(), c, facts)) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionMatches(String key, Object value, RuleCandidate c, PaymentFacts facts) {
        return switch (key) {
            case "amount_min" -> {
                // Override replaces amount_min when present.
                BigDecimal min = c.amountThresholdOverride() != null
                        ? c.amountThresholdOverride()
                        : toBigDecimal(value);
                yield min == null || (facts.amount() != null && facts.amount().compareTo(min) >= 0);
            }
            case "amount_max" -> {
                BigDecimal max = toBigDecimal(value);
                yield max == null || (facts.amount() != null && facts.amount().compareTo(max) <= 0);
            }
            case "project_ids" -> inCollection(value, facts.projectId() == null ? null : facts.projectId().toString());
            case "natures" -> inCollection(value, facts.nature());
            case "payment_modes" -> inCollection(value, facts.paymentMode());
            default -> false; // unknown key => defensive no-match
        };
    }

    /**
     * Membership test against a condition value that may be a single scalar or
     * a Collection. Compares by toString() so callers may pass UUID or String
     * for project_ids interchangeably. A null canonical never matches a
     * non-empty condition.
     */
    private static boolean inCollection(Object raw, String canonical) {
        if (canonical == null) {
            return false;
        }
        if (raw instanceof Collection<?> col) {
            for (Object e : col) {
                if (e != null && e.toString().equals(canonical)) {
                    return true;
                }
            }
            return false;
        }
        return raw != null && raw.toString().equals(canonical);
    }

    /**
     * Coerce a condition value to BigDecimal without double/float loss:
     * BigDecimal/BigInteger pass through; Integer/Long use longValue(); other
     * Numbers use BigDecimal.valueOf(doubleValue) (which round-trips via
     * Double.toString); Strings are parsed directly.
     */
    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (o instanceof Integer || o instanceof Long) {
            return BigDecimal.valueOf(((Number) o).longValue());
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (o instanceof String s) {
            return new BigDecimal(s.trim());
        }
        throw new IllegalArgumentException("Cannot convert to BigDecimal: " + o);
    }

}
