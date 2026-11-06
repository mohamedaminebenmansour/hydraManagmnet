package tn.hydra.controle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.organization.CompanyProfileService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ControleJustificationService — PUT /api/controle/justifications/{lineKey}.
 *
 * <p>Version rule: version 0 → insert (version 1); version ≥ 1 → update where
 * version = ?. Statut "none" → delete (no version check).
 */
@Service
public class ControleJustificationService {

    private static final Set<String> VALID_STATUS = Set.of("justifie", "partiel", "non-justifie", "conteste");
    private static final Set<String> VALID_DOCS = Set.of(
            "Facture", "Attachement signé", "Bon de livraison", "Bon de commande",
            "Reçu signé", "Ordre de virement", "Relevé bancaire", "Décompte", "Autre");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public ControleJustificationService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ── Request body ──────────────────────────────────────────────────────────

    public record JustifBody(String statut, String docType, String reference, String docDate,
                              BigDecimal docAmount, String note, int version) {}

    // ── PUT ────────────────────────────────────────────────────────────────────

    @Transactional
    public Object putJustification(Authentication auth, String lineKey, JustifBody body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        // (a) lineKey must exist in ctl_line or ctl_st_line of MY org
        if (!lineExists(orgId, lineKey)) {
            throw new ResourceNotFoundException("LINE_NOT_FOUND", "Ligne introuvable.");
        }

        // (b) statut "none" -> delete (no version check)
        if ("none".equals(body.statut())) {
            jdbc.update("delete from ctl_justification where org_id = ? and line_key = ?", orgId, lineKey);
            return Map.of("deleted", true);
        }

        // (c) Validate ALL fields, collect every error
        Map<String, String> errors = validateJustif(body);
        if (!errors.isEmpty()) {
            throw new CompanyProfileService.ValidationException("VALIDATION", errors);
        }

        // (d) Version rule
        if (body.version() == 0) {
            // Insert with version 1, on conflict do nothing
            int inserted = jdbc.update("""
                    insert into ctl_justification (org_id, line_key, statut, doc_type, reference, doc_date, doc_amount, note, version, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, now())
                    on conflict (org_id, line_key) do nothing
                    """, orgId, lineKey, body.statut(), blankToNull(body.docType()), blankToNull(body.reference()),
                    parseDate(body.docDate()), body.docAmount(), blankToNull(body.note()), me.getId());
            if (inserted == 0) {
                throw new BusinessRuleException("STALE",
                        "Quelqu'un d'autre a modifié ce justificatif. Rechargez.");
            }
        } else {
            // Update where version = ?
            int updated = jdbc.update("""
                    update ctl_justification set statut = ?, doc_type = ?, reference = ?, doc_date = ?, doc_amount = ?, note = ?,
                        version = version + 1, updated_by = ?, updated_at = now()
                    where org_id = ? and line_key = ? and version = ?
                    """, body.statut(), blankToNull(body.docType()), blankToNull(body.reference()),
                    parseDate(body.docDate()), body.docAmount(), blankToNull(body.note()), me.getId(),
                    orgId, lineKey, body.version());
            if (updated == 0) {
                throw new BusinessRuleException("STALE",
                        "Quelqu'un d'autre a modifié ce justificatif. Rechargez.");
            }
        }

        // (e) Audit
        audit.log("CONTROLE_JUSTIFIED", "ctl_justification", null,
                "{\"lineKey\":\"" + jsonEscape(lineKey) + "\",\"statut\":\"" + body.statut()
                + "\",\"docType\":\"" + jsonEscape(str(body.docType()))
                + "\",\"reference\":\"" + jsonEscape(str(body.reference()))
                + "\",\"docAmount\":" + (body.docAmount() == null ? "null" : body.docAmount().toPlainString()) + "}");

        // (f) Return the saved row in CtlJustif shape
        return loadJustif(orgId, lineKey);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private Map<String, String> validateJustif(JustifBody body) {
        Map<String, String> errors = new LinkedHashMap<>();

        // statut
        if (!VALID_STATUS.contains(body.statut())) {
            errors.put("statut", "Statut invalide.");
        }

        // docType — required only for "justifie" and "partiel"
        String docType = str(body.docType());
        boolean needsDoc = "justifie".equals(body.statut()) || "partiel".equals(body.statut());
        if (docType.isEmpty()) {
            if (needsDoc) errors.put("docType", "Choisissez le type de document.");
        } else if (!VALID_DOCS.contains(docType)) {
            errors.put("docType", "Type de document invalide.");
        }

        // reference <= 120
        String ref = str(body.reference());
        if (ref.length() > 120) {
            errors.put("reference", "120 caractères au plus.");
        }

        // note <= 2000
        String note = str(body.note());
        if (note.length() > 2000) {
            errors.put("note", "2000 caractères au plus.");
        }

        // docDate empty or yyyy-MM-dd
        String date = str(body.docDate());
        if (!date.isEmpty()) {
            try { LocalDate.parse(date, DATE_FMT); }
            catch (RuntimeException e) { errors.put("docDate", "Date invalide."); }
        }

        // docAmount null or >= 0 with scale <= 3
        if (body.docAmount() != null) {
            if (body.docAmount().scale() > 3) {
                errors.put("docAmount", "Montant invalide.");
            } else if (body.docAmount().compareTo(BigDecimal.ZERO) < 0) {
                errors.put("docAmount", "Montant invalide.");
            }
        }

        return errors;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean lineExists(UUID orgId, String lineKey) {
        Integer n = jdbc.queryForObject(
                "select (select count(*) from ctl_line where org_id = ? and line_key = ?) "
                + "+ (select count(*) from ctl_st_line where org_id = ? and line_key = ?)",
                Integer.class, orgId, lineKey, orgId, lineKey);
        return n != null && n > 0;
    }

    private static java.sql.Date parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.sql.Date.valueOf(LocalDate.parse(s.trim(), DATE_FMT)); }
        catch (RuntimeException e) { return null; }
    }

    /** Load a single justification row in the CtlJustif shape (with updatedByName). */
    private ControleQueryService.CtlJustif loadJustif(UUID orgId, String lineKey) {
        var rows = jdbc.query(
                "select j.*, u.full_name as updater_name from ctl_justification j "
                + "left join app_user u on u.id = j.updated_by "
                + "where j.org_id = ? and j.line_key = ?",
                (rs, i) -> new ControleQueryService.CtlJustif(
                        rs.getString("line_key"),
                        rs.getString("statut"),
                        rs.getString("doc_type"),
                        rs.getString("reference"),
                        rs.getDate("doc_date") == null ? null
                                : rs.getDate("doc_date").toLocalDate().format(DATE_FMT),
                        rs.getBigDecimal("doc_amount"),
                        rs.getString("note"),
                        rs.getInt("version"),
                        rs.getObject("updated_by") == null ? null : rs.getObject("updated_by").toString(),
                        rs.getString("updater_name"),
                        rs.getTimestamp("updated_at") == null ? null
                                : rs.getTimestamp("updated_at").toInstant().toString()),
                orgId, lineKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String str(String s) { return s == null ? "" : s; }

    /** Return null for null/blank strings, otherwise the trimmed value. */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : s;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
