package tn.hydra.controle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ControleArticleRulesTest — CA-01, CA-02, CA-07.
 * Expected values are what tools/controle/analyse.py article() / piece() return.
 */
class ControleArticleRulesTest {

    // ── CA-01 ──────────────────────────────────────────────────────────────

    @Test
    void article_rules_matchAnalysePy() {
        assertThat(ControleArticleRules.article("RSE 150 D", "")).isEqualTo("RSE 150");
        assertThat(ControleArticleRules.article("RSE150", "")).isEqualTo("RSE 150");
        assertThat(ControleArticleRules.article("st hamza Msalmi", "Hamza Msalmi")).isEqualTo("ST Hamza Msalmi");
        assertThat(ControleArticleRules.article("caisse chantier", "")).isEqualTo("Alimentation caisse chantier");
        assertThat(ControleArticleRules.article("BC", "")).isEqualTo("BC (à préciser)");
        assertThat(ControleArticleRules.article("Charges sur marché", "")).isEqualTo("Charge sur marché");
        assertThat(ControleArticleRules.article("MO RSP", "")).isEqualTo("MO RSP");
        assertThat(ControleArticleRules.article("", "")).isEqualTo("Non classé");
    }

    // ── CA-02 ──────────────────────────────────────────────────────────────

    @Test
    void piece_rules_matchAnalysePy() {
        assertThat(ControleArticleRules.piece("N°53859", "")).isEqualTo("Facture");
        assertThat(ControleArticleRules.piece("", "335")).isEqualTo("Bon");
        assertThat(ControleArticleRules.piece("N°53859", "335")).isEqualTo("Facture + bon");
        assertThat(ControleArticleRules.piece("O,B", "")).isEqualTo("Mention sans numéro");
        assertThat(ControleArticleRules.piece("", "S")).isEqualTo("Aucune pièce");
    }

    // ── CA-07 ──────────────────────────────────────────────────────────────

    @Test
    void article_moreRules_matchAnalysePy() {
        assertThat(ControleArticleRules.article("Piéces spéciales", "")).isEqualTo("Pièces spéciales");
        assertThat(ControleArticleRules.article("piéces des niches", "")).isEqualTo("Pièces des niches");
        assertThat(ControleArticleRules.article("Station de Surpresseur", "")).isEqualTo("Station de surpresseur");
        assertThat(ControleArticleRules.article("RSE100 Existant", "")).isEqualTo("Réservoir existant");
        assertThat(ControleArticleRules.article("MO RSE 500", "")).isEqualTo("MO RSE 500");
        assertThat(ControleArticleRules.article("Mohamed Ali Laabar", ""))
                .isEqualTo("Alimentation (personne) — Mohamed Ali Laabar");
    }
}
