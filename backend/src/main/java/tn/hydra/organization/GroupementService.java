package tn.hydra.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.util.*;

/**
 * Groupement service (S1-5 step 4 + FIX 2): joint ventures with members and shares.
 *
 * validateInput collects ALL field errors into a LinkedHashMap and throws
 * CompanyProfileService.ValidationException("VALIDATION", fields) — exactly
 * like the profile. The controller catches it and returns 400
 * {"code":"VALIDATION","fields":{...}}.
 */
@Service
public class GroupementService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public GroupementService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ══════════════════ RESPONSE ══════════════════

    public record MemberResponse(UUID id, int ordre, boolean isSelf, String raisonSociale,
                                  String matriculeFiscal, String pays, String role,
                                  BigDecimal partPct, String prestations) {}

    public record GroupementResponse(UUID id, String nom, String type, UUID projectId,
                                      String referenceMarche, String notes, int version,
                                      boolean archived, List<MemberResponse> membres) {}

    public record MemberInput(String raisonSociale, String matriculeFiscal, String pays,
                               String role, BigDecimal partPct, String prestations, Boolean isSelf) {}

    public record GroupementInput(String nom, String type, UUID projectId,
                                   String referenceMarche, String notes, int version,
                                   List<MemberInput> membres) {}

    // ══════════════════ GET LIST ══════════════════

    public List<GroupementResponse> list(Authentication auth, boolean includeArchived) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        String sql = includeArchived
                ? "select id from groupement where org_id = ? order by created_at desc"
                : "select id from groupement where org_id = ? and archived_at is null order by created_at desc";
        List<UUID> ids = jdbc.queryForList(sql, UUID.class, me.getOrgId());
        return ids.stream().map(id -> getOne(auth, id)).toList();
    }

    // ══════════════════ GET ONE ══════════════════

    public GroupementResponse getOne(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> row = findGroupement(id, me.getOrgId());
        if (row == null) throw new ResourceNotFoundException("GROUPEMENT_NOT_FOUND", "Groupement introuvable");
        List<MemberResponse> membres = loadMembers(id);
        return toResponse(row, membres);
    }

    // ══════════════════ CREATE ══════════════════

    @Transactional
    public GroupementResponse create(Authentication auth, GroupementInput body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        validateInput(body, me.getOrgId(), null);

        UUID id = UUID.randomUUID();
        // After validation, nom is guaranteed non-null and non-blank
        jdbc.update("""
                insert into groupement (id, org_id, nom, type, project_id, reference_marche, notes, version, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, me.getOrgId(), body.nom().trim(), body.type(), body.projectId(),
                trim(body.referenceMarche()), trim(body.notes()), me.getId(), me.getId());

        saveMembers(id, me.getOrgId(), body.membres(), me);
        auditGroupement(me.getOrgId(), me, "GROUPEMENT_CREATED", id, body, body.membres());
        return getOne(auth, id);
    }

    // ══════════════════ UPDATE ══════════════════

    @Transactional
    public GroupementResponse update(Authentication auth, UUID id, GroupementInput body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> existing = findGroupement(id, me.getOrgId());
        if (existing == null) throw new ResourceNotFoundException("GROUPEMENT_NOT_FOUND", "Groupement introuvable");
        if (existing.get("archived_at") != null)
            throw new BusinessRuleException("GROUPEMENT_ARCHIVED", "Ce groupement est archivé.");

        int oldVersion = ((Number) existing.get("version")).intValue();
        if (body.version() != oldVersion)
            throw new BusinessRuleException("STALE_GROUPEMENT",
                    "Le groupement a été modifié par quelqu'un d'autre. Rechargez la page.");

        validateInput(body, me.getOrgId(), id);

        int updated = jdbc.update("""
                update groupement set nom = ?, type = ?, project_id = ?, reference_marche = ?, notes = ?,
                    version = version + 1, updated_at = now(), updated_by = ?
                where id = ? and org_id = ? and version = ?
                """, body.nom().trim(), body.type(), body.projectId(),
                trim(body.referenceMarche()), trim(body.notes()), me.getId(),
                id, me.getOrgId(), oldVersion);
        if (updated == 0)
            throw new BusinessRuleException("STALE_GROUPEMENT",
                    "Le groupement a été modifié par quelqu'un d'autre. Rechargez la page.");

        // Replace members
        jdbc.update("delete from groupement_member where groupement_id = ?", id);
        saveMembers(id, me.getOrgId(), body.membres(), me);
        auditGroupement(me.getOrgId(), me, "GROUPEMENT_UPDATED", id, body, body.membres());
        return getOne(auth, id);
    }

    // ══════════════════ ARCHIVE ══════════════════

    @Transactional
    public void archive(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> existing = findGroupement(id, me.getOrgId());
        if (existing == null) throw new ResourceNotFoundException("GROUPEMENT_NOT_FOUND", "Groupement introuvable");
        jdbc.update("update groupement set archived_at = now(), updated_by = ? where id = ? and org_id = ?",
                me.getId(), id, me.getOrgId());
        audit.log("GROUPEMENT_ARCHIVED", "groupement", id,
                "{\"nom\":\"" + jsonEscape((String) existing.get("nom")) + "\"}");
    }

    // ── validation ──────────────────────────────────────────────────────
    //
    // Collects ALL field errors into a LinkedHashMap (insertion order preserved)
    // and throws CompanyProfileService.ValidationException("VALIDATION", fields).
    // Never calls body.nom().trim() before this method returns successfully:
    // null nom is reported as the "nom" field error and the method throws before
    // any trim is attempted in create/update.
    private void validateInput(GroupementInput body, UUID orgId, UUID existingId) {
        Map<String, String> errors = new LinkedHashMap<>();

        // nom
        if (body.nom() == null || body.nom().trim().isEmpty()) {
            errors.put("nom", "Le nom du groupement est obligatoire.");
        }

        // type
        if (!"CONJOINT".equals(body.type()) && !"SOLIDAIRE".equals(body.type())) {
            errors.put("type", "Choisissez Conjoint ou Solidaire.");
        }

        // membres structural checks (members min / self / mandataire)
        boolean enoughMembers = body.membres() != null && body.membres().size() >= 2;
        if (!enoughMembers) {
            errors.put("membres", "Un groupement compte au moins deux membres.");
        } else {
            long selfCount = body.membres().stream().filter(m -> Boolean.TRUE.equals(m.isSelf())).count();
            if (selfCount != 1) {
                errors.put("membres", "Indiquez quelle ligne est notre entreprise.");
            }
            long mandataireCount = body.membres().stream().filter(m -> "MANDATAIRE".equals(m.role())).count();
            if (mandataireCount != 1) {
                errors.put("membres", "Choisissez un seul mandataire.");
            }
        }

        // parts: sum to 100 — only compute when we have ≥ 2 members, otherwise the
        // structural error already covers it.
        if (enoughMembers) {
            BigDecimal total = BigDecimal.ZERO;
            for (MemberInput m : body.membres()) {
                if (m.partPct() != null && m.partPct().compareTo(BigDecimal.ZERO) > 0) {
                    total = total.add(m.partPct());
                }
            }
            if (total.compareTo(BigDecimal.valueOf(100)) != 0) {
                errors.put("parts", "Le total des parts doit faire 100 % (actuellement "
                        + formatShare(total) + " %).");
            }
        }

        // Per-member field errors (indexed by 0-based position in the list sent).
        if (body.membres() != null) {
            for (int i = 0; i < body.membres().size(); i++) {
                MemberInput m = body.membres().get(i);
                String prefix = "membres[" + i + "]";

                // raisonSociale (non-self only)
                if (!Boolean.TRUE.equals(m.isSelf())) {
                    if (m.raisonSociale() == null || m.raisonSociale().trim().isEmpty()) {
                        errors.put(prefix + ".raisonSociale",
                                "La raison sociale du membre est obligatoire.");
                    }
                }

                // role
                if (!"MANDATAIRE".equals(m.role()) && !"MEMBRE".equals(m.role())) {
                    errors.put(prefix + ".role", "Rôle invalide.");
                }

                // partPct > 0, scale ≤ 2
                if (m.partPct() == null || m.partPct().compareTo(BigDecimal.ZERO) <= 0) {
                    errors.put(prefix + ".partPct",
                            "La part doit être supérieure à 0, avec 2 décimales au plus.");
                } else if (m.partPct().scale() > 2) {
                    errors.put(prefix + ".partPct",
                            "La part doit être supérieure à 0, avec 2 décimales au plus.");
                }

                // matriculeFiscal when pays = TN
                String pays = m.pays() == null ? "TN" : m.pays();
                if ("TN".equals(pays) && m.matriculeFiscal() != null && !m.matriculeFiscal().isBlank()) {
                    var err = TunisianIds.validateMf(m.matriculeFiscal());
                    if (err.isPresent()) {
                        errors.put(prefix + ".matriculeFiscal", err.get());
                    }
                }
            }
        }

        // Project not in my org stays 404 (separate from field errors).
        if (body.projectId() != null) {
            Integer projectExists = jdbc.queryForObject(
                    "select count(*) from project where id = ? and org_id = ?",
                    Integer.class, body.projectId(), orgId);
            if (projectExists == null || projectExists == 0) {
                throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Chantier introuvable");
            }
        }

        if (!errors.isEmpty()) {
            throw new CompanyProfileService.ValidationException("VALIDATION", errors);
        }
    }

    /** Format a share for the error message: 90.00 → "90", 33.33 → "33,33". */
    private static String formatShare(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        String s = stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
        return s.replace('.', ',');
    }

    // ── save members ────────────────────────────────────────────────────

    private void saveMembers(UUID groupementId, UUID orgId, List<MemberInput> membres, AppUser me) {
        // Get company profile for isSelf member (used for both the saved name and the audit)
        String selfRaison = null;
        String selfMf = null;
        List<Map<String, Object>> profileRows = jdbc.queryForList(
                "select raison_sociale, matricule_fiscal from company_profile where org_id = ?", orgId);
        if (!profileRows.isEmpty()) {
            selfRaison = (String) profileRows.get(0).get("raison_sociale");
            selfMf = (String) profileRows.get(0).get("matricule_fiscal");
        }
        if (selfRaison == null) {
            selfRaison = jdbc.queryForObject("select name from organization where id = ?", String.class, orgId);
        }

        int ordre = 1;
        for (MemberInput m : membres) {
            boolean isSelf = Boolean.TRUE.equals(m.isSelf());
            // FIX 2b: for isSelf, write the name actually saved (from the profile), not the client value.
            String raison = isSelf ? selfRaison : (m.raisonSociale() != null ? m.raisonSociale().trim() : "");
            String mf = isSelf ? selfMf : trim(m.matriculeFiscal());
            if ("TN".equals(m.pays() == null ? "TN" : m.pays()) && mf != null) {
                mf = TunisianIds.normalizeMf(mf);
            }
            jdbc.update("""
                    insert into groupement_member (id, org_id, groupement_id, ordre, is_self, raison_sociale,
                        matricule_fiscal, pays, role, part_pct, prestations)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), orgId, groupementId, ordre++, isSelf,
                    raison, mf, m.pays() == null ? "TN" : m.pays(),
                    m.role(), m.partPct(), trim(m.prestations()));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> findGroupement(UUID id, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from groupement where id = ? and org_id = ?", id, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<MemberResponse> loadMembers(UUID groupementId) {
        return jdbc.queryForList(
                "select * from groupement_member where groupement_id = ? order by ordre", groupementId)
                .stream().map(r -> new MemberResponse(
                        (UUID) r.get("id"), ((Number) r.get("ordre")).intValue(),
                        Boolean.TRUE.equals(r.get("is_self")), (String) r.get("raison_sociale"),
                        (String) r.get("matricule_fiscal"), (String) r.get("pays"),
                        (String) r.get("role"),
                        r.get("part_pct") instanceof BigDecimal bd ? bd : null,
                        (String) r.get("prestations"))).toList();
    }

    private GroupementResponse toResponse(Map<String, Object> row, List<MemberResponse> membres) {
        return new GroupementResponse(
                (UUID) row.get("id"), (String) row.get("nom"), (String) row.get("type"),
                (UUID) row.get("project_id"), (String) row.get("reference_marche"),
                (String) row.get("notes"), ((Number) row.get("version")).intValue(),
                row.get("archived_at") != null, membres);
    }

    private void auditGroupement(UUID orgId, AppUser me, String action, UUID id,
                                  GroupementInput body, List<MemberInput> membres) {
        StringBuilder sb = new StringBuilder("{\"nom\":\"" + jsonEscape(body.nom()) + "\",\"type\":\"" + body.type() + "\",\"membres\":[");
        // FIX 2b: for the isSelf member write the name actually saved (from the profile),
        // not the client value. For non-self, write the client value as before.
        String selfRaison = jdbc.queryForObject(
                "select coalesce((select raison_sociale from company_profile where org_id = ?), "
                        + "(select name from organization where id = ?))",
                String.class, orgId, orgId);
        for (int i = 0; i < membres.size(); i++) {
            MemberInput m = membres.get(i);
            if (i > 0) sb.append(",");
            String name = Boolean.TRUE.equals(m.isSelf()) ? selfRaison : m.raisonSociale();
            sb.append("{\"nom\":\"").append(jsonEscape(name == null ? "" : name))
              .append("\",\"part\":").append(m.partPct()).append("}");
        }
        sb.append("]}");
        audit.log(action, "groupement", id, sb.toString());
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
