package tn.hydra.controle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Java records matching the JSON produced by tools/controle/convert.py EXACTLY.
 *
 * <p>Jackson 3 (the Spring Boot 4 ObjectMapper bean) ignores unknown properties
 * by default; {@code @JsonIgnoreProperties(ignoreUnknown = true)} is added
 * explicitly on every record so the importer keeps working even if convert.py
 * adds a new field before this file is updated.
 */
public final class ControleImportFile {

    private ControleImportFile() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String source, String generated, int lines, int stLines) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(String id, String name, String kind, String owner,
                          String project, String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StAccount(String id, String sheet, String person, String proj,
                            boolean supplier) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String id, String sheet, int row, String date, String acct,
                       String proj, double e, double d, String mode, String bon,
                       String fct, String cat, String desig, String party, String poste,
                       String kind, String obs, List<String> flags, String stLink) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StLine(String id, String st, String sheet, int row, String date,
                         double travaux, double paye, String mode, String desig,
                         String link, List<String> flags) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportFile(Meta meta, List<Account> accounts,
                             List<StAccount> stAccounts, List<Line> lines,
                             List<StLine> stLines) {}
}
