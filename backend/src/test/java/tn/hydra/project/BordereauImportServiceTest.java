package tn.hydra.project;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BordereauImportServiceTest — 23 unit tests for each import rule.
 * Each test quotes the designation from docs/BORDEREAU-ANALYSIS.md.
 */
class BordereauImportServiceTest {

    // §2.4 — UNIT NORMALISATION
    @Test void normaliseUnit_ml_variants() {
        assertThat(BordereauImportService.normaliseUnit("ml")).isEqualTo("ML");
        assertThat(BordereauImportService.normaliseUnit("ML")).isEqualTo("ML");
        assertThat(BordereauImportService.normaliseUnit("mètre linéaire")).isEqualTo("ML");
    }
    @Test void normaliseUnit_m3_and_m2() {
        assertThat(BordereauImportService.normaliseUnit("m³")).isEqualTo("M3");
        assertThat(BordereauImportService.normaliseUnit("m3")).isEqualTo("M3");
        assertThat(BordereauImportService.normaliseUnit("m²")).isEqualTo("M2");
        assertThat(BordereauImportService.normaliseUnit("m2")).isEqualTo("M2");
    }
    @Test void normaliseUnit_u_kg_ens_forfait_lot() {
        assertThat(BordereauImportService.normaliseUnit("U")).isEqualTo("U");
        assertThat(BordereauImportService.normaliseUnit("unité")).isEqualTo("U");
        assertThat(BordereauImportService.normaliseUnit("l'unité")).isEqualTo("U");
        assertThat(BordereauImportService.normaliseUnit("kg")).isEqualTo("KG");
        assertThat(BordereauImportService.normaliseUnit("Ens")).isEqualTo("ENS");
        assertThat(BordereauImportService.normaliseUnit("forfait")).isEqualTo("FORFAIT");
        assertThat(BordereauImportService.normaliseUnit("Forf")).isEqualTo("FORFAIT");
        assertThat(BordereauImportService.normaliseUnit("FF")).isEqualTo("FORFAIT");
        assertThat(BordereauImportService.normaliseUnit("lots")).isEqualTo("LOT");
    }
    @Test void normaliseUnit_unknown_returns_null() {
        assertThat(BordereauImportService.normaliseUnit("F")).isNull();
        assertThat(BordereauImportService.normaliseUnit("abc")).isNull();
        assertThat(BordereauImportService.normaliseUnit("")).isNull();
        assertThat(BordereauImportService.normaliseUnit(null)).isNull();
    }

    // §7.3 — SPECS EXTRACTION
    @Test void extractSpecs_pehd_pipe() {
        Map<String, Object> specs = BordereauImportService.extractSpecs("Conduite PEHD DE 110 PN 16");
        assertThat(specs.get("de_mm")).isEqualTo(110);
        assertThat(specs.get("pn_bar")).isEqualTo(16);
        assertThat(specs.get("materiau")).isEqualTo("PEHD");
    }
    @Test void extractSpecs_fonte_dn() {
        Map<String, Object> specs = BordereauImportService.extractSpecs("Robinet vanne DN 80 PN 16");
        assertThat(specs.get("dn_mm")).isEqualTo(80);
        assertThat(specs.get("pn_bar")).isEqualTo(16);
    }
    @Test void extractSpecs_reservoir_capacite() {
        Map<String, Object> specs = BordereauImportService.extractSpecs("Réservoir sur piliers 250 m³");
        assertThat(specs.get("capacite_m3")).isEqualTo(250.0);
    }
    @Test void extractSpecs_beton_dosage() {
        Map<String, Object> specs = BordereauImportService.extractSpecs("Béton dosé à 350 kg/m3");
        assertThat(specs.get("dosage_kg_m3")).isEqualTo(350);
    }
    @Test void extractSpecs_acier_galvanise() {
        Map<String, Object> specs = BordereauImportService.extractSpecs("Conduite en acier galvanisé DN 63");
        assertThat(specs.get("materiau")).isEqualTo("ACIER_GALVANISE");
        assertThat(specs.get("dn_mm")).isEqualTo(63);
    }

