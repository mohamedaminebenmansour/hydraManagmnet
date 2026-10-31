package tn.hydra.process;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  RuleMatcherTest — pure unit test for {@link RuleMatcher} (Step 4).
 * ============================================================================
 *
 *  No Spring context: RuleMatcher is a static, deterministic function. Each
 *  case builds its own candidate list + facts and asserts the pick.
 *
 *  Covers the 12 scenarios required by the handoff plus a few extras for
 *  combined conditions, override+max interaction, null amount, project_ids
 *  membership and deterministic tie-breaking.
 * ============================================================================
 */
class RuleMatcherTest {

    private static final UUID PID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PID_OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // ── builders ──────────────────────────────────────────────────

    /** Enabled candidate with conditions, no override. */
    private static RuleMatcher.RuleCandidate cand(UUID id, int priority, Map<String, Object> conditions) {
        return new RuleMatcher.RuleCandidate(id, priority, conditions, null, true);
    }

    /** Fully-specified candidate. */
    private static RuleMatcher.RuleCandidate cand(UUID id, int priority, Map<String, Object> conditions,
                                                  BigDecimal override, boolean enabled) {
        return new RuleMatcher.RuleCandidate(id, priority, conditions, override, enabled);
    }

    private static Map<String, Object> cond(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static RuleMatcher.PaymentFacts facts(BigDecimal amount) {
        return new RuleMatcher.PaymentFacts(PID, amount, "SOUS_TRAITANCE", "CHEQUE");
    }

    private static RuleMatcher.PaymentFacts facts(UUID projectId, BigDecimal amount,
                                                  String nature, String paymentMode) {
        return new RuleMatcher.PaymentFacts(projectId, amount, nature, paymentMode);
    }

    // ── the 12 required scenarios ─────────────────────────────────

    @Test
    void noConditions_matches() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(List.of(cand(a, 100, cond())), facts(new BigDecimal("500")));
        assertThat(r).contains(a);
    }

