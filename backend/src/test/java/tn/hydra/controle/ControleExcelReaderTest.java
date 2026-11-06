package tn.hydra.controle;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.hydra.common.BusinessRuleException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ControleExcelReaderTest — CX-01..CX-08.
 *
 * <p>Each test builds a small FAKE « Caisse des projets » workbook in code with
 * POI (fictitious names and amounts), writes it to bytes and reads it back with
 * {@link ControleExcelReader}, like an upload. The real Excel file is never in
 * the repository. Expected values follow the rules of tools/controle/convert.py;
 * expected ids are recomputed here from the sha1 formula on literal strings.
 */
class ControleExcelReaderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 9, 30, 41);

    private final ControleExcelReader reader = new ControleExcelReader();
    private ControleImportFile.ImportFile file;

    @BeforeEach
    void readFixture() throws Exception {
        file = read(fixture(Set.of()));
    }

    // ── CX-01 — counts, rows, accounts ─────────────────────────────────────
    @Test
    void cx01_countsRowsAndAccounts() {
        // Caisse Central 9 (row 6 is empty), Caisse Chantier Hajeb 2, STE NGNET Hajeb 2
        assertThat(file.lines()).hasSize(13);
        assertThat(file.stLines()).hasSize(3);
        assertThat(file.meta().lines()).isEqualTo(13);
        assertThat(file.meta().stLines()).isEqualTo(3);
        assertThat(file.meta().source()).isEqualTo("Caisse des projets.xlsx");
        assertThat(file.meta().generated()).isEqualTo("2026-11-06T09:30");

        assertThat(file.lines().stream().filter(l -> l.sheet().equals("Caisse Central")).map(l -> l.row()))
                .containsExactly(4, 5, 7, 8, 9, 10, 11, 12, 606);
        assertThat(file.lines().stream().filter(l -> l.sheet().equals("STE NGNET Hajeb")).map(l -> l.row()))
                .containsExactly(3, 4);

        assertThat(file.accounts()).hasSize(12);
        assertThat(file.accounts().get(1).note()).contains("OMAR");
        assertThat(file.stAccounts()).hasSize(29);
        assertThat(file.stAccounts().get(0).id()).isEqualTo("st-st-ismail-hajeb");
        var aqua = file.stAccounts().get(28);
        assertThat(aqua.id()).isEqualTo("st-st-aqua-r-seau");
        assertThat(aqua.supplier()).isTrue();
        assertThat(file.stLines()).allMatch(s -> s.st().equals("st-st-ismail-hajeb"));
    }

    // ── CX-02 — ids are sha1(sheet|date|e|d|designation)[:10], -1 for duplicates
    @Test
    void cx02_idsMatchConvertPyFormula() {
        String gasoil = sha10("Caisse Central|2024-03-05|0.000|1250.500|achat gasoil");
        assertThat(line("Caisse Central", 5).id()).isEqualTo(gasoil);
        assertThat(line("Caisse Central", 7).id()).isEqualTo(gasoil + "-1");
        assertThat(line("Caisse Central", 4).id())
                .isEqualTo(sha10("Caisse Central|2024-03-01|100000.000|0.000|solde de départ"));
        assertThat(line("Caisse Central", 9).id())
                .isEqualTo(sha10("Caisse Central|2024-03-08|0.000|5000.001|location pelle"));
        assertThat(line("STE NGNET Hajeb", 4).id())
                .isEqualTo(sha10("STE NGNET Hajeb|2024-03-16|0.000|7000.000|achat conduites"));
        assertThat(stLine(7).id()).isEqualTo(sha10("ST Ismail Hajeb|2024-04-01|0.000|1200.000|paiement"));
    }

    // ── CX-03 — formula cached value, dates, numbers as text, whitespace ───
    @Test
    void cx03_cellValuesReadLikeOpenpyxl() {
        var internal = line("Caisse Central", 606);
        assertThat(internal.d()).isEqualTo(3000.0);   // =B606*2, cached value
        assertThat(internal.id()).isEqualTo(sha10("Caisse Central|2024-05-02|0.000|3000.000|virement interne uib"));

        var gasoil = line("Caisse Central", 5);
        assertThat(gasoil.date()).isEqualTo("2024-03-05");
        assertThat(gasoil.bon()).isEqualTo("123");     // numeric cell 123.0
        assertThat(gasoil.fct()).isEqualTo("F-01");
        assertThat(gasoil.desig()).isEqualTo("Achat gasoil"); // no-break space + double space + trailing space
        assertThat(gasoil.mode()).isEqualTo("Espèces");
        assertThat(gasoil.acct()).isEqualTo("caisse-sigat");
        assertThat(gasoil.poste()).isEqualTo("Carburant");
        assertThat(gasoil.kind()).isEqualTo("sortie");

        var start = line("Caisse Central", 4);
        assertThat(start.kind()).isEqualTo("entree");
        assertThat(start.poste()).isEqualTo("Solde de départ");
        assertThat(start.e()).isEqualTo(100000.0);
    }

    // ── CX-04 — NGNET box: Bon N°, Facture N°, Observation in another order
    @Test
    void cx04_partnerBoxReadsBonFactureObservation() {
        var buy = line("STE NGNET Hajeb", 4);
        assertThat(buy.bon()).isEqualTo("B-77");
        assertThat(buy.fct()).isEqualTo("F-2024-9");
        assertThat(buy.obs()).isEqualTo("livré");
        assertThat(buy.mode()).isEqualTo("Chèque");
        assertThat(buy.kind()).isEqualTo("partenaire");
        assertThat(buy.acct()).isEqualTo("ngnet-hajeb");
        assertThat(buy.proj()).isEqualTo("Hajeb");
        assertThat(buy.d()).isEqualTo(7000.0);

        var funding = line("STE NGNET Hajeb", 3);
        assertThat(funding.poste()).isEqualTo("Financement NGNET");
        assertThat(funding.kind()).isEqualTo("partenaire");
        assertThat(funding.obs()).isEqualTo("reçu banque");
    }

    // ── CX-05 — transfers and « Projet X » destination ─────────────────────
    @Test
    void cx05_transfers() {
        var internal = line("Caisse Central", 606);
        assertThat(internal.kind()).isEqualTo("transfert");
        assertThat(internal.flags()).contains("interne");

        var feed = line("Caisse Central", 10);
        assertThat(feed.kind()).isEqualTo("transfert");
        assertThat(feed.poste()).isEqualTo("Alimentation caisse chantier");
        assertThat(feed.flags()).doesNotContain("interne");

        var received = line("Caisse Chantier Hajeb", 4);
        assertThat(received.kind()).isEqualTo("transfert");
        assertThat(received.poste()).isEqualTo("Alimentation reçue");
        assertThat(received.acct()).isEqualTo("chantier-hajeb");

        var otherProject = line("Caisse Chantier Hajeb", 5);
        assertThat(otherProject.kind()).isEqualTo("sortie");
        assertThat(otherProject.proj()).isEqualTo("Hammed");
        assertThat(otherProject.poste()).isEqualTo("Dépensé pour un autre projet");
    }

    // ── CX-06 — espèces flag: debit > 5 000 strictly, not on transfers ────
    @Test
    void cx06_cashFlagStrictlyAbove5000() {
        assertThat(line("Caisse Central", 8).flags()).doesNotContain("especes");  // 5 000,000
        assertThat(line("Caisse Central", 9).flags()).contains("especes");        // 5 000,001
        assertThat(line("Caisse Central", 10).flags()).doesNotContain("especes"); // 9 000, transfert
        assertThat(file.lines().stream().filter(l -> l.flags().contains("especes")).count()).isEqualTo(1);
        // duplicate of row 5 is flagged, the first one is not
        assertThat(line("Caisse Central", 7).flags()).contains("doublon");
        assertThat(line("Caisse Central", 5).flags()).doesNotContain("doublon");
    }

    // ── CX-07 — ST payment linked to a caisse line within ±10 days ─────────
    @Test
    void cx07_stLink() {
        var paid = stLine(7);          // 1 200 on 2024-04-01
        var cash = line("Caisse Central", 11); // 1 200 on 2024-04-11 (10 days)
        assertThat(paid.link()).isEqualTo(cash.id());
        assertThat(cash.stLink()).isEqualTo(paid.id());
        assertThat(cash.party()).isEqualTo("Ismaiil Mansouri");
        assertThat(cash.poste()).isEqualTo("Sous-traitants");
        assertThat(cash.flags()).doesNotContain("hors-compte");
        assertThat(paid.flags()).isEmpty();

        var notFound = stLine(8);      // 800 on 2024-04-08, cash line on 2024-04-20 (12 days)
        assertThat(notFound.link()).isEmpty();
        assertThat(notFound.flags()).containsExactly("non-retrouve");
        var outside = line("Caisse Central", 12);
        assertThat(outside.stLink()).isNull();
        assertThat(outside.flags()).contains("hors-compte");

        var works = stLine(6);
        assertThat(works.travaux()).isEqualTo(5000.0);
        assertThat(works.paye()).isEqualTo(0.0);
        assertThat(works.flags()).isEmpty();
    }

    // ── CX-08 — missing sheet / unreadable file → IMPORT_FORMAT ────────────
    @Test
    void cx08_missingSheetAndBadFile() throws Exception {
        byte[] missing = fixture(Set.of("Caisse Chantier Zaghdoud"));
        assertThatThrownBy(() -> read(missing))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Caisse Chantier Zaghdoud")
                .extracting(e -> ((BusinessRuleException) e).getCode()).isEqualTo("IMPORT_FORMAT");

        byte[] junk = "ceci n'est pas un classeur".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> read(junk))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ne peut pas être lu")
                .extracting(e -> ((BusinessRuleException) e).getCode()).isEqualTo("IMPORT_FORMAT");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private ControleImportFile.ImportFile read(byte[] xlsx) {
        return reader.read(new ByteArrayInputStream(xlsx), "Caisse des projets.xlsx", TODAY, NOW);
    }

    private ControleImportFile.Line line(String sheet, int row) {
        return file.lines().stream().filter(l -> l.sheet().equals(sheet) && l.row() == row)
                .findFirst().orElseThrow(() -> new AssertionError("no line " + sheet + " row " + row));
    }

    private ControleImportFile.StLine stLine(int row) {
        return file.stLines().stream().filter(s -> s.row() == row).findFirst().orElseThrow();
    }

    private static String sha10(String base) throws RuntimeException {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 10);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The FAKE workbook. {@code skip} = sheet names to leave out. */
    private static byte[] fixture(Set<String> skip) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            W w = new W(wb, dateStyle);

            // Caisse Central — fixed columns: 3 date, 4 bon, 5 fct, 6 cat, 7 dest, 8 desig,
            // 9 mode, 10 compte, 11 crédit, 12 débit, 14 observation. Data from row 4.
            Sheet cc = wb.createSheet("Caisse Central");
            w.row(cc, 3, 3, "Date", "Bon", "Facture", "Catégorie", "Destination", "Désignation",
                    "Mode", "Compte", "Crédit", "Débit");
            w.cc(cc, 4, "2024-03-01", null, null, "Alimentation", "Hajeb", "Solde de départ", "Virement", "UIB SIGAT", 100000.0, null);
            w.cc(cc, 5, "2024-03-05", 123.0, "F-01", "Carburant", "Hajeb", "Achat  gasoil ", "Espèces", "Caisse SIGAT", null, 1250.5);
            // row 6 left empty
            w.cc(cc, 7, "2024-03-05", 123.0, "F-01", "Carburant", "Hajeb", "Achat  gasoil ", "Espèces", "Caisse SIGAT", null, 1250.5);
            w.cc(cc, 8, "2024-03-07", null, null, "Location", "Hajeb", "Location camion", "Espèces", "Caisse SIGAT", null, 5000.0);
            w.cc(cc, 9, "2024-03-08", null, null, "Location", "Hajeb", "Location pelle", "Espèces", "Caisse SIGAT", null, 5000.001);
            w.cc(cc, 10, "2024-03-09", null, null, "Caisse chantier", "Hajeb", "Alimentation caisse Hajeb", "Espèces", "Caisse SIGAT", null, 9000.0);
            w.cc(cc, 11, "2024-04-11", null, null, "ST Ismail", "Hajeb", "Paiement Ismail", "Chèque", "UIB SIGAT", null, 1200.0);
            w.cc(cc, 12, "2024-04-20", null, null, "ST Ismail", "Hajeb", "Paiement Ismail bis", "Chèque", "UIB SIGAT", null, 800.0);
            w.cc(cc, 606, "2024-05-02", null, null, "Virement", "", "Virement interne UIB", "Virement", "UIB SIGAT", null, null);
            cc.getRow(605).createCell(1).setCellValue(1500.0);                 // B606
            cc.getRow(605).createCell(11).setCellFormula("B606*2");            // L606 = débit
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();     // cache the result, like Excel

            // Caisses chantier: header on row 3
            for (String sh : ControleExcelReader.SITE.keySet()) {
                if (skip.contains(sh)) continue;
                Sheet s = wb.createSheet(sh);
                w.row(s, 3, 1, "Date", "Désignation", "Mode", "Crédit", "Débit", "Destination", "Bon N°", "Observation");
                if (sh.equals("Caisse Chantier Hajeb")) {
                    w.row(s, 4, 1, LocalDate.of(2024, 3, 10), "Alimentation caisse", "Virement", 9000.0);
                    w.row(s, 5, 1, LocalDate.of(2024, 3, 12), "Achat ciment", "Espèces", null, 300.0, "Projet Hammed");
                }
            }

            // Caisses partenaires: header on row 2, columns in another order
            for (String sh : ControleExcelReader.PARTNER.keySet()) {
                if (skip.contains(sh)) continue;
                Sheet s = wb.createSheet(sh);
                w.row(s, 2, 1, "Observation", "Facture N°", "Date", "Désignation", "Bon N°",
                        "Encaissement", "Décaissement", "Mode");
                if (sh.equals("STE NGNET Hajeb")) {
                    w.row(s, 3, 1, "reçu banque", null, LocalDate.of(2024, 3, 15), "Financement NGNET", null, 20000.0, null, "Virement");
                    w.row(s, 4, 1, "livré", "F-2024-9", LocalDate.of(2024, 3, 16), "Achat conduites", "B-77", null, 7000.0, "Chèque");
                }
            }

            // Comptes sous-traitants: only « ST Ismail Hajeb » has a header and lines
            for (String sh : ControleExcelReader.ST_SHEETS.keySet()) {
                if (skip.contains(sh)) continue;
                Sheet s = wb.createSheet(sh);
                if (sh.equals("ST Ismail Hajeb")) {
                    w.row(s, 5, 1, "Date", "Désignation", "Crédit", "Débit", "Mode");
                    w.row(s, 6, 1, LocalDate.of(2024, 3, 20), "Travaux tranche 1", 5000.0, null, null);
                    w.row(s, 7, 1, LocalDate.of(2024, 4, 1), "Paiement", null, 1200.0, "Chèque");
                    w.row(s, 8, 1, LocalDate.of(2024, 4, 8), "Paiement 2", null, 800.0, "Chèque");
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Small cell writer: Excel row/column numbers are 1-based, like convert.py. */
    private record W(XSSFWorkbook wb, CellStyle dateStyle) {
        void row(Sheet s, int r, int firstCol, Object... values) {
            Row row = s.getRow(r - 1) != null ? s.getRow(r - 1) : s.createRow(r - 1);
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v == null) continue;
                Cell c = row.createCell(firstCol - 1 + i);
                if (v instanceof String str) c.setCellValue(str);
                else if (v instanceof Double d) c.setCellValue(d);
                else if (v instanceof LocalDate d) { c.setCellValue(d); c.setCellStyle(dateStyle); }
            }
        }

        /** One Caisse Central row (columns 3..12). */
        void cc(Sheet s, int r, String date, Double bon, String fct, String cat, String dest, String desig,
                String mode, String compte, Double credit, Double debit) {
            row(s, r, 3, LocalDate.parse(date), bon, fct, cat, dest, desig, mode, compte, credit, debit);
        }
    }
}
