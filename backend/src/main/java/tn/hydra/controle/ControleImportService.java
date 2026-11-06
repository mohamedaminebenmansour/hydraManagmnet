package tn.hydra.controle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ControleImportService — imports a JSON file produced by tools/controle/convert.py
 * into the ctl_* tables. ONE user action = ONE transaction (rule 14).
 *
 * <p>The org id ALWAYS comes from the logged-in user. No method takes an orgId
 * parameter (same pattern as {@code CompanyProfileService}).
 *
 * <p>This module is an archive of the company's cash Excel. It NEVER touches
 * {@code cash_pot} or {@code cash_movement}, and it NEVER touches
 * {@code ctl_justification} or {@code ctl_validation} — those survive a re-import.
 */
@Service
public class ControleImportService {

    private static final int MAX_PROBLEMS = 20;
    private static final int BATCH_SIZE = 500;
    private static final Set<String> VALID_ACCOUNT_KINDS = Set.of("banque", "especes", "chantier", "partenaire", "echeance");
    private static final Set<String> VALID_LINE_KINDS = Set.of("entree", "sortie", "transfert", "partenaire");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final ObjectMapper objectMapper;
    private final EventLogService audit;

    public ControleImportService(JdbcTemplate jdbc, AppUserRepository users,
                                  ObjectMapper objectMapper, EventLogService audit) {
        this.jdbc = jdbc; this.users = users;
        this.objectMapper = objectMapper; this.audit = audit;
    }

    // ════════════════════════════════════════════════════════════════════════
    // RESPONSE
    // ════════════════════════════════════════════════════════════════════════

    public record ImportResult(UUID importId, int lines, int stLines, int accounts,
                               int orphanJustifications) {}

    // ════════════════════════════════════════════════════════════════════════
    // IMPORT
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Parse + validate + persist a convert.py JSON export for the caller's org.
     * Everything happens in ONE @Transactional block: if anything fails after
     * the deletes, the transaction rolls back and the previous import stays
     * as it was.
     */
    @Transactional
    public ImportResult importFile(java.security.Principal auth, byte[] json) {
        if (auth == null) throw new IllegalStateException("Authentication required");

        // ── (a) Parse ──────────────────────────────────────────────────────
        ControleImportFile.ImportFile file;
        try {
            file = objectMapper.readValue(json, ControleImportFile.ImportFile.class);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("IMPORT_FORMAT",
                    "Le fichier n'est pas un export valide de convert.py.");
        }
        return importParsed(auth, file);
    }

    /**
     * Validate + persist an already-parsed file (the JSON above, or the .xlsx
     * read by {@link ControleExcelReader}). Same single transaction.
     */
    @Transactional
    public ImportResult importParsed(java.security.Principal auth, ControleImportFile.ImportFile file) {
        if (auth == null) throw new IllegalStateException("Authentication required");
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        // ── (b) Validate, collecting up to 20 problems ────────────────────
        List<String> problems = validate(file);
        if (!problems.isEmpty()) {
            throw new ImportInvalidException(
                    "Le fichier contient " + problems.size() + " problème(s).",
                    problems);
        }

        // ── (c) Persist in ONE transaction ────────────────────────────────
        // Delete the previous import's rows (org-scoped). NEVER touch
        // ctl_justification or ctl_validation — those survive a re-import.
        jdbc.update("delete from ctl_line       where org_id = ?", orgId);
        jdbc.update("delete from ctl_st_line    where org_id = ?", orgId);
        jdbc.update("delete from ctl_account    where org_id = ?", orgId);
        jdbc.update("delete from ctl_st_account where org_id = ?", orgId);
        jdbc.update("delete from ctl_recap      where org_id = ?", orgId);

        insertAccounts(orgId, file.accounts());
        insertStAccounts(orgId, file.stAccounts());
        // Article aliases of the org (raw_key → article) — never deleted by an import.
        Map<String, String> aliases = new HashMap<>();
        jdbc.query("select raw_key, article from ctl_article_alias where org_id = ?",
                rs -> { aliases.put(rs.getString("raw_key"), rs.getString("article")); }, orgId);
        insertLines(orgId, file.lines(), aliases);
        insertStLines(orgId, file.stLines());
        insertRecap(orgId, file.recap() == null ? List.of() : file.recap());

        UUID importId = UUID.randomUUID();
        jdbc.update("""
                insert into ctl_import (id, org_id, source_name, generated_at, imported_by, line_count, st_line_count)
                values (?, ?, ?, ?, ?, ?, ?)
                """, importId, orgId,
                file.meta().source(),
                file.meta().generated(),
                me.getId(),
                file.lines().size(),
                file.stLines().size());

        // Audit
        audit.log("CONTROLE_IMPORTED", "ctl_import", importId,
                "{\"source\":\"" + jsonEscape(file.meta().source())
                + "\",\"lines\":" + file.lines().size()
                + ",\"stLines\":" + file.stLines().size() + "}");

        // ── (d) Count orphan justifications ───────────────────────────────
        int orphan = countOrphanJustifications(orgId, file.lines(), file.stLines());

        return new ImportResult(importId, file.lines().size(), file.stLines().size(),
                file.accounts().size(), orphan);
    }

