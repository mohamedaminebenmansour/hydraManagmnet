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
 * Groupement service (S1-5 step 4): joint ventures with members and shares.
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

    private void validateInput(GroupementInput body, UUID orgId, UUID existingId) {
        if (body.membres() == null || body.membres().size() < 2) {
            throw new BusinessRuleException("MEMBERS_MIN", "Un groupement compte au moins deux membres.");
        }
        long selfCount = body.membres().stream().filter(m -> Boolean.TRUE.equals(m.isSelf())).count();
        if (selfCount != 1) {
            throw new BusinessRuleException("SELF_REQUIRED", "Indiquez quelle ligne est notre entreprise.");
        }
        long mandataireCount = body.membres().stream().filter(m -> "MANDATAIRE".equals(m.role())).count();
        if (mandataireCount != 1) {
            throw new BusinessRuleException("MANDATAIRE_REQUIRED", "Choisissez un seul mandataire.");
        }
        // Sum of parts = 100
        BigDecimal total = BigDecimal.ZERO;
        for (MemberInput m : body.membres()) {
            if (m.partPct() == null || m.partPct().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessRuleException("INVALID_SHARE", "Chaque part doit être supérieure à 0.");
            }
            total = total.add(m.partPct());
        }
        if (total.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new BusinessRuleException("SHARES_NOT_100",
                    "Le total des parts doit faire 100 % (actuellement " + total.toPlainString() + " %).");
        }
        // Validate member MF when pays = TN
        for (MemberInput m : body.membres()) {
            String pays = m.pays() == null ? "TN" : m.pays();
            if ("TN".equals(pays) && m.matriculeFiscal() != null && !m.matriculeFiscal().isBlank()) {
                var err = TunisianIds.validateMf(m.matriculeFiscal());
                if (err.isPresent()) {
                    throw new BusinessRuleException("INVALID_MF", err.get());
                }
            }
        }
        // Validate project belongs to org
        if (body.projectId() != null) {
            Integer projectExists = jdbc.queryForObject(
                    "select count(*) from project where id = ? and org_id = ?", Integer.class, body.projectId(), orgId);
            if (projectExists == null || projectExists == 0) {
                throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Chantier introuvable");
            }
        }
    }

    // ── save members ────────────────────────────────────────────────────

    private void saveMembers(UUID groupementId, UUID orgId, List<MemberInput> membres, AppUser me) {
        // Get company profile for isSelf member
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
        for (int i = 0; i < membres.size(); i++) {
            MemberInput m = membres.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"nom\":\"").append(jsonEscape(m.raisonSociale() == null ? "" : m.raisonSociale()))
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
