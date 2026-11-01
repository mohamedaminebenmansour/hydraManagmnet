package tn.hydra.project;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: imports HYDRA-test-bordereau-Bouslim.xlsx and checks every
 * row of the "Résultat attendu" sheet.
 *
 * <p>Tests the service's own parseAndAnalyse + pairLines methods directly (no
 * database needed). If this test fails, fix the import code, never the test
 * or the xlsx file.
 */
class BordereauBouslimIntegrationTest {

    private static final String TEST_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    private List<BordereauImportService.AnalysedLine> analysed;

    @Test
    void import_bouslim_xlsx() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(TEST_FILE));

        // Build the 5 ouvrages the chantier has (for suggestion testing).
        UUID conduiteId = UUID.randomUUID();
        UUID reservoir250Id = UUID.randomUUID();
        UUID reservoir150Id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID communId = UUID.randomUUID();
        List<Map<String, Object>> ouvrages = List.of(
                Map.of("id", conduiteId, "type", "CONDUITE", "name", "Conduite"),
                Map.of("id", reservoir250Id, "type", "RESERVOIR", "name", "Réservoir 250 m³ sur piliers"),
                Map.of("id", reservoir150Id, "type", "RESERVOIR", "name", "Réservoir 150 m³ semi-enterré"),
                Map.of("id", stationId, "type", "STATION_POMPAGE", "name", "Station de reprise"),
                Map.of("id", communId, "type", "COMMUN", "name", "Commun")
        );

        // Call the service's own method (no DB).
        analysed = BordereauImportService.parseAndAnalyse(bytes, ouvrages);

        // Separate article lines from section titles.
        List<BordereauImportService.AnalysedLine> articles = analysed.stream()
                .filter(a -> a.row().articleNo() != null && !a.row().articleNo().isBlank())
                .toList();
        List<BordereauImportService.AnalysedLine> lot1 = articles.stream()
                .filter(a -> a.row().sectionPath().get(0).contains("Lot 1"))
                .toList();
        List<BordereauImportService.AnalysedLine> lot2 = articles.stream()
                .filter(a -> a.row().sectionPath().get(0).contains("Lot 2"))
                .toList();

        // ── Check 1: 2 sheets imported (Lot 1 + Lot 2), "Résultat attendu" skipped ──
        long sheetCount = analysed.stream().map(a -> a.row().sectionPath().get(0)).distinct().count();
        assertThat(sheetCount).isEqualTo(2);

        // ── Check 2: 38 article lines on Lot 1 ──
        assertThat(lot1).hasSize(38);

        // ── Check 3: 7 article lines on Lot 2 ──
        assertThat(lot2).hasSize(7);

        // ── Check 4: section titles not imported as articles ──
        List<BordereauImportService.AnalysedLine> titles = analysed.stream()
                .filter(a -> a.row().articleNo() == null || a.row().articleNo().isBlank())
                .toList();
        assertThat(titles).isNotEmpty();
        for (var t : titles) {
            assertThat(t.row().designation()).isNotBlank();
            assertThat(t.row().unitRaw()).isNull();
        }

        // ── Check 5: TOTAL/TVA rows NOT imported ──
        assertThat(analysed).noneMatch(a -> a.row().designation() != null
                && a.row().designation().toUpperCase().startsWith("TOTAL"));
        assertThat(analysed).noneMatch(a -> a.row().designation() != null
                && a.row().designation().toUpperCase().startsWith("TVA"));

        // ── Check 6: quantity "14 609,000" (text) → 14609.000 ──
        var row200 = findByDesignation(lot1, "Conduite PEHD PN10 DE 200");
        assertThat(row200.row().quantity()).isEqualByComparingTo(new BigDecimal("14609.000"));

        // ── Check 7: quantity "7,5" (text) → 7.500 ──
        var rowB1 = findByDesignation(lot1, "Béton B1 de propreté dosé à 250 kg");
        assertThat(rowB1.row().quantity()).isEqualByComparingTo(new BigDecimal("7.500"));

        // ── Check 8-9: unit normalisation ──
        assertThat(BordereauImportService.normaliseUnit("ml")).isEqualTo("ML");
        assertThat(BordereauImportService.normaliseUnit("mètre linéaire")).isEqualTo("ML");
        assertThat(BordereauImportService.normaliseUnit("unité")).isEqualTo("U");
        assertThat(BordereauImportService.normaliseUnit("m³")).isEqualTo("M3");
        assertThat(BordereauImportService.normaliseUnit("Kg")).isEqualTo("KG");
        assertThat(BordereauImportService.normaliseUnit("Forf")).isEqualTo("FORFAIT");

        // ── Check 10: unit "F" (43, line 24) → unit null + warning "unité inconnue" ──
        var rowCloture = findByDesignation(lot1, "Clôture en brique");
        assertThat(rowCloture.row().unitRaw()).isEqualTo("F");
        assertThat(rowCloture.normalisedUnit()).isNull();
        assertThat(rowCloture.warnings()).anyMatch(w -> w.contains("Unité inconnue"));

        // ── Check 11: empty quantity (44, line 15) → quantity 0 + warning ──
        var rowEchelle = findByDesignation(lot1, "Échelle acier galvanisé");
        assertThat(rowEchelle.row().quantity()).isNull();
        assertThat(rowEchelle.warnings()).anyMatch(w -> w.contains("Quantité manquante"));
        assertThat(rowEchelle.effectiveQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

        // ── Check 12: "à préciser" (44, line 16) → quantity 0 + warning "non numérique" ──
        var rowPorte = findByDesignation(lot1, "Porte ventilée en tôle");
        assertThat(rowPorte.row().quantityRaw()).isEqualTo("à préciser");
        assertThat(rowPorte.warnings()).anyMatch(w -> w.contains("pas un nombre valide"));
        assertThat(rowPorte.effectiveQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

        // ── Check 13: duplicate N° 24 in section 42 — both lines + warning on both ──
        List<BordereauImportService.AnalysedLine> dup24 = lot1.stream()
                .filter(a -> "24".equals(a.row().articleNo()))
                .filter(a -> a.row().sectionPath().stream().anyMatch(s -> s.contains("42.")))
                .toList();
        assertThat(dup24).hasSize(2);
        for (var d : dup24) {
            assertThat(d.warnings()).anyMatch(w -> w.contains("dupliqué"));
        }

        // ── Check 14: duplicate N° 11 on Lot 2 — 3 lines + warning on all ──
        List<BordereauImportService.AnalysedLine> dup11 = lot2.stream()
                .filter(a -> "11".equals(a.row().articleNo()))
                .toList();
        assertThat(dup11).hasSize(3);
        // With the "warn both" logic, all 3 lines have at least one duplicate warning.
        long dupWarnings = dup11.stream()
                .filter(a -> a.warnings().stream().anyMatch(w -> w.contains("dupliqué")))
                .count();
        assertThat(dupWarnings).isEqualTo(3); // all 3 have the warning

        // ── Check 15: montant = quantity × unit_price (recomputed) ──
        var row1 = findByDesignation(lot1, "Conduite PEHD PN10 DE 250");
        assertThat(row1.row().quantity()).isEqualByComparingTo(new BigDecimal("1404"));
        assertThat(row1.row().unitPrice()).isEqualByComparingTo(new BigDecimal("95.5"));
        assertThat(row1.montant()).isEqualByComparingTo(new BigDecimal("134082.0"));

        // ── Check 16: specs extracted ──
        Map<String, Object> specs1 = BordereauImportService.extractSpecs("Conduite PEHD PN10 DE 250");
        assertThat(specs1.get("de_mm")).isEqualTo(250);
        assertThat(specs1.get("pn_bar")).isEqualTo(10);
        assertThat(specs1.get("materiau")).isEqualTo("PEHD");

        Map<String, Object> specs2 = BordereauImportService.extractSpecs("Robinet vanne fonte DN 200 PN10");
        assertThat(specs2.get("dn_mm")).isEqualTo(200);
        assertThat(specs2.get("pn_bar")).isEqualTo(10);
        assertThat(specs2.get("materiau")).isEqualTo("FONTE");

        Map<String, Object> specs3 = BordereauImportService.extractSpecs("Béton B5 dosé à 350 kg/m³, armé");
        assertThat(specs3.get("dosage_kg_m3")).isEqualTo(350);

        Map<String, Object> specs4 = BordereauImportService.extractSpecs("Construction réservoir sur piliers 250 m³");
        assertThat(specs4.get("capacite_m3")).isEqualTo(250.0);

        // ── Check 17: work_nature ──
        assertThat(BordereauImportService.inferWorkNature("Conduite PEHD PN10 DE 250", List.of("Lot 1", "I - FOURNITURE")))
                .isEqualTo("FOURNITURE");
        assertThat(BordereauImportService.inferWorkNature("Pose et essai conduite PEHD PN10 DE 250", List.of("Lot 1", "II - POSE ET ESSAI")))
                .isEqualTo("POSE");
        assertThat(BordereauImportService.inferWorkNature("Fouille en tranchée", List.of("Lot 1", "III - TERRASSEMENT")))
                .isEqualTo("TERRASSEMENT");
        assertThat(BordereauImportService.inferWorkNature("Béton B5 dosé à 350 kg/m³", List.of("Lot 1", "IV - GENIE CIVIL", "42.")))
                .isEqualTo("GENIE_CIVIL");
        assertThat(BordereauImportService.inferWorkNature("Trappe d'entrée avec aération", List.of("Lot 1", "IV", "44.")))
                .isEqualTo("EQUIPEMENT");

        // ── Check 18: fourniture ↔ pose pairs (6 pairs) — test the service's own pairLines ──
        // The pairing was already done inside parseAndAnalyse. Check the results.
        List<BordereauImportService.AnalysedLine> fourniture = analysed.stream()
                .filter(a -> a.row().articleNo() != null && !a.row().articleNo().isBlank())
                .filter(a -> BordereauImportService.isFourniture(a.row().designation(), a.row().sectionPath()))
                .toList();
        // 6 PEHD conduite lines have de_mm + pn_bar + materiau PEHD
        List<BordereauImportService.AnalysedLine> pairedFourniture = fourniture.stream()
                .filter(a -> a.pairedLineId() != null)
                .toList();
        assertThat(pairedFourniture).hasSize(6);

        // The 4 robinetterie lines (DN, not DE) have no link.
        List<BordereauImportService.AnalysedLine> unpairedFourniture = fourniture.stream()
                .filter(a -> a.pairedLineId() == null)
                .toList();
        assertThat(unpairedFourniture).hasSize(4);

        // Each paired fourniture line matches a pose line with the same PN + DE.
        List<BordereauImportService.AnalysedLine> pose = analysed.stream()
                .filter(a -> a.row().articleNo() != null && !a.row().articleNo().isBlank())
                .filter(a -> BordereauImportService.isPose(a.row().designation(), a.row().sectionPath()))
                .toList();
        assertThat(pose).hasSize(6);
        for (var f : pairedFourniture) {
            Object fDe = f.specs().get("de_mm");
            Object fPn = f.specs().get("pn_bar");
            // Find the pose line with the same DE + PN.
            var matchingPose = pose.stream()
                    .filter(p -> String.valueOf(p.specs().get("de_mm")).equals(String.valueOf(fDe))
                            && String.valueOf(p.specs().get("pn_bar")).equals(String.valueOf(fPn)))
                    .findFirst();
            assertThat(matchingPose).isPresent();
            // The fourniture's pairedLineId should match the pose's pairedLineId
            // (both are set by pairLines to a synthetic UUID).
            assertThat(f.pairedLineId()).isNotNull();
            // Added (feature/bordereau-import): the link is a real line id, both ways.
            assertThat(f.pairedLineId()).isEqualTo(matchingPose.get().lineId());
            assertThat(matchingPose.get().pairedLineId()).isEqualTo(f.lineId());
        }
        // Added: pairLines returns each pair once.
        assertThat(BordereauImportService.pairLines(analysed)).hasSize(6);

        // ── Check 19: ouvrage suggestion ──
        // Sections I, II, III → Conduite
        var rowFourniture = findByDesignation(lot1, "Conduite PEHD PN10 DE 250");
        assertThat(rowFourniture.suggestion().ouvrageId()).isEqualTo(conduiteId);
        assertThat(rowFourniture.suggestion().ouvrageType()).isEqualTo("CONDUITE");

        // 42 → Réservoir 250 m³ sur piliers
        var rowReservoir = findByDesignation(lot1, "Terrassement pleine masse de la plate-forme");
        assertThat(rowReservoir.suggestion().ouvrageType()).isEqualTo("RESERVOIR");

        // 43 → Station de reprise
        var rowStation = findByDesignation(lot1, "Fouille en pleine masse");
        assertThat(rowStation.suggestion().ouvrageType()).isEqualTo("STATION_POMPAGE");

        // 44 → Réservoir 150 m³ semi-enterré (section "44. Construction réservoir semi-enterré 150 m³")
        var row44 = findByDesignation(lot1, "Béton B3");
        assertThat(row44.suggestion().ouvrageType()).isEqualTo("RESERVOIR");

        // Lot 2 → needs a Brise-charge ouvrage, otherwise left empty
        var lot2Row = lot2.get(0);
        // Lot 2 is "Brise-charge" → the section contains "brise" → suggestOuvrage returns RESERVOIR type
        // but no RESERVOIR ouvrage with "brise" in the name, so ouvrageId is null.
        // Actually the section "SOUS-LOT III : CONSTRUCTION DE DEUX BRISES CHARGE DE 8 m³ CHACUN"
        // contains "brise" → RESERVOIR type. Let's check.
        assertThat(lot2Row.suggestion().ouvrageType()).isEqualTo("RESERVOIR");

        // ── Check 20: total HT of Lot 1 recomputed = 4217510.300 ──
        BigDecimal total = BigDecimal.ZERO;
        for (var a : lot1) {
            total = total.add(a.effectiveQuantity().multiply(
                    a.row().unitPrice() != null ? a.row().unitPrice() : BigDecimal.ZERO));
        }
        assertThat(total).isEqualByComparingTo(new BigDecimal("4217510.300"));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private BordereauImportService.AnalysedLine findByDesignation(
            List<BordereauImportService.AnalysedLine> lines, String designationContains) {
        return lines.stream()
                .filter(a -> a.row().designation() != null && a.row().designation().contains(designationContains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Row not found: " + designationContains));
    }
}