    @Test
    void amountMin_boundary_exactEquals_matches() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_min", new BigDecimal("500")))),
                facts(new BigDecimal("500"))); // exact boundary
        assertThat(r).contains(a);
    }

    @Test
    void amountMax_boundary_exactEquals_matches() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_max", new BigDecimal("500")))),
                facts(new BigDecimal("500"))); // exact boundary
        assertThat(r).contains(a);
    }

    @Test
    void amountBelowMin_noMatch() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_min", new BigDecimal("500")))),
                facts(new BigDecimal("499.999")));
        assertThat(r).isEmpty();
    }

    @Test
    void amountAboveMax_noMatch() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_max", new BigDecimal("500")))),
                facts(new BigDecimal("500.001")));
        assertThat(r).isEmpty();
    }

    @Test
    void naturesFilter_inList_matches_notIn_doesNot() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // a restricts to MATERIEL; facts nature is SOUS_TRAITANCE -> no match
        Optional<UUID> rNo = RuleMatcher.pick(
                List.of(cand(a, 100, cond("natures", List.of("MATERIEL")))),
                facts(new BigDecimal("100")));
        assertThat(rNo).isEmpty();

        // b allows SOUS_TRAITANCE -> match
        Optional<UUID> rYes = RuleMatcher.pick(
                List.of(cand(b, 100, cond("natures", List.of("SOUS_TRAITANCE", "MATERIEL")))),
                facts(new BigDecimal("100")));
        assertThat(rYes).contains(b);
    }

    @Test
    void paymentModesFilter_inList_matches() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("payment_modes", List.of("CHEQUE", "VIREMENT")))),
                facts(new BigDecimal("100")));
        assertThat(r).contains(a);
    }

    @Test
    void priority_lowestValueWinsAmongMatches() {
        UUID low = UUID.randomUUID();   // priority 10
        UUID high = UUID.randomUUID();  // priority 100
        UUID fallback = UUID.randomUUID(); // priority 1000
        // All match (no conditions). Lowest priority wins -> low.
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(high, 100, cond()),
                        cand(fallback, 1000, cond()),
                        cand(low, 10, cond())),
                facts(new BigDecimal("100")));
        assertThat(r).contains(low);
    }

    @Test
    void enabledForProjectFalse_candidateIgnored() {
        UUID disabled = UUID.randomUUID();
        UUID enabled = UUID.randomUUID();
        // Disabled has BETTER priority (10) but is ignored; enabled (100) wins.
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(disabled, 10, cond(), null, false),
                        cand(enabled, 100, cond())),
                facts(new BigDecimal("100")));
        assertThat(r).contains(enabled);
    }

    @Test
    void amountThresholdOverride_replacesAmountMin() {
        UUID a = UUID.randomUUID();
        // Template says amount_min=1000, but the chantier override sets it to 200.
        // Original min (1000) no longer applies; facts amount 300 now matches
        // (it would have failed against the 1000 min).
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_min", new BigDecimal("1000")),
                        new BigDecimal("200"), true)),
                facts(new BigDecimal("300")));
        assertThat(r).contains(a);
    }

    @Test
    void unknownConditionKey_noMatch() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_currency", "TND"))), // unknown key
                facts(new BigDecimal("100")));
        assertThat(r).isEmpty();
    }

    @Test
    void emptyRuleList_returnsEmpty() {
        Optional<UUID> r = RuleMatcher.pick(List.of(), facts(new BigDecimal("100")));
        assertThat(r).isEmpty();
    }

    // ── extra scenarios ───────────────────────────────────────────

    @Test
    void combinedConditions_allMatch_matches_oneFails_doesNot() {
        UUID a = UUID.randomUUID();
        Map<String, Object> conds = cond(
                "amount_min", new BigDecimal("100"),
                "amount_max", new BigDecimal("1000"),
                "natures", List.of("SOUS_TRAITANCE"),
                "payment_modes", List.of("CHEQUE"));
        // all match
        Optional<UUID> rOk = RuleMatcher.pick(List.of(cand(a, 100, conds)), facts(new BigDecimal("500")));
        assertThat(rOk).contains(a);

        // amount above max -> fails
        Optional<UUID> rOver = RuleMatcher.pick(List.of(cand(a, 100, conds)), facts(new BigDecimal("1500")));
        assertThat(rOver).isEmpty();

        // wrong nature -> fails
        Optional<UUID> rNature = RuleMatcher.pick(List.of(cand(a, 100, conds)),
                facts(PID, new BigDecimal("500"), "MATERIEL", "CHEQUE"));
        assertThat(rNature).isEmpty();
    }

    @Test
    void overrideReplacesMinButMaxStillApplies() {
        UUID a = UUID.randomUUID();
        // min overridden to 100, max stays 1000. Amount 2000: passes min (>=100),
        // fails max (>1000) -> no match.
        Optional<UUID> rOver = RuleMatcher.pick(
                List.of(cand(a, 100,
                        cond("amount_min", new BigDecimal("1000"), "amount_max", new BigDecimal("1000")),
                        new BigDecimal("100"), true)),
                facts(new BigDecimal("2000")));
        assertThat(rOver).isEmpty();

        // Amount 500: passes overridden min (>=100) and max (<=1000) -> match.
        Optional<UUID> rOk = RuleMatcher.pick(
                List.of(cand(a, 100,
                        cond("amount_min", new BigDecimal("1000"), "amount_max", new BigDecimal("1000")),
                        new BigDecimal("100"), true)),
                facts(new BigDecimal("500")));
        assertThat(rOk).contains(a);
    }

    @Test
    void nullAmount_withAmountMin_noMatch() {
        UUID a = UUID.randomUUID();
        Optional<UUID> r = RuleMatcher.pick(
                List.of(cand(a, 100, cond("amount_min", new BigDecimal("100")))),
                facts(PID, null, "SOUS_TRAITANCE", "CHEQUE"));
        assertThat(r).isEmpty();
    }

    @Test
    void projectIdsFilter_inList_matches_notIn_doesNot() {
        UUID a = UUID.randomUUID();
        // in list -> match
        Optional<UUID> rOk = RuleMatcher.pick(
                List.of(cand(a, 100, cond("project_ids", List.of(PID, PID_OTHER)))),
                facts(PID, new BigDecimal("100"), "SOUS_TRAITANCE", "CHEQUE"));
        assertThat(rOk).contains(a);

        // not in list -> no match
        Optional<UUID> rNo = RuleMatcher.pick(
                List.of(cand(a, 100, cond("project_ids", List.of(PID_OTHER)))),
                facts(PID, new BigDecimal("100"), "SOUS_TRAITANCE", "CHEQUE"));
        assertThat(rNo).isEmpty();
    }

    @Test
    void priorityTie_brokenDeterministicallyRegardlessOfListOrder() {
        // Two matching candidates with the SAME priority -> the same winner is
        // returned no matter the input order. This is the determinism property
        // the rule engine relies on (UUID.compareTo is consistent, even though
        // it uses signed-long comparison internally).
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        RuleMatcher.PaymentFacts f = facts(new BigDecimal("100"));
        Optional<UUID> r1 = RuleMatcher.pick(List.of(cand(x, 100, cond()), cand(y, 100, cond())), f);
        Optional<UUID> r2 = RuleMatcher.pick(List.of(cand(y, 100, cond()), cand(x, 100, cond())), f);
        assertThat(r1).isPresent();
        assertThat(r2).isPresent();
        assertThat(r1).isEqualTo(r2); // same winner regardless of order
        assertThat(r1.get()).isIn(x, y);
    }

    @Test
    void nullRules_returnsEmpty() {
        Optional<UUID> r = RuleMatcher.pick(null, facts(new BigDecimal("100")));
        assertThat(r).isEmpty();
    }
}
