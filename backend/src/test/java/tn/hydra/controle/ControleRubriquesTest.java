package tn.hydra.controle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ControleRubriquesTest — CB-01 rubrique mapping, CB-02 famille.
 */
class ControleRubriquesTest {

    private static String rub(String article, String rawKey) {
        return ControleRubriques.rubrique(article, rawKey);
    }

    // ── CB-01 ──────────────────────────────────────────────────────────────

    @Test
    void rubrique_mapping() {
        assertThat(ControleRubriques.RUBRIQUES).hasSize(27);
        assertThat(ControleRubriques.RUBRIQUES.get(0)).isEqualTo("Abri Forage");
        assertThat(ControleRubriques.RUBRIQUES.get(26)).isEqualTo("ST Haider Methneni");

        assertThat(rub("Abri forage", "abri forage")).isEqualTo("Abri Forage");
        assertThat(rub("Conduite en fonte", "conduite en fonte")).isEqualTo("Conduite en Fonte");
        assertThat(rub("Pièces spéciales", "pieces speciales")).isEqualTo("Piéces spéciales");
        assertThat(rub("Pièces des niches", "pieces des niches")).isEqualTo("piéces des niches");
        assertThat(rub("BC (à préciser)", "bc")).isEqualTo("BC");
        assertThat(rub("RSE 150", "rse 150 d")).isEqualTo("RSE 150 D");
        assertThat(rub("RSE 100", "rse100")).isEqualTo("RSE 100 M");
        assertThat(rub("Station de surpresseur", "station de surpresseur")).isEqualTo("Station de Surpresseur");
        assertThat(rub("ST Abd El Moula", "st abd moula")).isEqualTo("ST Abd Moula");
        // raw_key « local gda » wins over the rule's article
        assertThat(rub("Alimentation (personne) — Local Gda", "local gda")).isEqualTo("Local GDA");
        assertThat(rub("Travaux divers / GC", "travaux gc")).isEqualTo("Travaux GC");
        assertThat(rub("Travaux divers / GC", "travaux divers")).isEqualTo("Travaux Divers");
        // an article already equal to a rubrique stays itself
        assertThat(rub("Charge sur marché", "charges sur marche")).isEqualTo("Charge sur marché");
        assertThat(rub("ST Hamza Msalmi", "st hamza msalmi")).isEqualTo("ST Hamza Msalmi");
        assertThat(rub("MO RSE 500", "mo rse 500")).isEqualTo("MO RSE 500");
        // outside the recap: rubrique = article
        assertThat(rub("Réservoir existant", "rse100 existant")).isEqualTo("Réservoir existant");
        assertThat(rub("Alimentation caisse chantier", "caisse chantier")).isEqualTo("Alimentation caisse chantier");
        assertThat(rub("Non classé", "")).isEqualTo("Non classé");
    }

    // ── CB-02 ──────────────────────────────────────────────────────────────

    @Test
    void famille_mapping() {
        assertThat(ControleRubriques.famille("ST Hamza Msalmi")).isEqualTo("Sous-traitance");
        assertThat(ControleRubriques.famille("ST Abd Moula")).isEqualTo("Sous-traitance");
        assertThat(ControleRubriques.famille("MO RSP")).isEqualTo("Main-d'œuvre");
        assertThat(ControleRubriques.famille("MO RSE 100")).isEqualTo("Main-d'œuvre");
        for (String r : new String[]{"Conduite en PEHD", "Conduite en Fonte", "Piéces spéciales", "piéces des niches"}) {
            assertThat(ControleRubriques.famille(r)).as(r).isEqualTo("Fournitures et conduites");
        }
        for (String r : new String[]{"RSP", "RSE 500", "RSE 150 D", "RSE 100 M", "Station de Surpresseur",
                "Abri Forage", "Clôture", "Local GDA", "Réservoir existant"}) {
            assertThat(ControleRubriques.famille(r)).as(r).isEqualTo("Ouvrages");
        }
        for (String r : new String[]{"Travaux GC", "Travaux Divers", "BC"}) {
            assertThat(ControleRubriques.famille(r)).as(r).isEqualTo("Travaux");
        }
        assertThat(ControleRubriques.famille("Charge sur marché")).isEqualTo("Charges");
        assertThat(ControleRubriques.famille("Alimentation caisse chantier")).isEqualTo("Mouvements internes");
        assertThat(ControleRubriques.famille("Alimentation (personne) — Hosni Hamzaoui")).isEqualTo("Mouvements internes");
        assertThat(ControleRubriques.famille("Encaissement (décomptes)")).isEqualTo("Mouvements internes");
        assertThat(ControleRubriques.famille("Non classé")).isEqualTo("Non classé");
        assertThat(ControleRubriques.famille("Clôture chantier")).isEqualTo("Non classé");
        assertThat(ControleRubriques.famille("")).isEqualTo("Non classé");
    }
}
