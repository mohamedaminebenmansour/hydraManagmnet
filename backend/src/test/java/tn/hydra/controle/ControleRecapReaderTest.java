package tn.hydra.controle;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ControleRecapReaderTest — CB-03 « Caisse à échéance », CB-04 « Récap des dépenses par projet ».
 * Builds a FAKE workbook in code (never a real file).
 */
class ControleRecapReaderTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 12, 31);

    // ── CB-03 ──────────────────────────────────────────────────────────────

    @Test
    void echeanceSheet_read() throws Exception {
        var file = read(fixture(true));

        var acct = file.accounts().stream().filter(a -> a.id().equals("caisse-echeance")).findFirst().orElseThrow();
        assertThat(acct.name()).isEqualTo("Caisse à échéance");
        assertThat(acct.kind()).isEqualTo("echeance");
        assertThat(acct.project()).isEmpty();

        List<ControleImportFile.Line> ech = file.lines().stream()
                .filter(l -> l.sheet().equals("Caisse à échéance")).toList();
        assertThat(ech).hasSize(2);                       // row 5 has no Débit
        // added after every other line, so existing ids and their order do not move
        assertThat(file.lines().subList(file.lines().size() - 2, file.lines().size())).isEqualTo(ech);

        var a = ech.get(0);
        assertThat(a.id()).isEqualTo(sha10("Caisse à échéance|2024-03-10|0.000|6496.875|location chargeuse juillet"));
        assertThat(a.row()).isEqualTo(4);
        assertThat(a.date()).isEqualTo("2024-03-10");
        assertThat(a.acct()).isEqualTo("caisse-echeance");
        assertThat(a.e()).isZero();
        assertThat(a.d()).isEqualTo(6496.875);
        assertThat(a.bon()).isEqualTo("91");
        assertThat(a.fct()).isEqualTo("N° 55349");
        assertThat(a.cat()).isEqualTo("ST Ahmed Houimli");
        assertThat(a.proj()).isEqualTo("Hajeb");
        assertThat(a.party()).isEqualTo("Ahmed Houimli");
        assertThat(a.poste()).isEqualTo("Sous-traitants");
        assertThat(a.mode()).isEqualTo("Espèces");
        assertThat(a.kind()).isEqualTo("sortie");
        assertThat(a.obs()).isEqualTo("500 espèce");
        assertThat(a.flags()).containsExactly("especes", "hors-compte");

        var b = ech.get(1);
        assertThat(b.row()).isEqualTo(6);
        assertThat(b.date()).isEmpty();
        assertThat(b.d()).isEqualTo(202.719);
        assertThat(b.cat()).isEqualTo("charge sur marche");
        assertThat(b.proj()).isEqualTo("Guttar Messioutta");
        assertThat(b.mode()).isEqualTo("Non renseigné");
        assertThat(b.flags()).containsExactly("sans-date");
    }

    // ── CB-04 ──────────────────────────────────────────────────────────────

    @Test
    void recapTable_read() throws Exception {
        var file = read(fixture(true));
        assertThat(file.recap())
                .extracting(ControleImportFile.Recap::proj, ControleImportFile.Recap::rubrique,
                        ControleImportFile.Recap::amount)
                .containsExactly(
                        tuple("Hajeb", "Abri Forage", 918.5),
                        tuple("Hajeb", "Station de Surpresseur", 27417.504),
                        tuple("Zaghdoud", "RSP", 174999.788),
                        tuple("SIGAT (frais généraux)", "Abri Forage", 12.0));
    }

    @Test
    void withoutTheNewSheets_nothingAdded() throws Exception {
        var file = read(fixture(false));
        assertThat(file.accounts()).noneMatch(a -> a.id().equals("caisse-echeance"));
        assertThat(file.lines()).noneMatch(l -> l.sheet().equals("Caisse à échéance"));
        assertThat(file.recap()).isEmpty();
    }

    // ── Fixture ────────────────────────────────────────────────────────────

    private static ControleImportFile.ImportFile read(byte[] xlsx) {
        return new ControleExcelReader().read(new ByteArrayInputStream(xlsx), "fake.xlsx",
                TODAY, LocalDateTime.of(2024, 12, 31, 10, 0));
    }

    private static String sha10(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h).substring(0, 10);
    }

    /** Smallest workbook the reader accepts, plus the two new sheets when {@code withNew}. */
    private static byte[] fixture(boolean withNew) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));

            wb.createSheet("Caisse Central");
            for (String sh : ControleExcelReader.SITE.keySet()) {
                row(wb.createSheet(sh), 3, 1, dateStyle, "Date", "Désignation", "Mode", "Crédit", "Débit");
            }
            for (String sh : ControleExcelReader.PARTNER.keySet()) {
                row(wb.createSheet(sh), 3, 1, dateStyle, "Date", "Désignation", "Mode", "Encaissement", "Décaissement");
            }
            for (String sh : ControleExcelReader.ST_SHEETS.keySet()) wb.createSheet(sh);

            if (withNew) {
                // « Caisse à échéance »: title row 1, TOTAUX row 2, header row 3 from column B
                Sheet e = wb.createSheet("Caisse à échéance");
                row(e, 1, 2, dateStyle, "Caisse à échéance");
                row(e, 2, 2, dateStyle, "TOTAUX", null, null, null, null, null, null, null, null, null, 6699.594);
                row(e, 3, 2, dateStyle, "N°", "Mois", "Date", "Bon N°", "Fct N° / SF", "Sous-traitant / Chef Projet",
                        "Destination", "Désignation", "Mode de paiement", "Compte", "Débit", "Observation");
                row(e, 4, 2, dateStyle, 1.0, null, LocalDate.of(2024, 3, 10), 91.0, "N° 55349", "ST Ahmed Houimli",
                        "hajeb", "Location chargeuse  juillet ", "Espèces", null, 6496.875, "500 espèce");
                row(e, 5, 2, dateStyle, 2.0, null, null, null, null, "ST Aymen Amri", "Soualem", "Sans montant");
                row(e, 6, 2, dateStyle, 3.0, null, null, 11.0, null, "charge sur marche", "Guttar Messioutta",
                        "Fac Bestoplast", null, null, 202.719);

                // « Récap T.d.B dépenses »: another table above, then the recap
                Sheet r = wb.createSheet("Récap T.d.B dépenses");
                row(r, 2, 2, dateStyle, "Autre tableau", 5.0);
                row(r, 5, 2, dateStyle, "Récap des dépenses par projet");
                row(r, 6, 2, dateStyle, "Destination     Projet", "Abri Forage", "Station de Surpresseur ", "RSP",
                        "Total par Projet", "Après le total");
                row(r, 7, 2, dateStyle, "Hajeb", 918.5, 27417.504, 0.0, 28336.004, 99.0);
                row(r, 8, 2, dateStyle, "zaghdoud", 0.0, null, 174999.78795999993, 174999.788, 99.0);
                row(r, 9, 2, dateStyle);                                   // empty name: skipped
                row(r, 10, 2, dateStyle, "SIGAT", 12.0, 0.0, 0.0, 12.0);
                row(r, 11, 2, dateStyle, "Total par Dést.", 930.5, 27417.504, 174999.788, 203347.792);
                row(r, 12, 2, dateStyle, "Alaa", 7.0);                     // after the total: ignored
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Writes values from {@code firstCol} on Excel row {@code r} (1-based). */
    private static void row(Sheet s, int r, int firstCol, CellStyle dateStyle, Object... values) {
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
}
