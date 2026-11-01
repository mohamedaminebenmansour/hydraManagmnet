package tn.hydra.project;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ouvrage suggestion by capacity and one-fourniture-per-pose pairing
 * (feature/bordereau-ouvrages). Calls the service's own static methods; no database.
 */
class BordereauOuvrageMatchingTest {

    private static final String TEST_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    @Test
    void reservoirs_matchedByCapacity_briseChargeWithoutOuvrage_isNull() throws Exception {
        UUID conduiteId = UUID.randomUUID();
        UUID reservoir250Id = UUID.randomUUID();
        UUID reservoir150Id = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        List<Map<String, Object>> ouvrages = List.of(
                Map.of("id", conduiteId, "type", "CONDUITE", "name", "Conduite"),
                // DB shape: characteristics is the jsonb text.
                Map.of("id", reservoir250Id, "type", "RESERVOIR", "name", "Réservoir sur piliers",
                        "characteristics", "{\"capacite_m3\": 250, \"sous_type\": \"SUR_PILIERS\"}"),
                Map.of("id", reservoir150Id, "type", "RESERVOIR", "name", "Réservoir semi-enterré",
                        "characteristics", Map.of("capacite_m3", 150, "sous_type", "SEMI_ENTERRE")),
                Map.of("id", stationId, "type", "STATION_POMPAGE", "name", "Station de reprise"));

        List<BordereauImportService.AnalysedLine> analysed =
                BordereauImportService.parseAndAnalyse(Files.readAllBytes(Path.of(TEST_FILE)), ouvrages);
        List<BordereauImportService.AnalysedLine> articles = analysed.stream()
                .filter(a -> a.row().articleNo() != null && !a.row().articleNo().isBlank())
                .toList();

        List<BordereauImportService.AnalysedLine> s42 = inSection(articles, "42");
        List<BordereauImportService.AnalysedLine> s44 = inSection(articles, "44");
        List<BordereauImportService.AnalysedLine> lot2 = articles.stream()
                .filter(a -> a.row().sectionPath().get(0).contains("Lot 2")).toList();

        assertThat(s42).isNotEmpty();
        assertThat(s42).allSatisfy(a -> assertThat(a.suggestion().ouvrageId()).isEqualTo(reservoir250Id));
        assertThat(s44).isNotEmpty();
        assertThat(s44).allSatisfy(a -> assertThat(a.suggestion().ouvrageId()).isEqualTo(reservoir150Id));
        assertThat(lot2).isNotEmpty();
        assertThat(lot2).allSatisfy(a -> assertThat(a.suggestion().ouvrageId()).isNull());
    }

    @Test
    void secondFournitureOnAnAlreadyPairedPose_isNotPaired_andGetsTheWarning() {
        List<BordereauImportService.AnalysedLine> lines = new ArrayList<>(List.of(
                line("I", "1", "Fourniture conduite PEHD PN10 DE 110"),
                line("I", "2", "Fourniture conduite PEHD PN10 DE 110"),
                line("II", "7", "Pose et essai conduite PEHD PN10 DE 110")));

        assertThat(BordereauImportService.pairLines(lines)).hasSize(1);
        BordereauImportService.applyPairing(lines);

        assertThat(lines.get(0).pairedLineId()).isEqualTo(lines.get(2).lineId());
        assertThat(lines.get(2).pairedLineId()).isEqualTo(lines.get(0).lineId());
        assertThat(lines.get(1).pairedLineId()).isNull();
        assertThat(lines.get(1).warnings())
                .contains("Plusieurs fournitures correspondent à la même pose : « 7 ».");
    }

    @Test
    void fournitureMatchingTwoPoses_isNotPaired_andGetsTheWarning() {
        List<BordereauImportService.AnalysedLine> lines = new ArrayList<>(List.of(
                line("I", "1", "Fourniture conduite PEHD PN10 DE 110"),
                line("II", "7", "Pose et essai conduite PEHD PN10 DE 110"),
                line("II", "8", "Pose et essai conduite PEHD PN10 DE 110")));

        BordereauImportService.applyPairing(lines);

        assertThat(lines).allSatisfy(l -> assertThat(l.pairedLineId()).isNull());
        assertThat(lines.get(0).warnings()).contains("Plusieurs poses possibles, aucun lien créé.");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static List<BordereauImportService.AnalysedLine> inSection(
            List<BordereauImportService.AnalysedLine> lines, String sectionNo) {
        return lines.stream()
                .filter(a -> a.row().sectionPath().stream().anyMatch(s -> s.trim().startsWith(sectionNo)))
                .toList();
    }

    private static BordereauImportService.AnalysedLine line(String section, String articleNo, String designation) {
        BordereauImportService.ParsedRow row = new BordereauImportService.ParsedRow(
                List.of("Lot test", section), articleNo, designation, "ml", "100",
                new BigDecimal("100"), new BigDecimal("10"), false);
        return new BordereauImportService.AnalysedLine(row, "ML", BordereauImportService.extractSpecs(designation),
                new BordereauImportService.OuvrageSuggestion(null, null), null, new ArrayList<>(),
                new BigDecimal("1000"), null, 0, UUID.randomUUID());
    }
}
