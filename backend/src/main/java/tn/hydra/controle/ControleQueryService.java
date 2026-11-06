package tn.hydra.controle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ControleQueryService — read-only queries for the Contrôle de trésorerie module.
 *
 * <p>{@link #getData(Authentication)} returns the full dataset for the caller's
 * org: the latest import metadata, all accounts/lines/stLines, and all
 * justifications/validations. The response shape matches the frontend
 * {@code CtlData} interface field by field.
 */
@Service
public class ControleQueryService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    public ControleQueryService(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc; this.users = users;
    }

    // ════════════════════════════════════════════════════════════════════════
    // RESPONSE RECORDS — field names match the frontend interfaces EXACTLY
    // ════════════════════════════════════════════════════════════════════════

    public record CtlMeta(String source, String generated, int lines, int stLines) {}

    public record CtlAccount(String id, String name, String kind, String owner,
                              String project, String note) {}

    public record CtlStAccount(String id, String sheet, String person, String proj,
                                boolean supplier) {}

    public record CtlLine(String id, String sheet, int row, String date, String acct,
                          String proj, BigDecimal e, BigDecimal d, String mode, String bon,
                          String fct, String cat, String desig, String party, String poste,
                          String kind, String obs, List<String> flags, String stLink,
                          String article, String piece, String rubrique, String famille) {}

    public record CtlRecap(String proj, String rubrique, BigDecimal amount) {}

    public record CtlStLine(String id, String st, String sheet, int row, String date,
                            BigDecimal travaux, BigDecimal paye, String mode, String desig,
                            String link, List<String> flags) {}

    public record CtlJustif(String lineKey, String statut, String docType, String reference,
                            String docDate, BigDecimal docAmount, String note, int version,
                            String updatedBy, String updatedByName, String updatedAt) {}

    public record CtlValid(String key, String statut, BigDecimal agreedBalance, String note,
                           int version, String updatedBy, String updatedByName, String updatedAt) {}

    public record CtlImport(String id, String sourceName, String generatedAt, String importedAt,
                            String importedBy, String importerName, int lineCount, int stLineCount) {}

    public record CtlData(CtlMeta meta, List<CtlAccount> accounts,
                          List<CtlStAccount> stAccounts, List<CtlLine> lines,
                          List<CtlStLine> stLines, List<CtlJustif> justif,
                          List<CtlValid> valid, CtlImport imported, List<CtlRecap> recap) {}

    // ════════════════════════════════════════════════════════════════════════
    // GET /api/controle/data
    // ════════════════════════════════════════════════════════════════════════

    public CtlData getData(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        // Latest import row
        List<Map<String, Object>> imports = jdbc.queryForList(
                "select * from ctl_import where org_id = ? order by imported_at desc limit 1", orgId);

        CtlMeta meta = null;
        CtlImport imported = null;
        if (!imports.isEmpty()) {
            Map<String, Object> row = imports.get(0);
            String source = str(row.get("source_name"));
            String generated = str(row.get("generated_at"));
            int lineCount = num(row.get("line_count")).intValue();
            int stLineCount = num(row.get("st_line_count")).intValue();
            meta = new CtlMeta(source, generated, lineCount, stLineCount);
            imported = new CtlImport(
                    str(row.get("id")),
                    source,
                    generated,
                    ts(row.get("imported_at")),
                    uuidStr(row.get("imported_by")),
                    resolveName(row.get("imported_by")),
                    lineCount,
                    stLineCount);
        }

        List<CtlAccount> accounts = loadAccounts(orgId);
        List<CtlStAccount> stAccounts = loadStAccounts(orgId);
        List<CtlLine> lines = loadLines(orgId);
        List<CtlStLine> stLines = loadStLines(orgId);
        List<CtlJustif> justif = loadJustifications(orgId);
        List<CtlValid> valid = loadValidations(orgId);

        List<CtlRecap> recap = loadRecap(orgId);

        return new CtlData(meta, accounts, stAccounts, lines, stLines, justif, valid, imported, recap);
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOADERS — every query scoped by org_id
    // ════════════════════════════════════════════════════════════════════════

    private List<CtlAccount> loadAccounts(UUID orgId) {
        return jdbc.query(
                "select id, name, kind, owner, project, note from ctl_account where org_id = ? order by kind, id",
                (rs, i) -> new CtlAccount(
                        rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                        rs.getString("owner"), rs.getString("project"), rs.getString("note")),
                orgId);
    }

    private List<CtlStAccount> loadStAccounts(UUID orgId) {
        return jdbc.query(
                "select id, sheet, person, project, supplier from ctl_st_account where org_id = ? order by person, id",
                (rs, i) -> new CtlStAccount(
                        rs.getString("id"), rs.getString("sheet"), rs.getString("person"),
                        rs.getString("project"), rs.getBoolean("supplier")),
                orgId);
    }

    private List<CtlLine> loadLines(UUID orgId) {
        return jdbc.query(
                "select * from ctl_line where org_id = ? order by sheet, excel_row",
                (rs, i) -> new CtlLine(
                        rs.getString("line_key"),
                        rs.getString("sheet"),
                        rs.getInt("excel_row"),
                        dateStr(rs.getDate("line_date")),
                        rs.getString("account_id"),
                        rs.getString("project"),
                        rs.getBigDecimal("amount_in"),
                        rs.getBigDecimal("amount_out"),
                        rs.getString("pay_mode"),
                        rs.getString("bon"),
                        rs.getString("fct"),
                        rs.getString("category_excel"),
                        rs.getString("designation"),
                        rs.getString("party"),
                        rs.getString("poste"),
                        rs.getString("kind"),
                        rs.getString("obs"),
                        flagsToList(rs.getArray("flags")),
                        rs.getString("st_line_key"),
                        rs.getString("article"),
                        rs.getString("piece"),
                        rs.getString("rubrique"),
                        rs.getString("famille")),
                orgId);
    }

    private List<CtlRecap> loadRecap(UUID orgId) {
        return jdbc.query(
                "select project, rubrique, amount from ctl_recap where org_id = ? order by project, rubrique",
                (rs, i) -> new CtlRecap(rs.getString("project"), rs.getString("rubrique"), rs.getBigDecimal("amount")),
                orgId);
    }

    private List<CtlStLine> loadStLines(UUID orgId) {
        return jdbc.query(
                "select * from ctl_st_line where org_id = ? order by st_account_id, excel_row",
                (rs, i) -> new CtlStLine(
                        rs.getString("line_key"),
                        rs.getString("st_account_id"),
                        rs.getString("sheet"),
                        rs.getInt("excel_row"),
                        dateStr(rs.getDate("line_date")),
                        rs.getBigDecimal("travaux"),
                        rs.getBigDecimal("paye"),
                        rs.getString("pay_mode"),
                        rs.getString("designation"),
                        rs.getString("link_line_key"),
                        flagsToList(rs.getArray("flags"))),
                orgId);
    }

    private List<CtlJustif> loadJustifications(UUID orgId) {
        return jdbc.query(
                "select j.*, u.full_name as updater_name from ctl_justification j "
                        + "left join app_user u on u.id = j.updated_by "
                        + "where j.org_id = ? order by j.line_key",
                (rs, i) -> new CtlJustif(
                        rs.getString("line_key"),
                        rs.getString("statut"),
                        rs.getString("doc_type"),
                        rs.getString("reference"),
                        dateStr(rs.getDate("doc_date")),
                        rs.getBigDecimal("doc_amount"),
                        rs.getString("note"),
                        rs.getInt("version"),
                        uuidStr(rs.getObject("updated_by")),
                        rs.getString("updater_name"),
                        ts(rs.getObject("updated_at"))),
                orgId);
    }

    private List<CtlValid> loadValidations(UUID orgId) {
        return jdbc.query(
                "select v.*, u.full_name as updater_name from ctl_validation v "
                        + "left join app_user u on u.id = v.updated_by "
                        + "where v.org_id = ? order by v.key",
                (rs, i) -> new CtlValid(
                        rs.getString("key"),
                        rs.getString("statut"),
                        rs.getBigDecimal("agreed_balance"),
                        rs.getString("note"),
                        rs.getInt("version"),
                        uuidStr(rs.getObject("updated_by")),
                        rs.getString("updater_name"),
                        ts(rs.getObject("updated_at"))),
                orgId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private static List<String> flagsToList(Array arr) {
        if (arr == null) return List.of();
        try {
            String[] a = (String[]) arr.getArray();
            return a == null ? List.of() : List.of(a);
        } catch (SQLException e) {
            return List.of();
        }
    }

    private static String dateStr(Date d) {
        return d == null ? null : d.toLocalDate().format(DATE_FMT);
    }

    private static String ts(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) {
            return t.toInstant().toString();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.format(TS_FMT);
        }
        return o.toString();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Number num(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n;
        return 0;
    }

    private static String uuidStr(Object o) {
        return o == null ? null : o.toString();
    }

    private String resolveName(Object userId) {
        if (userId == null) return null;
        try {
            List<String> names = jdbc.queryForList(
                    "select full_name from app_user where id = ?", String.class, userId);
            return names.isEmpty() ? null : names.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