    // ════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ════════════════════════════════════════════════════════════════════════

    /** Collect up to 20 French problem messages. Returns the list (empty = valid). */
    private List<String> validate(ControleImportFile.ImportFile file) {
        List<String> problems = new ArrayList<>();
        if (file == null) {
            problems.add("Le fichier est vide.");
            return problems;
        }
        if (file.meta() == null) {
            problems.add("La section « meta » est manquante.");
            return problems; // can't check counts without meta
        }
        List<ControleImportFile.Account> accounts = file.accounts() == null ? List.of() : file.accounts();
        List<ControleImportFile.StAccount> stAccounts = file.stAccounts() == null ? List.of() : file.stAccounts();
        List<ControleImportFile.Line> lines = file.lines() == null ? List.of() : file.lines();
        List<ControleImportFile.StLine> stLines = file.stLines() == null ? List.of() : file.stLines();

        // meta counts
        if (file.meta().lines() != lines.size()) {
            problems.add("meta.lines = " + file.meta().lines()
                    + " mais le fichier contient " + lines.size() + " ligne(s).");
        }
        if (file.meta().stLines() != stLines.size()) {
            problems.add("meta.stLines = " + file.meta().stLines()
                    + " mais le fichier contient " + stLines.size() + " sous-traitant(s).");
        }

        // account ids unique + kind valid
        Set<String> accountIds = new HashSet<>();
        for (ControleImportFile.Account a : accounts) {
            if (a.id() == null || a.id().isBlank()) {
                problems.add("Un compte sans identifiant a été trouvé.");
            } else if (!accountIds.add(a.id())) {
                problems.add("Le compte « " + a.id() + " » apparaît plusieurs fois.");
            }
            if (a.kind() == null || !VALID_ACCOUNT_KINDS.contains(a.kind())) {
                problems.add("Le compte « " + a.id() + " » a un type invalide : "
                        + (a.kind() == null ? "(manquant)" : a.kind()) + ".");
            }
        }

        // line ids unique + acct valid + kind valid + amounts >= 0
        Set<String> lineIds = new HashSet<>();
        for (ControleImportFile.Line l : lines) {
            String lid = l.id() == null ? "(sans identifiant)" : l.id();
            if (l.id() == null || l.id().isBlank()) {
                problems.add("Une ligne sans identifiant a été trouvée.");
            } else if (!lineIds.add(l.id())) {
                problems.add("La ligne « " + l.id() + " » apparaît plusieurs fois.");
            }
            if (l.acct() != null && !l.acct().isEmpty() && !accountIds.contains(l.acct())) {
                problems.add("La ligne « " + lid + " » référence un compte inconnu : « " + l.acct() + " ».");
            }
            if (l.kind() == null || !VALID_LINE_KINDS.contains(l.kind())) {
                problems.add("La ligne « " + lid + " » a un type invalide : "
                        + (l.kind() == null ? "(manquant)" : l.kind()) + ".");
            }
            // Negative amounts are accepted: they are corrections (a cancelled payment
            // written as a negative amount). The real company file has ~11 such lines.
            if (problems.size() >= MAX_PROBLEMS) break;
        }

        // stAccount ids unique
        Set<String> stAccountIds = new HashSet<>();
        for (ControleImportFile.StAccount a : stAccounts) {
            if (a.id() == null || a.id().isBlank()) {
                problems.add("Un sous-traitant sans identifiant a été trouvé.");
            } else if (!stAccountIds.add(a.id())) {
                problems.add("Le sous-traitant « " + a.id() + " » apparaît plusieurs fois.");
            }
            if (problems.size() >= MAX_PROBLEMS) break;
        }

        // stLine ids unique + st is an stAccount id + stLine ids not equal to any line id + amounts >= 0
        Set<String> stLineIds = new HashSet<>();
        for (ControleImportFile.StLine s : stLines) {
            String sid = s.id() == null ? "(sans identifiant)" : s.id();
            if (s.id() == null || s.id().isBlank()) {
                problems.add("Une ligne sous-traitant sans identifiant a été trouvée.");
            } else if (!stLineIds.add(s.id())) {
                problems.add("La ligne sous-traitant « " + s.id() + " » apparaît plusieurs fois.");
            } else if (lineIds.contains(s.id())) {
                problems.add("La ligne sous-traitant « " + s.id()
                        + " » a le même identifiant qu'une ligne de caisse.");
            }
            if (s.st() == null || !stAccountIds.contains(s.st())) {
                problems.add("La ligne sous-traitant « " + sid
                        + " » référence un sous-traitant inconnu : « " + (s.st() == null ? "(manquant)" : s.st()) + " ».");
            }
            // Negative travaux/paye are accepted: they are corrections.
            if (problems.size() >= MAX_PROBLEMS) break;
        }

        return problems;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PERSISTENCE — jdbc.batchUpdate in batches of 500
    // ════════════════════════════════════════════════════════════════════════

    private void insertAccounts(UUID orgId, List<ControleImportFile.Account> accounts) {
        if (accounts.isEmpty()) return;
        List<ControleImportFile.Account> batch = new ArrayList<>(BATCH_SIZE);
        for (ControleImportFile.Account a : accounts) {
            batch.add(a);
            if (batch.size() >= BATCH_SIZE) { flushAccounts(orgId, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) flushAccounts(orgId, batch);
    }

    private void flushAccounts(UUID orgId, List<ControleImportFile.Account> batch) {
        jdbc.batchUpdate("""
                insert into ctl_account (org_id, id, name, kind, owner, project, note)
                values (?, ?, ?, ?, ?, ?, ?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public int getBatchSize() { return batch.size(); }
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ControleImportFile.Account a = batch.get(i);
                ps.setObject(1, orgId);
                ps.setString(2, nz(a.id()));
                ps.setString(3, nz(a.name()));
                ps.setString(4, nz(a.kind()));
                ps.setString(5, nz(a.owner()));
                ps.setString(6, nz(a.project()));
                ps.setString(7, emptyToNull(a.note())); // note stays null when empty
            }
        });
    }

    private void insertStAccounts(UUID orgId, List<ControleImportFile.StAccount> stAccounts) {
        if (stAccounts.isEmpty()) return;
        List<ControleImportFile.StAccount> batch = new ArrayList<>(BATCH_SIZE);
        for (ControleImportFile.StAccount a : stAccounts) {
            batch.add(a);
            if (batch.size() >= BATCH_SIZE) { flushStAccounts(orgId, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) flushStAccounts(orgId, batch);
    }

    private void flushStAccounts(UUID orgId, List<ControleImportFile.StAccount> batch) {
        jdbc.batchUpdate("""
                insert into ctl_st_account (org_id, id, sheet, person, project, supplier)
                values (?, ?, ?, ?, ?, ?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public int getBatchSize() { return batch.size(); }
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ControleImportFile.StAccount a = batch.get(i);
                ps.setObject(1, orgId);
                ps.setString(2, nz(a.id()));
                ps.setString(3, nz(a.sheet()));
                ps.setString(4, nz(a.person()));
                ps.setString(5, nz(a.proj()));
                ps.setBoolean(6, a.supplier());
            }
        });
    }

    private void insertLines(UUID orgId, List<ControleImportFile.Line> lines, Map<String, String> aliases) {
        if (lines.isEmpty()) return;
        List<ControleImportFile.Line> batch = new ArrayList<>(BATCH_SIZE);
        for (ControleImportFile.Line l : lines) {
            batch.add(l);
            if (batch.size() >= BATCH_SIZE) { flushLines(orgId, batch, aliases); batch.clear(); }
        }
        if (!batch.isEmpty()) flushLines(orgId, batch, aliases);
    }

    private void flushLines(UUID orgId, List<ControleImportFile.Line> batch, Map<String, String> aliases) {
        jdbc.batchUpdate("""
                insert into ctl_line (org_id, line_key, sheet, excel_row, line_date, account_id,
                    project, amount_in, amount_out, pay_mode, bon, fct, category_excel,
                    designation, party, poste, kind, obs, flags, st_line_key,
                    raw_key, article, piece, rubrique, famille)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public int getBatchSize() { return batch.size(); }
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ControleImportFile.Line l = batch.get(i);
                ps.setObject(1, orgId);
                ps.setString(2, nz(l.id()));
                ps.setString(3, nz(l.sheet()));
                ps.setInt(4, l.row());
                ps.setDate(5, parseDate(l.date()));
                ps.setString(6, nz(l.acct()));
                ps.setString(7, nz(l.proj()));
                ps.setBigDecimal(8, toBd(l.e()));
                ps.setBigDecimal(9, toBd(l.d()));
                ps.setString(10, nz(l.mode()));
                ps.setString(11, nz(l.bon()));
                ps.setString(12, nz(l.fct()));
                ps.setString(13, nz(l.cat()));
                ps.setString(14, nz(l.desig()));
                ps.setString(15, nz(l.party()));
                ps.setString(16, nz(l.poste()));
                ps.setString(17, nz(l.kind()));
                ps.setString(18, nz(l.obs()));
                ps.setArray(19, ps.getConnection().createArrayOf("text",
                        (l.flags() == null || l.flags().isEmpty()) ? new String[0] : l.flags().toArray()));
                ps.setString(20, emptyToNull(l.stLink())); // st_line_key stays null when empty
                // Article = the org's alias for raw_key, else the analyse.py rule.
                String rawKey = ControleArticleRules.plain(l.cat());
                String alias = aliases.get(rawKey);
                String article = alias != null ? alias : ControleArticleRules.article(l.cat(), l.party());
                String rubrique = ControleRubriques.rubrique(article, rawKey);
                ps.setString(21, rawKey);
                ps.setString(22, article);
                ps.setString(23, ControleArticleRules.piece(l.fct(), l.bon()));
                ps.setString(24, rubrique);
                ps.setString(25, ControleRubriques.famille(rubrique));
            }
        });
    }

    private void insertStLines(UUID orgId, List<ControleImportFile.StLine> stLines) {
        if (stLines.isEmpty()) return;
        List<ControleImportFile.StLine> batch = new ArrayList<>(BATCH_SIZE);
        for (ControleImportFile.StLine s : stLines) {
            batch.add(s);
            if (batch.size() >= BATCH_SIZE) { flushStLines(orgId, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) flushStLines(orgId, batch);
    }

    private void flushStLines(UUID orgId, List<ControleImportFile.StLine> batch) {
        jdbc.batchUpdate("""
                insert into ctl_st_line (org_id, line_key, st_account_id, sheet, excel_row, line_date,
                    travaux, paye, pay_mode, designation, link_line_key, flags)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public int getBatchSize() { return batch.size(); }
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ControleImportFile.StLine s = batch.get(i);
                ps.setObject(1, orgId);
                ps.setString(2, nz(s.id()));
                ps.setString(3, nz(s.st()));
                ps.setString(4, nz(s.sheet()));
                ps.setInt(5, s.row());
                ps.setDate(6, parseDate(s.date()));
                ps.setBigDecimal(7, toBd(s.travaux()));
                ps.setBigDecimal(8, toBd(s.paye()));
                ps.setString(9, nz(s.mode()));
                ps.setString(10, nz(s.desig()));
                ps.setString(11, emptyToNull(s.link())); // link_line_key stays null when empty
                ps.setArray(12, ps.getConnection().createArrayOf("text",
                        (s.flags() == null || s.flags().isEmpty()) ? new String[0] : s.flags().toArray()));
            }
        });
    }