    // §7.4 — OUVRAGE SUGGESTION
    @Test void suggestOuvrage_reservoir() {
        var row = new BordereauImportService.ParsedRow(List.of("42 Construction Réservoir 250 m³"),
                "42-1", "Béton armé", "M3", "30", new BigDecimal("30"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isEqualTo("RESERVOIR");
    }
    @Test void suggestOuvrage_station_pompage() {
        var row = new BordereauImportService.ParsedRow(List.of("43 Construction station de reprise"),
                "43-1", "Béton armé", "M3", "10", new BigDecimal("10"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isEqualTo("STATION_POMPAGE");
    }
    @Test void suggestOuvrage_forage() {
        var row = new BordereauImportService.ParsedRow(List.of("Forage manuel"),
                "A1", "Forage manuel", "U", "6", new BigDecimal("6"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isEqualTo("FORAGE");
    }
    @Test void suggestOuvrage_conduite() {
        var row = new BordereauImportService.ParsedRow(List.of("I Fourniture"),
                "101a", "Conduite PEHD DE 110 PN 16", "ML", "1500", new BigDecimal("1500"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isEqualTo("CONDUITE");
    }
    @Test void suggestOuvrage_branchements() {
        var row = new BordereauImportService.ParsedRow(List.of("II Distribution"),
                "227", "Borne fontaine", "U", "5", new BigDecimal("5"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isEqualTo("BRANCHEMENTS");
    }
    @Test void suggestOuvrage_commun() {
        var row = new BordereauImportService.ParsedRow(List.of("Installation"),
                "1.1", "Amenée du matériel", "FF", "1", new BigDecimal("1"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isEqualTo("COMMUN");
    }
    @Test void suggestOuvrage_no_match() {
        var row = new BordereauImportService.ParsedRow(List.of("Série 100"),
                "101", "Pièce spéciale non identifiée", "U", "2", new BigDecimal("2"), null, false);
        var s = BordereauImportService.suggestOuvrage(row, List.of());
        assertThat(s.ouvrageType()).isNull();
        assertThat(s.ouvrageId()).isNull();
    }

    // §2.2 — FOURNITURE / POSE PAIRING
    @Test void isFourniture_detects_supply_line() {
        assertThat(BordereauImportService.isFourniture("Conduite PEHD PN10 DE 110 — fourniture")).isTrue();
        assertThat(BordereauImportService.isFourniture("Fourniture et pose conduite PEHD")).isFalse();
    }
    @Test void isPose_detects_laying_line() {
        assertThat(BordereauImportService.isPose("Pose et essai conduite PEHD DE 110")).isTrue();
        assertThat(BordereauImportService.isPose("Conduite PEHD DE 110")).isFalse();
    }

    // WORK NATURE
    @Test void inferWorkNature_fourniture() {
        assertThat(BordereauImportService.inferWorkNature("Fourniture conduite PEHD DE 110", List.of())).isEqualTo("FOURNITURE");
    }
    @Test void inferWorkNature_pose() {
        assertThat(BordereauImportService.inferWorkNature("Pose et essai conduite PEHD DE 110", List.of())).isEqualTo("POSE");
    }
    @Test void inferWorkNature_terrassement() {
        assertThat(BordereauImportService.inferWorkNature("Fouille en tranchée terrain de toute nature", List.of())).isEqualTo("TERRASSEMENT");
    }
    @Test void inferWorkNature_genie_civil() {
        assertThat(BordereauImportService.inferWorkNature("Béton armé dosé 350 kg/m3", List.of())).isEqualTo("GENIE_CIVIL");
    }
    @Test void inferWorkNature_equipment() {
        assertThat(BordereauImportService.inferWorkNature("Pompe immergée 4 m3/h HMT 40 m", List.of())).isEqualTo("EQUIPEMENT");
    }

    // NUMBER PARSING
    @Test void parseDecimal_french_format() {
        assertThat(BordereauImportService.parseDecimal("14 609,000")).isEqualByComparingTo(new BigDecimal("14609.000"));
        assertThat(BordereauImportService.parseDecimal("7,5")).isEqualByComparingTo(new BigDecimal("7.5"));
        assertThat(BordereauImportService.parseDecimal("95.5")).isEqualByComparingTo(new BigDecimal("95.5"));
    }

    // TOTAL ROW DETECTION
    @Test void isTotalRow_detects_totals() {
        assertThat(BordereauImportService.isTotalRow("TOTAL HORS TVA")).isTrue();
        assertThat(BordereauImportService.isTotalRow("TVA 19 %")).isTrue();
        assertThat(BordereauImportService.isTotalRow("TOTAL TTC")).isTrue();
        assertThat(BordereauImportService.isTotalRow("Béton armé")).isFalse();
    }
}
