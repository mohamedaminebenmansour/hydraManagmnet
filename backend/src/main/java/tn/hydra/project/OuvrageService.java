package tn.hydra.project;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OuvrageService — CRUD for the structured objects of a chantier.
 *
 * <p>Permission model mirrors {@link ProjectService}:
 * <ul>
 *   <li><b>Read</b> ({@code list}, {@code get}) — owner, company-wide readers,
 *       or project member. Same check as {@code ProjectService.requireReadable}.</li>
 *   <li><b>Write</b> ({@code create}, {@code update}, {@code delete}) —
 *       same as updating a project: owner, PROJECT_WRITE / PROJECT_ASSIGN,
 *       or project ADMIN/MANAGER. Same check as
 *       {@code ProjectService.requireManageable}.</li>
 * </ul>
 *
 * <p>Every write is audited via {@link EventLogService} with the ouvrage id,
 * the action (OUVRAGE_CREATED / OUVRAGE_UPDATED / OUVRAGE_DELETED) and a JSON
 * details blob carrying the type and name.
 *
 * <p>A single COMMUN ouvrage is auto-created for every chantier (see
 * {@link #ensureCommunOuvrage}) — installation et repli, plans d'exécution,
 * essais globaux, raccordement au réseau existant, sécurité. Called from
 * {@link ProjectService#create} right after the project + folders + members
 * are seeded.
 */
@Service
public class OuvrageService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final ProjectService projects;
    private final EventLogService audit;
    private final ObjectMapper json = new ObjectMapper();

    public OuvrageService(JdbcTemplate jdbc, AppUserRepository users,
                          ProjectService projects, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.projects = projects; this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════════════
    // RESPONSE SHAPE
    // ═══════════════════════════════════════════════════════════════

    public record OuvrageResponse(UUID id, UUID projectId, String type, String code,
                                  String name, Map<String, Object> characteristics,
                                  Integer sortOrder, Instant createdAt) {}

    public record CreateOuvrageRequest(String type, String code, String name,
                                       Map<String, Object> characteristics, Integer sortOrder) {}

    public record UpdateOuvrageRequest(String code, String name,
                                       Map<String, Object> characteristics, Integer sortOrder) {}

    // ═══════════════════════════════════════════════════════════════
    // 1. LIST
    // ═══════════════════════════════════════════════════════════════

    public List<OuvrageResponse> list(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireReadableForOuvrage(auth, me, projectId);
        return jdbc.queryForList("""
                select id, project_id, type, code, name, characteristics, sort_order, created_at
                from ouvrage
                where org_id = ? and project_id = ?
                order by sort_order, name
                """, me.getOrgId(), projectId).stream()
                .map(OuvrageService::toResponse).toList();
    }

    public OuvrageResponse get(Authentication auth, UUID projectId, UUID ouvrageId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireReadableForOuvrage(auth, me, projectId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, project_id, type, code, name, characteristics, sort_order, created_at
                from ouvrage
                where id = ? and org_id = ? and project_id = ?
                """, ouvrageId, me.getOrgId(), projectId);
        return rows.isEmpty() ? null : toResponse(rows.get(0));
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. CREATE
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public OuvrageResponse create(Authentication auth, UUID projectId, CreateOuvrageRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);

        if (body.type() == null || body.type().isBlank())
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le type d'ouvrage est obligatoire");
        if (body.name() == null || body.name().isBlank())
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom de l'ouvrage est obligatoire");

        OuvrageType type;
        try {
            type = OuvrageType.valueOf(body.type().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_OUVRAGE_TYPE",
                    "Type d'ouvrage inconnu : " + body.type());
        }

        // COMMUN is created automatically by ensureCommunOuvrage — reject manual
        // creation so the per-project unique index can never be violated by the
        // user trying to add a second COMMUN.
        if (type == OuvrageType.COMMUN)
            throw new BusinessRuleException("COMMUN_AUTO",
                    "L'ouvrage COMMUN est créé automatiquement pour chaque chantier.");

        Map<String, Object> characteristics = validateCharacteristics(type, body.characteristics());

        UUID id = jdbc.queryForObject("""
                insert into ouvrage (org_id, project_id, type, code, name, characteristics, sort_order, created_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                returning id
                """, UUID.class,
                me.getOrgId(), projectId, type.name(),
                blankToNull(body.code()), body.name().trim(),
                toJson(characteristics), body.sortOrder(), me.getId());

        audit.log("OUVRAGE_CREATED", "ouvrage", id,
                "{\"projectId\":\"" + projectId + "\",\"type\":\"" + type.name()
                        + "\",\"name\":\"" + jsonEscape(body.name().trim()) + "\"}");
        return get(auth, projectId, id);
    }

    /**
     * Create an ouvrage inside an already-open transaction (used by
     * {@link ProjectService#create} when the chantier request carries an
     * optional list of ouvrages). Does NOT re-check permissions — the caller
     * is already authenticated and is creating the chantier itself.
     *
     * <p>Validates the type and characteristics the same way the standalone
     * endpoint does, so an invalid ouvrage rejects the whole chantier
     * (the @Transactional on ProjectService.create rolls back everything).
     *
     * @param ouvrageIndex 1-based index of the ouvrage in the request list,
     *    used to build a clear error message ("ouvrage n°2 : champ …")
     * @param createdBy the user creating the chantier
     * @return the new ouvrage id
     */
    UUID createInTransaction(UUID projectId, UUID orgId, UUID createdBy,
                             CreateOuvrageRequest body, int ouvrageIndex) {
        if (body.type() == null || body.type().isBlank())
            throw new BusinessRuleException("REQUIRED_FIELDS",
                    "Ouvrage n°" + ouvrageIndex + " : le type d'ouvrage est obligatoire");
        if (body.name() == null || body.name().isBlank())
            throw new BusinessRuleException("REQUIRED_FIELDS",
                    "Ouvrage n°" + ouvrageIndex + " : le nom de l'ouvrage est obligatoire");

        OuvrageType type;
        try {
            type = OuvrageType.valueOf(body.type().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_OUVRAGE_TYPE",
                    "Ouvrage n°" + ouvrageIndex + " : type d'ouvrage inconnu : " + body.type());
        }
        if (type == OuvrageType.COMMUN)
            throw new BusinessRuleException("COMMUN_AUTO",
                    "Ouvrage n°" + ouvrageIndex
                            + " : l'ouvrage COMMUN est créé automatiquement pour chaque chantier.");

        Map<String, Object> characteristics;
        try {
            characteristics = validateCharacteristics(type, body.characteristics());
        } catch (BusinessRuleException e) {
            // Prefix the error message with the ouvrage index so the user
            // knows WHICH ouvrage in the list is wrong.
            throw new BusinessRuleException(e.getCode(),
                    "Ouvrage n°" + ouvrageIndex + " (" + typeLabel(type) + ") : " + e.getMessage());
        }

        UUID id = jdbc.queryForObject("""
                insert into ouvrage (org_id, project_id, type, code, name, characteristics, sort_order, created_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                returning id
                """, UUID.class,
                orgId, projectId, type.name(),
                blankToNull(body.code()), body.name().trim(),
                toJson(characteristics), body.sortOrder(), createdBy);

        audit.log("OUVRAGE_CREATED", "ouvrage", id,
                "{\"projectId\":\"" + projectId + "\",\"type\":\"" + type.name() + "\",\"name\":\"" + jsonEscape(body.name().trim()) + "\",\"auto\":false}");
        return id;
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. UPDATE
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public OuvrageResponse update(Authentication auth, UUID projectId, UUID ouvrageId,
                                   UpdateOuvrageRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);

        if (body.name() != null && body.name().isBlank())
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom de l'ouvrage ne peut pas être vide");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select type, characteristics from ouvrage
                where id = ? and org_id = ? and project_id = ?
                """, ouvrageId, me.getOrgId(), projectId);
        if (rows.isEmpty())
            throw new BusinessRuleException("OUVRAGE_NOT_FOUND", "Ouvrage introuvable");

        OuvrageType type = OuvrageType.valueOf((String) rows.get(0).get("type"));

        // Reject type change — the allowed characteristics depend on the type,
        // so changing the type silently would orphan or invalidate keys. The
        // user must delete + recreate to change the type.
        // Characteristics: if provided, validate against the type spec; if null,
        // keep the existing ones unchanged.
        Map<String, Object> characteristics = body.characteristics() == null
                ? parseJson(rows.get(0).get("characteristics"))
                : validateCharacteristics(type, body.characteristics());

        jdbc.update("""
                update ouvrage set
                    code = coalesce(?, code),
                    name = coalesce(?, name),
                    characteristics = ?::jsonb,
                    sort_order = coalesce(?, sort_order)
                where id = ? and org_id = ? and project_id = ?
                """,
                blankToNull(body.code()),
                body.name() == null ? null : body.name().trim(),
                toJson(characteristics),
                body.sortOrder(),
                ouvrageId, me.getOrgId(), projectId);

        audit.log("OUVRAGE_UPDATED", "ouvrage", ouvrageId,
                "{\"projectId\":\"" + projectId + "\",\"type\":\"" + type.name() + "\"}");
        return get(auth, projectId, ouvrageId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. DELETE
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public void delete(Authentication auth, UUID projectId, UUID ouvrageId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select type from ouvrage
                where id = ? and org_id = ? and project_id = ?
                """, ouvrageId, me.getOrgId(), projectId);
        if (rows.isEmpty())
            throw new BusinessRuleException("OUVRAGE_NOT_FOUND", "Ouvrage introuvable");

        String type = (String) rows.get(0).get("type");
        // Don't allow deleting the auto-created COMMUN — the chantier must always
        // have one. Use update if you want to rename it.
        if ("COMMUN".equals(type))
            throw new BusinessRuleException("COMMUN_AUTO",
                    "L'ouvrage COMMUN est obligatoire pour chaque chantier et ne peut pas être supprimé.");

        jdbc.update("delete from ouvrage where id = ? and org_id = ? and project_id = ?",
                ouvrageId, me.getOrgId(), projectId);

        audit.log("OUVRAGE_DELETED", "ouvrage", ouvrageId,
                "{\"projectId\":\"" + projectId + "\",\"type\":\"" + type + "\"}");
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. COMMUN AUTO-CREATION (called by ProjectService.create)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ensure the chantier has exactly one COMMUN ouvrage. Idempotent — if one
     * already exists (e.g. created by a previous call), do nothing. Called by
     * {@link ProjectService#create} right after folders + members are seeded.
     *
     * <p>The COMMUN ouvrage carries the items that belong to the whole
     * chantier rather than to a specific ouvrage: installation et repli,
     * plans d'exécution, essais globaux, raccordement au réseau existant,
     * sécurité. See BORDEREAU-ANALYSIS §5 (COMMUN: no characteristics).
     */
    @Transactional
    public void ensureCommunOuvrage(UUID projectId, UUID orgId, UUID createdBy) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from ouvrage where project_id = ? and org_id = ? and type = 'COMMUN'",
                Integer.class, projectId, orgId);
        if (existing != null && existing > 0) return;

        UUID id = jdbc.queryForObject("""
                insert into ouvrage (org_id, project_id, type, code, name, characteristics, sort_order, created_by)
                values (?, ?, 'COMMUN', 'COMMUN', 'Commun', '{}'::jsonb, 0, ?)
                returning id
                """, UUID.class, orgId, projectId, createdBy);

        audit.log("OUVRAGE_CREATED", "ouvrage", id,
                "{\"projectId\":\"" + projectId + "\",\"type\":\"COMMUN\",\"name\":\"Commun\",\"auto\":true}");
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. ouvrageSummary — added to ProjectResponse
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a short French summary of the ouvrages of a chantier, e.g.
     * "2 réservoirs · 1 conduite 12 km · 1 station de pompage · 1 forage · commun".
     * Returns an empty string when the project has no ouvrages yet (rare,
     * since COMMUN is auto-created).
     *
     * <p>For CONDUITE, the total length is computed by summing the
     * {@code longueur_m} of every tronçon across every CONDUITE ouvrage.
     * Lengths ≥ 1 000 m are shown in km with one decimal.
     */
    public String ouvrageSummary(UUID projectId, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select type, characteristics from ouvrage
                where project_id = ? and org_id = ?
                order by sort_order, name
                """, projectId, orgId);
        if (rows.isEmpty()) return "";

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("RESERVOIR", 0L); counts.put("CONDUITE", 0L);
        counts.put("STATION_POMPAGE", 0L); counts.put("FORAGE", 0L);
        counts.put("OUVRAGE_ANNEXE", 0L); counts.put("BRANCHEMENTS", 0L);
        counts.put("ASSAINISSEMENT", 0L); counts.put("COMMUN", 0L); counts.put("AUTRE", 0L);

        double conduiteTotalM = 0.0;
        boolean hasConduite = false;
        for (Map<String, Object> r : rows) {
            String t = (String) r.get("type");
            counts.merge(t, 1L, Long::sum);
            if ("CONDUITE".equals(t)) {
                hasConduite = true;
                Map<String, Object> chars = parseJson(r.get("characteristics"));
                // Sum troncons[*].longueur_m if present, else the top-level longueur_m.
                Object troncons = chars.get("troncons");
                if (troncons instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> tr) {
                            Object l = tr.get("longueur_m");
                            if (l instanceof Number n) conduiteTotalM += n.doubleValue();
                        }
                    }
                } else {
                    Object l = chars.get("longueur_m");
                    if (l instanceof Number n) conduiteTotalM += n.doubleValue();
                }
            }
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() <= 0) continue;
            String label = frenchLabel(e.getKey(), e.getValue());
            if ("CONDUITE".equals(e.getKey()) && hasConduite) {
                if (conduiteTotalM >= 1000.0) {
                    label += " " + formatKm(conduiteTotalM);
                } else if (conduiteTotalM > 0) {
                    label += " " + Math.round(conduiteTotalM) + " m";
                }
            }
            parts.add(label);
        }
        return String.join(" · ", parts);
    }

    private static String frenchLabel(String type, long count) {
        return switch (type) {
            case "RESERVOIR"       -> count + (count > 1 ? " réservoirs" : " réservoir");
            case "CONDUITE"        -> count + (count > 1 ? " conduites" : " conduite");
            case "STATION_POMPAGE" -> count + (count > 1 ? " stations de pompage" : " station de pompage");
            case "FORAGE"          -> count + (count > 1 ? " forages" : " forage");
            case "OUVRAGE_ANNEXE"  -> count + (count > 1 ? " ouvrages annexes" : " ouvrage annexe");
            case "BRANCHEMENTS"    -> count + (count > 1 ? " branchements" : " branchement");
            case "ASSAINISSEMENT"  -> count + (count > 1 ? " assainissements" : " assainissement");
            case "COMMUN"          -> "commun";
            case "AUTRE"           -> count + (count > 1 ? " autres" : " autre");
            default -> type.toLowerCase();
        };
    }

    private static String formatKm(double metres) {
        double km = metres / 1000.0;
        // 12.3 km, 1.5 km — one decimal is enough for a summary.
        return String.format(java.util.Locale.FRANCE, "%.1f km", km);
    }

    // ═══════════════════════════════════════════════════════════════
    // CHARACTERISTICS VALIDATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Validate the characteristics map against the spec for the given type.
     * Rejects unknown keys with a French BusinessRuleException. Returns a
     * clean map (drops null values, coerces numbers where the spec allows).
     *
     * <p>AUTRE has an empty spec, so any key is accepted (free-form).
     */
    private Map<String, Object> validateCharacteristics(OuvrageType type, Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, OuvrageType.Spec> spec = type.characteristicsSpec();
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val == null) continue; // drop null values silently

            // AUTRE: free-form, accept anything (skip validation).
            if (type == OuvrageType.AUTRE) {
                cleaned.put(key, val);
                continue;
            }

            OuvrageType.Spec s = spec.get(key);
            if (s == null)
                throw new BusinessRuleException("UNKNOWN_CHARACTERISTIC",
                        "Caractéristique inconnue pour un ouvrage de type "
                                + typeLabel(type) + " : « " + key + " ».");
            cleaned.put(key, validateValue(type, key, s, val));
        }
        return cleaned;
    }

    private Object validateValue(OuvrageType type, String key, OuvrageType.Spec spec, Object val) {
        return switch (spec.kind()) {
            case NUMBER_WITH_UNIT -> {
                Number n = asNumber(val, type, key);
                yield n;
            }
            case INTEGER -> {
                Number n = asInteger(val, type, key);
                yield n;
            }
            case BOOLEAN -> {
                if (val instanceof Boolean b) yield b;
                if (val instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")))
                    yield Boolean.parseBoolean(s);
                throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                        "La caractéristique « " + key + " » doit être un booléen (vrai/faux).");
            }
            case CHOICE -> {
                String s = val.toString().trim();
                if (!spec.choices().contains(s))
                    throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                            "La caractéristique « " + key + " » doit être l'une de : "
                                    + String.join(", ", spec.choices()) + ".");
                yield s;
            }
            case TEXT -> {
                String s = val.toString().trim();
                if (s.isBlank())
                    throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                            "La caractéristique « " + key + " » ne peut pas être vide.");
                yield s;
            }
            case TRONCONS_ARRAY -> {
                // Only CONDUITE has troncons — a JSON array of {de_mm, pn_bar, longueur_m}.
                if (!(val instanceof List<?> list))
                    throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                            "La caractéristique « troncons » doit être une liste de tronçons "
                                    + "(de_mm, pn_bar, longueur_m).");
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                int i = 0;
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m))
                        throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                                "Le tronçon #" + (i + 1) + " doit être un objet "
                                        + "(de_mm, pn_bar, longueur_m).");
                    ObjectNode tr = JsonNodeFactory.instance.objectNode();
                    tr.put("de_mm", requireInt(m.get("de_mm"), "tronçon #" + (i + 1) + " de_mm", type, key));
                    int pn = requireInt(m.get("pn_bar"), "tronçon #" + (i + 1) + " pn_bar", type, key);
                    if (pn != 10 && pn != 16)
                        throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                                "Le tronçon #" + (i + 1) + " a un pn_bar invalide (10 ou 16).");
                    tr.put("pn_bar", pn);
                    tr.put("longueur_m", requireNumber(m.get("longueur_m"),
                            "tronçon #" + (i + 1) + " longueur_m", type, key));
                    arr.add(tr);
                    i++;
                }
                // Return the ArrayNode so it serializes cleanly via Jackson in the response.
                yield arr;
            }
        };
    }

    private Number asNumber(Object val, OuvrageType type, String key) {
        if (val instanceof Number n) return n;
        if (val instanceof String s) {
            try { return Double.parseDouble(s.trim()); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                "La caractéristique « " + key + " » doit être un nombre.");
    }

    private Number asInteger(Object val, OuvrageType type, String key) {
        if (val instanceof Number n) {
            double d = n.doubleValue();
            if (d != Math.floor(d))
                throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                        "La caractéristique « " + key + " » doit être un entier.");
            return n.intValue();
        }
        if (val instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                "La caractéristique « " + key + " » doit être un entier.");
    }

    private int requireInt(Object val, String label, OuvrageType type, String key) {
        if (val == null)
            throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                    "Le champ " + label + " est obligatoire.");
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                "Le champ " + label + " doit être un entier.");
    }

    private double requireNumber(Object val, String label, OuvrageType type, String key) {
        if (val == null)
            throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                    "Le champ " + label + " est obligatoire.");
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s.trim()); }
            catch (NumberFormatException ignored) { /* fall through */ }
        }
        throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                "Le champ " + label + " doit être un nombre.");
    }

    private static String typeLabel(OuvrageType type) {
        return switch (type) {
            case RESERVOIR -> "réservoir";
            case CONDUITE -> "conduite";
            case STATION_POMPAGE -> "station de pompage";
            case FORAGE -> "forage";
            case OUVRAGE_ANNEXE -> "ouvrage annexe";
            case BRANCHEMENTS -> "branchements";
            case ASSAINISSEMENT -> "assainissement";
            case COMMUN -> "commun";
            case AUTRE -> "autre";
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ═══════════════════════════════════════════════════════════════

    private String toJson(Map<String, Object> map) {
        try { return json.writeValueAsString(map); }
        catch (JacksonException e) {
            throw new BusinessRuleException("INVALID_CHARACTERISTIC",
                    "Les caractéristiques ne sont pas sérialisables : " + e.getMessage());
        }
    }

    /** jsonb column value (PGobject from JdbcTemplate, or text) → Map. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(Object v) {
        String s = v == null ? null : v.toString();
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try {
            JsonNode node = json.readTree(s);
            if (!node.isObject()) return new LinkedHashMap<>();
            return json.convertValue(node, Map.class);
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ROW → RESPONSE
    // ═══════════════════════════════════════════════════════════════

    private static OuvrageResponse toResponse(Map<String, Object> row) {
        return new OuvrageResponse(
                (UUID) row.get("id"),
                (UUID) row.get("project_id"),
                (String) row.get("type"),
                (String) row.get("code"),
                (String) row.get("name"),
                parseCharacteristics(row.get("characteristics")),
                row.get("sort_order") == null ? null : ((Number) row.get("sort_order")).intValue(),
                toInstant(row.get("created_at")));
    }

    /** jsonb column value (PGobject from JdbcTemplate, or text) → Map. A (String) cast
     *  on a PGobject threw ClassCastException → 500 on GET /ouvrages. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseCharacteristics(Object v) {
        String s = v == null ? null : v.toString();
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try {
            ObjectMapper m = new ObjectMapper();
            JsonNode node = m.readTree(s);
            if (!node.isObject()) return new LinkedHashMap<>();
            return m.convertValue(node, Map.class);
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
