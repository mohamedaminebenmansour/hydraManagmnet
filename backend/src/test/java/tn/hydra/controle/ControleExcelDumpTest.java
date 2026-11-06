package tn.hydra.controle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ControleExcelDumpTest — NOT a CI test: a local tool to compare the Java reader
 * with tools/controle/convert.py on the real Excel file. Skipped unless
 * {@code -Dcontrole.xlsx} is given:
 *
 * <pre>
 * cd backend
 * ./mvnw -q test -Dtest=ControleExcelDumpTest -Dcontrole.xlsx="C:/chemin/Caisse des projets.xlsx" -Dcontrole.out="C:/chemin/java.json"
 * python3 tools/controle/compare.py py.json java.json
 * </pre>
 *
 * The output files hold real company data: never commit them.
 */
class ControleExcelDumpTest {

    @Test
    @EnabledIfSystemProperty(named = "controle.xlsx", matches = ".+")
    void dump() throws Exception {
        Path in = Path.of(System.getProperty("controle.xlsx"));
        Path out = Path.of(System.getProperty("controle.out", "java.json"));
        ControleImportFile.ImportFile file;
        try (InputStream s = Files.newInputStream(in)) {
            file = new ControleExcelReader().read(s, in.getFileName().toString(),
                    LocalDate.now(), LocalDateTime.now());
        }
        Files.write(out, new ObjectMapper().writeValueAsBytes(file));
        System.out.println("lines " + file.lines().size() + " stLines " + file.stLines().size()
                + " linked " + file.stLines().stream().filter(x -> !x.link().isEmpty()).count()
                + " payments " + file.stLines().stream().filter(x -> x.paye() > 0).count()
                + " -> " + out.toAbsolutePath());
    }
}
