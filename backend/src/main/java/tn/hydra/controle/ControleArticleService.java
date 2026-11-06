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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ControleArticleService — « Regroupement des articles ».
 *
 * <p>GET lists the raw Excel texts (ctl_line.raw_key) of my org with their article.
 * PUT saves an alias raw_key → article and, in the SAME transaction, sets the
 * article of every ctl_line with that raw_key. Version rule like the justifications:
 * version 0 → insert (version 1); version ≥ 1 → update where version = ?;
 * 0 rows → 409 STALE.
 */
@Service
public class ControleArticleService {

    private static final int MAX_ARTICLE = 80;

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public ControleArticleService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ── Records ───────────────────────────────────────────────────────────────

    /** version = alias version, 0 when the article comes from the rule. */
    public record ArticleRow(String rawKey, String sampleText, String article, int lineCount, int version) {}

    public record AliasBody(String rawKey, String article, int version) {}

    // ── GET ───────────────────────────────────────────────────────────────────

    public List<ArticleRow> listArticles(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return loadRows(me.getOrgId(), null);
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    @Transactional
    public ArticleRow putAlias(Authentication auth, AliasBody body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        UUID orgId = me.getOrgId();

        String article = body.article() == null ? "" : body.article().trim();
        Map<String, String> errors = new LinkedHashMap<>();
        if (article.isEmpty()) {
            errors.put("article", "Saisissez l'article.");
        } else if (article.length() > MAX_ARTICLE) {
            errors.put("article", MAX_ARTICLE + " caractères au plus.");
        }
        if (!errors.isEmpty()) {
            throw new CompanyProfileService.ValidationException("VALIDATION", errors);
        }

        // The raw_key must exist in ctl_line of MY org
        String rawKey = body.rawKey() == null ? "" : body.rawKey();
        Integer n = jdbc.queryForObject(
                "select count(*) from ctl_line where org_id = ? and raw_key = ?", Integer.class, orgId, rawKey);
        if (rawKey.isEmpty() || n == null || n == 0) {
            throw new ResourceNotFoundException("RAW_KEY_NOT_FOUND", "Texte introuvable.");
        }

        int saved;
        if (body.version() == 0) {
            saved = jdbc.update("""
                    insert into ctl_article_alias (org_id, raw_key, article, version, updated_by, updated_at)
                    values (?, ?, ?, 1, ?, now())
                    on conflict (org_id, raw_key) do nothing
                    """, orgId, rawKey, article, me.getId());
        } else {
            saved = jdbc.update("""
                    update ctl_article_alias set article = ?, version = version + 1, updated_by = ?, updated_at = now()
                    where org_id = ? and raw_key = ? and version = ?
                    """, article, me.getId(), orgId, rawKey, body.version());
        }
        if (saved == 0) {
            throw new BusinessRuleException("STALE",
                    "Quelqu'un d'autre a modifié ce regroupement. Rechargez.");
        }

        int lines = jdbc.update("update ctl_line set article = ? where org_id = ? and raw_key = ?",
                article, orgId, rawKey);

        audit.log("CONTROLE_ARTICLE_CHANGED", "ctl_article_alias", null,
                "{\"rawKey\":\"" + jsonEscape(rawKey) + "\",\"article\":\"" + jsonEscape(article)
                + "\",\"lines\":" + lines + "}");

        return loadRows(orgId, rawKey).get(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** One row per raw_key of the org (or only {@code rawKey} when not null), most lines first. */
    private List<ArticleRow> loadRows(UUID orgId, String rawKey) {
        String sql = """
                select l.raw_key,
                       count(*) as line_count,
                       mode() within group (order by l.category_excel) as sample_text,
                       mode() within group (order by l.article) as line_article,
                       a.article as alias_article,
                       a.version as alias_version
                from ctl_line l
                left join ctl_article_alias a on a.org_id = l.org_id and a.raw_key = l.raw_key
                where l.org_id = ?
                """ + (rawKey == null ? "" : " and l.raw_key = ?") + """

                group by l.raw_key, a.article, a.version
                order by line_count desc, l.raw_key
                """;
        Object[] args = rawKey == null ? new Object[]{orgId} : new Object[]{orgId, rawKey};
        return jdbc.query(sql, (rs, i) -> new ArticleRow(
                rs.getString("raw_key"),
                rs.getString("sample_text"),
                rs.getString("alias_article") != null ? rs.getString("alias_article") : rs.getString("line_article"),
                rs.getInt("line_count"),
                rs.getInt("alias_version")), args);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
