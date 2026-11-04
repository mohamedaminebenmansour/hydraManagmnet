package tn.hydra.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.document.service.FileStorage;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.util.*;

/**
 * Company profile service (S1-5 step 3): legal identity, bank account, logo.
 * The org id ALWAYS comes from the logged-in user. No method takes an orgId parameter.
 */
@Service
public class CompanyProfileService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final FileStorage storage;

    public CompanyProfileService(JdbcTemplate jdbc, AppUserRepository users,
                                 EventLogService audit, FileStorage storage) {
        this.jdbc = jdbc; this.users = users; this.audit = audit; this.storage = storage;
    }

    // ══════════════════ RESPONSE ══════════════════

    public record CompanyProfileResponse(
            UUID orgId, String pays, String raisonSociale, String nomCommercial,
            String formeJuridique, String matriculeFiscal, String mfFormate,
            String rne, BigDecimal capital, Boolean assujettiTva,
            String adresse, String codePostal, String ville, String gouvernorat,
            String telephone, String email, String siteWeb,
            String banque, String rib, String ribFormate, String iban,
            Boolean hasLogo, int version,
            List<String> manquants, boolean complet) {}

    // ══════════════════ GET ══════════════════

    /** GET /api/company — any authenticated user of the org. */
    public CompanyProfileResponse get(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from company_profile where org_id = ?", me.getOrgId());
        if (rows.isEmpty()) {
            // No row yet: return a stub from organization.name
            String orgName = jdbc.queryForObject(
                    "select name from organization where id = ?", String.class, me.getOrgId());
            return buildResponse(me.getOrgId(), "TN", orgName, null, null, null, null,
                    null, null, true, null, null, null, null, null, null, null,
                    null, null, null, null, false, 0, true);
        }
        Map<String, Object> r = rows.get(0);
        return mapRow(r);
    }

    // ══════════════════ PUT ══════════════════

    public record UpdateRequest(
            String pays, String raisonSociale, String nomCommercial,
            String formeJuridique, String matriculeFiscal, String rne,
            BigDecimal capital, Boolean assujettiTva,
            String adresse, String codePostal, String ville, String gouvernorat,
            String telephone, String email, String siteWeb,
            String banque, String rib, int version) {}

    /**
     * PUT /api/company — PERM_ORG_SETTINGS (checked by @PreAuthorize on controller).
     * Optimistic lock via version. All validation errors returned at once.
     */
    @Transactional
    public CompanyProfileResponse update(Authentication auth, UpdateRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        // Trim + nullify
        String pays = trim(body.pays()); if (pays == null) pays = "TN";
        String raisonSociale = trim(body.raisonSociale());
        String nomCommercial = trim(body.nomCommercial());
        String formeJuridique = trim(body.formeJuridique());
        String matriculeFiscal = trim(body.matriculeFiscal());
        String rne = trim(body.rne());
        String adresse = trim(body.adresse());
        String codePostal = trim(body.codePostal());
        String ville = trim(body.ville());
        String gouvernorat = trim(body.gouvernorat());
        String telephone = trim(body.telephone());
        String email = trim(body.email());
        String siteWeb = trim(body.siteWeb());
        String banque = trim(body.banque());
        String rib = trim(body.rib());
        boolean assujetti = body.assujettiTva() == null || body.assujettiTva();

        // Validation: collect all field errors
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        if (raisonSociale == null) {
            fieldErrors.put("raisonSociale", "La raison sociale est obligatoire.");
        }

        boolean isTN = "TN".equals(pays);
        String mfNormalized = null;
        String ribNormalized = null;

        if (isTN) {
            if (matriculeFiscal != null) {
                var err = TunisianIds.validateMf(matriculeFiscal);
                if (err.isPresent()) {
                    fieldErrors.put("matriculeFiscal", err.get());
                } else {
                    mfNormalized = TunisianIds.normalizeMf(matriculeFiscal);
                }
            }
            if (rib != null) {
                var err = TunisianIds.validateRib(rib);
                if (err.isPresent()) {
                    fieldErrors.put("rib", err.get());
                } else {
                    ribNormalized = TunisianIds.normalizeRib(rib);
                }
            }
            if (codePostal != null && !codePostal.matches("\\d{4}")) {
                fieldErrors.put("codePostal", "Le code postal doit contenir 4 chiffres.");
            }
        } else {
            if (matriculeFiscal != null && matriculeFiscal.length() > 30) {
                fieldErrors.put("matriculeFiscal", "Le matricule fiscal ne peut pas dépasser 30 caractères.");
            }
            if (rib != null && rib.length() > 34) {
                fieldErrors.put("rib", "Le RIB ne peut pas dépasser 34 caractères.");
            }
            mfNormalized = matriculeFiscal;
            ribNormalized = rib;
        }

        if (email != null) {
            int at = email.indexOf('@');
            if (at < 1 || email.lastIndexOf('.') < at + 1) {
                fieldErrors.put("email", "Adresse e-mail invalide.");
            }
        }

        if (!fieldErrors.isEmpty()) {
            throw new ValidationException("VALIDATION", fieldErrors);
        }

        // Load existing row for audit diff
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select * from company_profile where org_id = ?", orgId);

        int newVersion = body.version();
        if (existing.isEmpty()) {
            // INSERT (version must be 0)
            if (body.version() != 0) {
                throw new BusinessRuleException("STALE_PROFILE",
                        "Le profil a été modifié par quelqu'un d'autre. Rechargez la page.");
            }
            jdbc.update("""
                    insert into company_profile (org_id, pays, raison_sociale, nom_commercial, forme_juridique,
                        matricule_fiscal, rne, capital, assujetti_tva, adresse, code_postal, ville,
                        gouvernorat, telephone, email, site_web, banque, rib, version, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    """, orgId, pays, raisonSociale, nomCommercial, formeJuridique,
                    mfNormalized, rne, body.capital(), assujetti, adresse, codePostal, ville,
                    gouvernorat, telephone, email, siteWeb, banque, ribNormalized, me.getId());
            newVersion = 1;
            // Audit: all fields are "new"
            auditChange(orgId, me, null, pays, raisonSociale, nomCommercial, formeJuridique,
                    mfNormalized, rne, body.capital(), assujetti, adresse, codePostal, ville,
                    gouvernorat, telephone, email, siteWeb, banque, ribNormalized);
        } else {
            // UPDATE with optimistic lock
            Map<String, Object> old = existing.get(0);
            int oldVersion = ((Number) old.get("version")).intValue();
            if (body.version() != oldVersion) {
                throw new BusinessRuleException("STALE_PROFILE",
                        "Le profil a été modifié par quelqu'un d'autre. Rechargez la page.");
            }
            int updated = jdbc.update("""
                    update company_profile set pays = ?, raison_sociale = ?, nom_commercial = ?,
                        forme_juridique = ?, matricule_fiscal = ?, rne = ?, capital = ?,
                        assujetti_tva = ?, adresse = ?, code_postal = ?, ville = ?,
                        gouvernorat = ?, telephone = ?, email = ?, site_web = ?,
                        banque = ?, rib = ?, version = version + 1, updated_at = now(), updated_by = ?
                    where org_id = ? and version = ?
                    """, pays, raisonSociale, nomCommercial, formeJuridique,
                    mfNormalized, rne, body.capital(), assujetti, adresse, codePostal, ville,
                    gouvernorat, telephone, email, siteWeb, banque, ribNormalized,
                    me.getId(), orgId, oldVersion);
            if (updated == 0) {
                throw new BusinessRuleException("STALE_PROFILE",
                        "Le profil a été modifié par quelqu'un d'autre. Rechargez la page.");
            }
            newVersion = oldVersion + 1;
            // Audit: only changed fields
            auditChange(orgId, me, old, pays, raisonSociale, nomCommercial, formeJuridique,
                    mfNormalized, rne, body.capital(), assujetti, adresse, codePostal, ville,
                    gouvernorat, telephone, email, siteWeb, banque, ribNormalized);
        }

        // Return fresh GET body
        return get(auth);
    }

    // ══════════════════ LOGO ══════════════════

    @Transactional
    public void uploadLogo(Authentication auth, byte[] content, String detectedContentType) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        String ext = "image/png".equals(detectedContentType) ? ".png" : ".jpg";
        String key = "org/" + orgId + "/company/logo-" + UUID.randomUUID() + ext;

        // Save to storage FIRST (so we have the file)
        storage.save(key, content, detectedContentType);

        // Load old key for cleanup
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select logo_key, version from company_profile where org_id = ?", orgId);
        String oldKey = rows.isEmpty() ? null : (String) rows.get(0).get("logo_key");

        int updated;
        if (rows.isEmpty()) {
            // No profile row yet: create one with just the logo
            String orgName = jdbc.queryForObject(
                    "select name from organization where id = ?", String.class, orgId);
            jdbc.update("""
                    insert into company_profile (org_id, raison_sociale, logo_key, logo_content_type, version, updated_by)
                    values (?, ?, ?, ?, 1, ?)
                    """, orgId, orgName, key, detectedContentType, me.getId());
        } else {
            int oldVersion = ((Number) rows.get(0).get("version")).intValue();
            updated = jdbc.update("""
                    update company_profile set logo_key = ?, logo_content_type = ?,
                        version = version + 1, updated_at = now(), updated_by = ?
                    where org_id = ?
                    """, key, detectedContentType, me.getId(), orgId);
            if (updated == 0) {
                // DB update failed: delete the new file to avoid orphan
                try { storage.delete(key); } catch (RuntimeException ignored) {}
                throw new BusinessRuleException("STALE_PROFILE",
                        "Le profil a été modifié par quelqu'un d'autre. Rechargez la page.");
            }
        }

        audit.log("COMPANY_LOGO_UPDATED", "company_profile", orgId, null);

        // Delete old logo file after commit
        if (oldKey != null) {
            registerAfterCommitDelete(oldKey);
        }
    }

    @Transactional
    public void deleteLogo(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select logo_key, version from company_profile where org_id = ?", orgId);
        if (rows.isEmpty()) return;

        String oldKey = (String) rows.get(0).get("logo_key");
        if (oldKey == null) return;

        jdbc.update("""
                update company_profile set logo_key = null, logo_content_type = null,
                    version = version + 1, updated_at = now(), updated_by = ?
                where org_id = ?
                """, me.getId(), orgId);

        audit.log("COMPANY_LOGO_REMOVED", "company_profile", orgId, null);
        registerAfterCommitDelete(oldKey);
    }

    public record LogoData(byte[] content, String contentType, String logoKey) {}

    /** GET /api/company/logo — streams the logo. Returns null if none. */
    public LogoData getLogo(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select logo_key, logo_content_type from company_profile where org_id = ?", me.getOrgId());
        if (rows.isEmpty()) return null;
        String key = (String) rows.get(0).get("logo_key");
        String contentType = (String) rows.get(0).get("logo_content_type");
        if (key == null) return null;
        byte[] bytes = storage.load(key);
        return new LogoData(bytes, contentType, key);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void registerAfterCommitDelete(String key) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_ROLLED_BACK) {
                            try { storage.delete(key); } catch (RuntimeException e) {
                                org.slf4j.LoggerFactory.getLogger(CompanyProfileService.class)
                                        .warn("Failed to delete old logo file {}", key);
                            }
                        }
                    }
                });
    }

    private void auditChange(UUID orgId, AppUser me, Map<String, Object> oldRow,
                             String pays, String raisonSociale, String nomCommercial,
                             String formeJuridique, String mf, String rne, BigDecimal capital,
                             boolean assujetti, String adresse, String codePostal, String ville,
                             String gouvernorat, String telephone, String email, String siteWeb,
                             String banque, String rib) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        first = addDiff(sb, first, "pays", oldRow, pays);
        first = addDiff(sb, first, "raisonSociale", oldRow, raisonSociale);
        first = addDiff(sb, first, "nomCommercial", oldRow, nomCommercial);
        first = addDiff(sb, first, "formeJuridique", oldRow, formeJuridique);
        first = addDiff(sb, first, "matriculeFiscal", oldRow, mf);
        first = addDiff(sb, first, "rne", oldRow, rne);
        first = addDiffNum(sb, first, "capital", oldRow, capital);
        first = addDiff(sb, first, "adresse", oldRow, adresse);
        first = addDiff(sb, first, "codePostal", oldRow, codePostal);
        first = addDiff(sb, first, "ville", oldRow, ville);
        first = addDiff(sb, first, "gouvernorat", oldRow, gouvernorat);
        first = addDiff(sb, first, "telephone", oldRow, telephone);
        first = addDiff(sb, first, "email", oldRow, email);
        first = addDiff(sb, first, "siteWeb", oldRow, siteWeb);
        first = addDiff(sb, first, "banque", oldRow, banque);
        // RIB is masked in audit
        first = addDiffMasked(sb, first, "rib", oldRow, rib);
        sb.append("}");
        audit.log("COMPANY_PROFILE_UPDATED", "company_profile", orgId, sb.toString());
    }

    private boolean addDiff(StringBuilder sb, boolean first, String field, Map<String, Object> old, String newVal) {
        String oldVal = old == null ? null : (String) old.get(snake(field));
        if (Objects.equals(oldVal, newVal)) return first;
        if (!first) sb.append(",");
        sb.append("\"").append(field).append("\":{\"old\":")
          .append(oldVal == null ? "null" : "\"" + jsonEscape(oldVal) + "\"")
          .append(",\"new\":")
          .append(newVal == null ? "null" : "\"" + jsonEscape(newVal) + "\"")
          .append("}");
        return false;
    }

    private boolean addDiffNum(StringBuilder sb, boolean first, String field, Map<String, Object> old, BigDecimal newVal) {
        Object oldVal = old == null ? null : old.get(snake(field));
        String oldStr = oldVal == null ? null : oldVal.toString();
        String newStr = newVal == null ? null : newVal.toPlainString();
        if (Objects.equals(oldStr, newStr)) return first;
        if (!first) sb.append(",");
        sb.append("\"").append(field).append("\":{\"old\":")
          .append(oldStr == null ? "null" : "\"" + oldStr + "\"")
          .append(",\"new\":")
          .append(newStr == null ? "null" : "\"" + newStr + "\"")
          .append("}");
        return false;
    }

    private boolean addDiffMasked(StringBuilder sb, boolean first, String field, Map<String, Object> old, String newRib) {
        String oldRib = old == null ? null : (String) old.get("rib");
        if (Objects.equals(oldRib, newRib)) return first;
        if (!first) sb.append(",");
        sb.append("\"").append(field).append("\":{\"old\":")
          .append(oldRib == null ? "null" : "\"" + TunisianIds.maskRib(oldRib) + "\"")
          .append(",\"new\":")
          .append(newRib == null ? "null" : "\"" + TunisianIds.maskRib(newRib) + "\"")
          .append("}");
        return false;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private CompanyProfileResponse mapRow(Map<String, Object> r) {
        UUID orgId = (UUID) r.get("org_id");
        String pays = (String) r.get("pays");
        String mf = (String) r.get("matricule_fiscal");
        String rib = (String) r.get("rib");
        boolean isTN = "TN".equals(pays);

        String mfFormate = null;
        if (isTN && mf != null && TunisianIds.validateMf(mf).isEmpty()) {
            mfFormate = TunisianIds.formatMf(mf);
        }

        String ribFormate = null;
        String iban = null;
        if (isTN && rib != null && TunisianIds.validateRib(rib).isEmpty()) {
            ribFormate = TunisianIds.formatRib(rib);
            iban = TunisianIds.formatIban(TunisianIds.ibanFromRib(rib));
        }

        boolean hasLogo = r.get("logo_key") != null;
        int version = ((Number) r.get("version")).intValue();

        return buildResponse(
                orgId, pays,
                (String) r.get("raison_sociale"),
                (String) r.get("nom_commercial"),
                (String) r.get("forme_juridique"),
                mf, mfFormate,
                (String) r.get("rne"),
                r.get("capital") instanceof BigDecimal bd ? bd : null,
                Boolean.TRUE.equals(r.get("assujetti_tva")),
                (String) r.get("adresse"),
                (String) r.get("code_postal"),
                (String) r.get("ville"),
                (String) r.get("gouvernorat"),
                (String) r.get("telephone"),
                (String) r.get("email"),
                (String) r.get("site_web"),
                (String) r.get("banque"),
                rib, ribFormate, iban,
                hasLogo, version, false);
    }

    private CompanyProfileResponse buildResponse(UUID orgId, String pays, String raisonSociale,
            String nomCommercial, String formeJuridique, String mf, String mfFormate,
            String rne, BigDecimal capital, boolean assujetti, String adresse,
            String codePostal, String ville, String gouvernorat, String telephone,
            String email, String siteWeb, String banque, String rib, String ribFormate,
            String iban, boolean hasLogo, int version, boolean isStub) {
        List<String> manquants = new ArrayList<>();
        if (raisonSociale == null || raisonSociale.isBlank()) manquants.add("raison sociale");
        if (mf == null) manquants.add("matricule fiscal");
        if (adresse == null) manquants.add("adresse");
        if (ville == null) manquants.add("ville");
        if (telephone == null) manquants.add("téléphone");
        if (rib == null) manquants.add("RIB");
        return new CompanyProfileResponse(orgId, pays, raisonSociale, nomCommercial,
                formeJuridique, mf, mfFormate, rne, capital, assujetti,
                adresse, codePostal, ville, gouvernorat, telephone, email, siteWeb,
                banque, rib, ribFormate, iban, hasLogo, version,
                manquants, manquants.isEmpty());
    }

    // ══════════════════ VALIDATION EXCEPTION ══════════════════

    /** Thrown when field validation fails. Carries all field errors at once. */
    public static class ValidationException extends RuntimeException {
        private final String code;
        private final Map<String, String> fields;

        public ValidationException(String code, Map<String, String> fields) {
            super("Validation error");
            this.code = code;
            this.fields = fields;
        }

        public String getCode() { return code; }
        public Map<String, String> getFields() { return fields; }
    }
}