    /** The Excel table « Récap des dépenses par projet », replaced at each import. */
    private void insertRecap(UUID orgId, List<ControleImportFile.Recap> recap) {
        if (recap.isEmpty()) return;
        jdbc.batchUpdate("insert into ctl_recap (org_id, project, rubrique, amount) values (?, ?, ?, ?)",
                recap.stream().map(r -> new Object[]{orgId, nz(r.proj()), nz(r.rubrique()), toBd(r.amount())}).toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    // IMPORT HISTORY
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Last 10 ctl_import rows of my org, newest first, with the importer's name.
     */
    public List<ControleImportController.ImportHistoryRow> listImports(java.security.Principal auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();
        return jdbc.query("""
                select i.id, i.org_id, i.source_name, i.generated_at, i.imported_at,
                       i.imported_by, u.full_name as importer_name,
                       i.line_count, i.st_line_count
                from ctl_import i
                left join app_user u on u.id = i.imported_by
                where i.org_id = ?
                order by i.imported_at desc
                limit 10
                """, (rs, rowNum) -> new ControleImportController.ImportHistoryRow(
                rs.getObject("id", java.util.UUID.class),
                rs.getObject("org_id", java.util.UUID.class),
                rs.getString("source_name"),
                rs.getString("generated_at"),
                rs.getObject("imported_at", java.time.OffsetDateTime.class),
                rs.getObject("imported_by", java.util.UUID.class),
                rs.getString("importer_name"),
                rs.getInt("line_count"),
                rs.getInt("st_line_count")),
                orgId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ORPHAN JUSTIFICATIONS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Count rows of ctl_justification of this org whose line_key is in neither
     * ctl_line nor ctl_st_line (after this import).
     */
    private int countOrphanJustifications(UUID orgId, List<ControleImportFile.Line> lines,
                                           List<ControleImportFile.StLine> stLines) {
        // Collect the set of line_keys that exist after this import.
        Set<String> liveKeys = new HashSet<>();
        for (ControleImportFile.Line l : lines) liveKeys.add(l.id());
        for (ControleImportFile.StLine s : stLines) liveKeys.add(s.id());

        // Read every justification line_key for this org, count those no longer present.
        List<String> justKeys = jdbc.queryForList(
                "select line_key from ctl_justification where org_id = ?", String.class, orgId);
        int orphan = 0;
        for (String k : justKeys) {
            if (!liveKeys.contains(k)) orphan++;
        }
        return orphan;
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /** Null-safe non-empty: null → "", blank stays as-is. Used for NOT NULL text columns. */
    private static String nz(String s) { return s == null ? "" : s; }

    /** Empty or null → null (for nullable text columns: note, st_line_key, link_line_key). */
    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : s;
    }

    /** Parse a yyyy-MM-dd date string into a java.sql.Date; "" or null → null. */
    private static Date parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Date.valueOf(LocalDate.parse(s.trim()));
        } catch (RuntimeException e) {
            return null; // let the DB store null if the date is malformed
        }
    }

    /** Convert a double amount to a BigDecimal with scale 3, HALF_UP rounding. */
    private static BigDecimal toBd(double x) {
        return BigDecimal.valueOf(x).setScale(3, RoundingMode.HALF_UP);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ════════════════════════════════════════════════════════════════════════
    // EXCEPTION — carries the list of problems for the 400 response
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Thrown when the file is parseable but fails validation. Carries the list
     * of French problem messages so the controller can return them in the 400
     * body as {@code {"code":"IMPORT_INVALID","message":...,"problems":[...]}}.
     */
    public static class ImportInvalidException extends RuntimeException {
        private final List<String> problems;

        public ImportInvalidException(String message, List<String> problems) {
            super(message);
            this.problems = problems;
        }

        public List<String> getProblems() { return problems; }
    }
}
