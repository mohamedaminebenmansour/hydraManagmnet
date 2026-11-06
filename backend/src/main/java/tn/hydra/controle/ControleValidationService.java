package tn.hydra.controle;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.organization.CompanyProfileService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ControleValidationService — PUT /api/controle/validations/{key}.
 *
 * <p>Same version rule as justifications: version 0 → insert; version ≥ 1 →
 * update where version = ?. Statut "none" is invalid (400).
 */
@Service
public class ControleValidationService {

    private static final Set<String> VALID_STATUS = Set.of("valide", "a-revoir", "conteste");
    private static final java.util.regex.Pattern KEY_PATTERN =
            java.util.regex.Pattern.compile("^(person|st|ngnet)-[a-z0-9-]{1,150}$");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public ControleValidationService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    public record ValidBody(String statut, BigDecimal agreedBalance, String note, int version) {}

    @Transactional
    public ControleQueryService.CtlValid putValidation(Authentication auth, String key, ValidBody body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        // Validate key format + fields, collect every error
        Map<String, String> errors = validateValid(key, body);
        if (!errors.isEmpty()) {
            throw new CompanyProfileService.ValidationException("VALIDATION", errors);
        }

        // Version rule (same as justifications)
        if (body.version() == 0) {
            int inserted = jdbc.update("""
                    insert into ctl_validation (org_id, key, statut, agreed_balance, note, version, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, 1, ?, now())
                    on conflict (org_id, key) do nothing
                    """, orgId, key, body.statut(), body.agreedBalance(), body.note(), me.getId());
            if (inserted == 0) {
                throw new BusinessRuleException("STALE",
                        "Quelqu'un d'autre a modifié cette validation. Rechargez.");
            }
        } else {
            int updated = jdbc.update("""
                    update ctl_validation set statut = ?, agreed_balance = ?, note = ?,
                        version = version + 1, updated_by = ?, updated_at = now()
                    where org_id = ? and key = ? and version = ?
                    """, body.statut(), body.agreedBalance(), body.note(), me.getId(),
                    orgId, key, body.version());
            if (updated == 0) {
                throw new BusinessRuleException("STALE",
                        "Quelqu'un d'autre a modifié cette validation. Rechargez.");
            }
        }

        // Audit
        audit.log("CONTROLE_VALIDATED", "ctl_validation", null,
                "{\"key\":\"" + jsonEscape(key) + "\",\"statut\":\"" + body.statut()
                + "\",\"agreedBalance\":" + (body.agreedBalance() == null ? "null"
                        : body.agreedBalance().toPlainString()) + "}");

        // Return the saved row
        return loadValid(orgId, key);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private Map<String, String> validateValid(String key, ValidBody body) {
        Map<String, String> errors = new LinkedHashMap<>();

        // key must match ^(person|st|ngnet)-[a-z0-9-]{1,150}$
        if (!KEY_PATTERN.matcher(key).matches()) {
            errors.put("key", "Clé invalide.");
        }

        // statut in (valide, a-revoir, conteste)
        if (!VALID_STATUS.contains(body.statut())) {
            errors.put("statut", "Statut invalide.");
        }

        // agreedBalance: any sign, scale <= 3
        if (body.agreedBalance() != null && body.agreedBalance().scale() > 3) {
            errors.put("agreedBalance", "Montant invalide.");
        }

        // note <= 2000
        String note = body.note() == null ? "" : body.note();
        if (note.length() > 2000) {
            errors.put("note", "2000 caractères au plus.");
        }

        return errors;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ControleQueryService.CtlValid loadValid(UUID orgId, String key) {
        var rows = jdbc.query(
                "select v.*, u.full_name as updater_name from ctl_validation v "
                + "left join app_user u on u.id = v.updated_by "
                + "where v.org_id = ? and v.key = ?",
                (rs, i) -> new ControleQueryService.CtlValid(
                        rs.getString("key"),
                        rs.getString("statut"),
                        rs.getBigDecimal("agreed_balance"),
                        rs.getString("note"),
                        rs.getInt("version"),
                        rs.getObject("updated_by") == null ? null : rs.getObject("updated_by").toString(),
                        rs.getString("updater_name"),
                        rs.getTimestamp("updated_at") == null ? null
                                : rs.getTimestamp("updated_at").toInstant().toString()),
                orgId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
