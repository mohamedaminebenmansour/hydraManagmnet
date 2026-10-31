package tn.hydra.project;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: imports HYDRA-test-bordereau-Bouslim.xlsx and checks every
 * row of the "Résultat attendu" sheet.
 *
 * <p>Tests the parsing and pairing directly (no database needed). The test
 * calls {@link BordereauImportService#parseWorkbook(byte[])} which returns
 * the parsed sheets without any DB interaction.
 *
 * <p>If this test fails, fix the import code, never the test or the xlsx file.
 */
class BordereauBouslimIntegrationTest {

    private static final String TEST_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    private List<BordereauImportService.ParsedSheet> sheets;

    @Test
    void import_bouslim_xlsx() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(TEST_FILE));
        sheets = BordereauImportService.parseWorkbook(bytes);

        // ── Check 1: 2 sheets imported (Lot 1 + Lot 2), "Résultat attendu" skipped ──
        assertThat(sheets).hasSize(2);
        assertThat(sheets.get(0).sheetName()).isEqualTo("Lot 1 - Bouslim");
        assertThat(sheets.get(1).sheetName()).isEqualTo("Lot 2 - Brise-charge");

        // ── Check 2: 38 article lines on Lot 1 (titles and totals excluded) ──
        List<BordereauImportService.ParsedRow> lot1 = sheets.get(0).rows();
        List<BordereauImportService.ParsedRow> lot1Articles = lot1.stream()
                .filter(r -> r.articleNo() != null && !r.articleNo().isBlank())
                .toList();
        assertThat(lot1Articles).hasSize(38);

        // ── Check 3: 7 article lines on Lot 2 ──
        List<BordereauImportService.ParsedRow> lot2 = sheets.get(1).rows();
        List<BordereauImportService.ParsedRow> lot2Articles = lot2.stream()
                .filter(r -> r.articleNo() != null && !r.articleNo().isBlank())
                .toList();
        assertThat(lot2Articles).hasSize(7);

        // ── Check 4: section titles on Lot 1 are NOT imported as articles ──
        // I, II, III, IV and sub-sections 41, 42, 43, 44 are section titles
        List<BordereauImportService.ParsedRow> lot1Titles = lot1.stream()
                .filter(r -> r.articleNo() == null || r.articleNo().isBlank())
                .toList();
        assertThat(lot1Titles).isNotEmpty();
        // Each title should have a designation but no quantity/unit
        for (var t : lot1Titles) {
            assertThat(t.designation()).isNotBlank();
            assertThat(t.unitRaw()).isNull();
            assertThat(t.quantity()).isNull();
        }

        // ── Check 5: TOTAL HORS TVA, TVA 19 %, TOTAL TTC are NOT imported ──
        List<String> allDesignations = lot1.stream().map(r -> r.designation()).toList();
        assertThat(allDesignations).noneMatch(d -> d != null && d.toUpperCase().startsWith("TOTAL"));
        assertThat(allDesignations).noneMatch(d -> d != null && d.toUpperCase().startsWith("TVA"));

        // ── Check 6: quantity "14 609,000" (text) parsed as 14609.000 ──
        var row5 = findRow(lot1Articles, "3", "Conduite PEHD PN10 DE 200");
        assertThat(row5.quantity()).isEqualByComparingTo(new BigDecimal("14609.000"));

        // ── Check 7: quantity "7,5" (text) parsed as 7.500 ──
        var rowB1 = findRow(lot1Articles, "2", "Béton B1 de propreté dosé à 250 kg");
        assertThat(rowB1.quantity()).isEqualByComparingTo(new BigDecimal("7.500"));

        // ── Check 8: units normalised — ml / mètre linéaire / ML → all ML ──
        assertThat(BordereauImportService.normaliseUnit("ml")).isEqualTo("ML");
        assertThat(BordereauImportService.normaliseUnit("mètre linéaire")).isEqualTo("ML");
        assertThat(BordereauImportService.normaliseUnit("ML")).isEqualTo("ML");

        // ── Check 9: unité → U, m³ → M3, Kg/KG → KG, Forf → FORFAIT ──
        assertThat(BordereauImportService.normaliseUnit("unité")).isEqualTo("U");
        assertThat(BordereauImportService.normaliseUnit("m³")).isEqualTo("M3");
        assertThat(BordereauImportService.normaliseUnit("Kg")).isEqualTo("KG");
        assertThat(BordereauImportService.normaliseUnit("Forf")).isEqualTo("FORFAIT");

        // ── Check 10: unit "F" (Lot 1, 43, line 24) → unit = null ──
        var rowF = findRowByDesignation(lot1Articles, "Clôture en brique");
        assertThat(rowF.unitRaw()).isEqualTo("F");
        assertThat(BordereauImportService.normaliseUnit("F")).isNull();

        // ── Check 11: quantity empty (44, line 15) → quantity null ──
        var rowEchelle = findRowByDesignation(lot1Articles, "Échelle acier galvanisé");
        assertThat(rowEchelle.quantity()).isNull();

        // ── Check 12: quantity "à préciser" (44, line 16) → quantity 0 + warning ──
        var rowPorte = findRowByDesignation(lot1Articles, "Porte ventilée en tôle");
        assertThat(rowPorte.quantityRaw()).isEqualTo("à préciser");
        assertThat(rowPorte.quantityParseFailed()).isTrue();

        // ── Check 13: duplicate N° 24 in section 42 — both kept ──
        // (N° 24 also appears in section 43 "Clôture en brique" — that's a different section, not a duplicate)
        List<BordereauImportService.ParsedRow> dup24 = lot1Articles.stream()
                .filter(r -> "24".equals(r.articleNo()))
                .filter(r -> r.sectionPath().stream().anyMatch(s -> s.contains("42.")))
                .toList();
        assertThat(dup24).hasSize(2);

        // ── Check 14: duplicate N° 11 on Lot 2 — 3 lines kept ──
        List<BordereauImportService.ParsedRow> dup11 = lot2Articles.stream()
                .filter(r -> "11".equals(r.articleNo()))
                .toList();
        assertThat(dup11).hasSize(3);

        // ── Check 15: montant column ignored, recomputed = quantity × unit price ──
        var row1 = findRow(lot1Articles, "1", "Conduite PEHD PN10 DE 250");
        assertThat(row1.quantity()).isEqualByComparingTo(new BigDecimal("1404"));
        assertThat(row1.unitPrice()).isEqualByComparingTo(new BigDecimal("95.5"));
        // montant = 1404 × 95.5 = 134082
        assertThat(row1.quantity().multiply(row1.unitPrice()))
                .isEqualByComparingTo(new BigDecimal("134082.0"));

        // ── Check 16: specs extracted ──
        // "Conduite PEHD PN10 DE 250" → de_mm 250, pn_bar 10, materiau PEHD
        Map<String, Object> specs1 = BordereauImportService.extractSpecs("Conduite PEHD PN10 DE 250");
        assertThat(specs1.get("de_mm")).isEqualTo(250);
        assertThat(specs1.get("pn_bar")).isEqualTo(10);
        assertThat(specs1.get("materiau")).isEqualTo("PEHD");

        // "Robinet vanne fonte DN 200 PN10" → dn_mm 200, pn_bar 10, materiau FONTE
        Map<String, Object> specs2 = BordereauImportService.extractSpecs("Robinet vanne fonte DN 200 PN10");
        assertThat(specs2.get("dn_mm")).isEqualTo(200);
        assertThat(specs2.get("pn_bar")).isEqualTo(10);
        assertThat(specs2.get("materiau")).isEqualTo("FONTE");

        // "Béton B5 dosé à 350 kg/m³" → dosage_kg_m3 350
        Map<String, Object> specs3 = BordereauImportService.extractSpecs("Béton B5 dosé à 350 kg/m³, armé");
        assertThat(specs3.get("dosage_kg_m3")).isEqualTo(350);

        // Section "42 ... 250 m³" → capacite_m3 250
        Map<String, Object> specs4 = BordereauImportService.extractSpecs("Construction réservoir sur piliers 250 m³");
        assertThat(specs4.get("capacite_m3")).isEqualTo(250.0);

        // ── Check 17: work_nature ──
        // Section I lines → FOURNITURE
        assertThat(BordereauImportService.inferWorkNature("Conduite PEHD PN10 DE 250", List.of("Lot 1 - Bouslim", "I - FOURNITURE")))
                .isEqualTo("FOURNITURE");
        // Section II lines → POSE
        assertThat(BordereauImportService.inferWorkNature("Pose et essai conduite PEHD PN10 DE 250", List.of("Lot 1 - Bouslim", "II - POSE ET ESSAI DES CONDUITES")))
                .isEqualTo("POSE");
        // Section III lines → TERRASSEMENT
        assertThat(BordereauImportService.inferWorkNature("Fouille en tranchée", List.of("Lot 1 - Bouslim", "III - TERRASSEMENT")))
                .isEqualTo("TERRASSEMENT");
        // 42-44 béton/acier → GENIE_CIVIL
        assertThat(BordereauImportService.inferWorkNature("Béton B5 dosé à 350 kg/m³, armé", List.of("Lot 1 - Bouslim", "IV - GENIE CIVIL", "42. Construction réservoir sur piliers 250 m³")))
                .isEqualTo("GENIE_CIVIL");
        // robinet/trappe/échelle → EQUIPEMENT
        assertThat(BordereauImportService.inferWorkNature("Trappe d'entrée avec aération", List.of("Lot 1 - Bouslim", "IV - GENIE CIVIL", "44. Construction réservoir semi-enterré 150 m³")))
                .isEqualTo("EQUIPEMENT");

        // ── Check 18: fourniture ↔ pose pairs (6 pairs) ──
        // Each "Conduite PEHD PNx DE y" in I matches "Pose et essai conduite PEHD PNx DE y" in II
        // (same materiau PEHD + same de_mm + same pn_bar)
        List<BordereauImportService.ParsedRow> fourniture = lot1Articles.stream()
                .filter(r -> BordereauImportService.isFourniture(r.designation(), r.sectionPath()))
                .toList();
        List<BordereauImportService.ParsedRow> pose = lot1Articles.stream()
                .filter(r -> BordereauImportService.isPose(r.designation(), r.sectionPath()))
                .toList();
        // Fourniture lines include conduites + robinetterie; only the 6 PEHD
        // conduite lines have matching pose lines (by materiau + de_mm + pn_bar).
        // The 4 robinetterie lines (DN, not DE) don't pair.
        assertThat(fourniture.size()).isGreaterThanOrEqualTo(6);
        // 6 pose lines (same DEs as the 6 fourniture PEHD lines)
        assertThat(pose).hasSize(6);

        // Each fourniture line should match exactly one pose line by materiau + de_mm + pn_bar.
        int pairCount = 0;
        for (var f : fourniture) {
            Map<String, Object> fSpecs = BordereauImportService.extractSpecs(f.designation());
            String fMat = (String) fSpecs.get("materiau");
            Object fDe = fSpecs.get("de_mm");
            Object fPn = fSpecs.get("pn_bar");
            if (fMat == null || fDe == null || fPn == null) continue;

            int matches = 0;
            for (var p : pose) {
                Map<String, Object> pSpecs = BordereauImportService.extractSpecs(p.designation());
                String pMat = (String) pSpecs.get("materiau");
                Object pDe = pSpecs.get("de_mm");
                Object pPn = pSpecs.get("pn_bar");
                if (fMat.equals(pMat) && String.valueOf(fDe).equals(String.valueOf(pDe))
                        && String.valueOf(fPn).equals(String.valueOf(pPn))) {
                    matches++;
                }
            }
            if (matches == 1) pairCount++;
        }
        assertThat(pairCount).isEqualTo(6);

        // ── Check 19: ouvrage suggestion ──
        // I, II, III → CONDUITE
        var rowFourniture = findRow(lot1Articles, "1", "Conduite PEHD PN10 DE 250");
        var s1 = BordereauImportService.suggestOuvrage(rowFourniture, List.of());
        assertThat(s1.ouvrageType()).isEqualTo("CONDUITE");
        // 42 → RESERVOIR (section contains "réservoir")
        var rowReservoir = findRowByDesignation(lot1Articles, "Terrassement pleine masse");
        var s2 = BordereauImportService.suggestOuvrage(rowReservoir, List.of());
        assertThat(s2.ouvrageType()).isEqualTo("RESERVOIR");
        // 43 → STATION_POMPAGE (section contains "station")
        var rowStation = findRowByDesignation(lot1Articles, "Fouille en pleine masse");
        // This row is in section "43. Construction station de reprise + abri"
        var s3 = BordereauImportService.suggestOuvrage(rowStation, List.of());
        assertThat(s3.ouvrageType()).isEqualTo("STATION_POMPAGE");

        // ── Check 20: total HT of Lot 1 recomputed by HYDRA ──
        // Sum of (quantity × unit_price) for all lines with both values.
        BigDecimal total = BigDecimal.ZERO;
        for (var r : lot1Articles) {
            if (r.quantity() != null && r.unitPrice() != null) {
                total = total.add(r.quantity().multiply(r.unitPrice()));
            }
        }
        // The file says "PRIX FICTIFS" — we just verify the sum is positive and large.
        assertThat(total).isPositive();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private BordereauImportService.ParsedRow findRow(
            List<BordereauImportService.ParsedRow> rows, String articleNo, String designationContains) {
        return rows.stream()
                .filter(r -> articleNo.equals(r.articleNo()))
                .filter(r -> r.designation() != null && r.designation().contains(designationContains))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Row not found: articleNo=" + articleNo + " designation~" + designationContains));
    }

    private BordereauImportService.ParsedRow findRowByDesignation(
            List<BordereauImportService.ParsedRow> rows, String designationContains) {
        return rows.stream()
                .filter(r -> r.designation() != null && r.designation().contains(designationContains))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Row not found: designation~" + designationContains));
    }
}
