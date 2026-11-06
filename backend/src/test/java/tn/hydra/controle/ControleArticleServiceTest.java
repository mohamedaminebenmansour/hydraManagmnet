package tn.hydra.controle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ControleArticleServiceTest — CA-03..CA-06.
 * Builds a small fake import in code (no real data).
 */
@SpringBootTest
@Transactional
@Rollback
class ControleArticleServiceTest {

    private static final String RSE_KEY = "rse 150 d";

    @Autowired private ControleArticleService articleService;
    @Autowired private ControleArticleController articleController;
    @Autowired private ControleImportService importService;
    @Autowired private ControleQueryService queryService;
    @Autowired private JdbcTemplate jdbc;

    private Authentication authA;

    @BeforeEach
    void provision() {
        authA = newOrgOwner("ca.a");
        SecurityContextHolder.getContext().setAuthentication(authA);
        importService.importParsed(authA, fakeFile(List.of()));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private Authentication newOrgOwner(String prefix) {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String email = prefix + "." + userId + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "CA Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, 'x', 'Owner', true, true, false)
                """, userId, orgId, email);
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_READ"),
                        new SimpleGrantedAuthority("PERM_CTL_JUSTIFY"),
                        new SimpleGrantedAuthority("PERM_CTL_IMPORT")));
    }

    private static ControleImportFile.Line line(String id, int row, String cat) {
        return new ControleImportFile.Line(id, "Caisse", row, "2024-03-01", "c1", "Projet Factice",
                0, 100, "especes", "", "", cat, "Ligne factice", "", "", "sortie", "", List.of(), null);
    }

    /** Two lines with the same raw_key « rse 150 d », one « BC », plus {@code extra}. */
    private static ControleImportFile.ImportFile fakeFile(List<ControleImportFile.Line> extra) {
        List<ControleImportFile.Line> lines = new ArrayList<>(List.of(
                line("ca-1", 5, "RSE 150 D"),
                line("ca-2", 6, "rse 150  d"),
                line("ca-3", 7, "BC")));
        lines.addAll(extra);
        return new ControleImportFile.ImportFile(
                new ControleImportFile.Meta("fake.xlsx", "2024-12-31", lines.size(), 0),
                List.of(new ControleImportFile.Account("c1", "Caisse Factice", "especes", "Société Factice", "", "")),
                List.of(), lines, List.of());
    }

    private String articleOf(Authentication auth, String lineId) {
        return queryService.getData(auth).lines().stream()
                .filter(l -> l.id().equals(lineId)).findFirst().orElseThrow().article();
    }

    // ── CA-03 ──────────────────────────────────────────────────────────────

    @Test
    void alias_changesEveryLineWithThatRawKey() {
        var rse = articleService.listArticles(authA).stream()
                .filter(r -> r.rawKey().equals(RSE_KEY)).findFirst().orElseThrow();
        assertThat(rse.lineCount()).isEqualTo(2);
        assertThat(rse.article()).isEqualTo("RSE 150");
        assertThat(rse.version()).isZero();
        assertThat(articleOf(authA, "ca-1")).isEqualTo("RSE 150");

        var saved = articleService.putAlias(authA,
                new ControleArticleService.AliasBody(RSE_KEY, "  Réservoir RSE 150  ", 0));
        assertThat(saved.article()).isEqualTo("Réservoir RSE 150");
        assertThat(saved.version()).isEqualTo(1);

        assertThat(articleOf(authA, "ca-1")).isEqualTo("Réservoir RSE 150");
        assertThat(articleOf(authA, "ca-2")).isEqualTo("Réservoir RSE 150");
        assertThat(articleOf(authA, "ca-3")).isEqualTo("BC (à préciser)");
    }

    // ── CA-04 ──────────────────────────────────────────────────────────────

    @Test
    void staleVersion_409() {
        articleService.putAlias(authA, new ControleArticleService.AliasBody(RSE_KEY, "RSE 150 bis", 0));

        // version 0 again: the row exists → STALE
        assertThatThrownBy(() -> articleService.putAlias(authA,
                new ControleArticleService.AliasBody(RSE_KEY, "Autre", 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "STALE");

        // controller maps STALE to 409
        var resp = articleController.putAlias(
                new ControleArticleService.AliasBody(RSE_KEY, "Autre", 5), authA);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // version 1 is the current one → saved as version 2
        var ok = articleService.putAlias(authA, new ControleArticleService.AliasBody(RSE_KEY, "Autre", 1));
        assertThat(ok.version()).isEqualTo(2);
        assertThat(articleOf(authA, "ca-2")).isEqualTo("Autre");
    }

    // ── CA-05 ──────────────────────────────────────────────────────────────

    @Test
    void orgB_neverSeesNorChangesOrgA_aliases() {
        articleService.putAlias(authA, new ControleArticleService.AliasBody(RSE_KEY, "Alias de A", 0));

        Authentication authB = newOrgOwner("ca.b");
        SecurityContextHolder.getContext().setAuthentication(authB);

        // B has no import: sees nothing, cannot change A's alias
        assertThat(articleService.listArticles(authB)).isEmpty();
        assertThatThrownBy(() -> articleService.putAlias(authB,
                new ControleArticleService.AliasBody(RSE_KEY, "Pirate", 1)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RAW_KEY_NOT_FOUND");

        // B imports the same file: B's lines use the rule, not A's alias
        importService.importParsed(authB, fakeFile(List.of()));
        var rseB = articleService.listArticles(authB).stream()
                .filter(r -> r.rawKey().equals(RSE_KEY)).findFirst().orElseThrow();
        assertThat(rseB.article()).isEqualTo("RSE 150");
        assertThat(rseB.version()).isZero();
        assertThat(articleOf(authB, "ca-1")).isEqualTo("RSE 150");

        // A is unchanged
        var rseA = articleService.listArticles(authA).stream()
                .filter(r -> r.rawKey().equals(RSE_KEY)).findFirst().orElseThrow();
        assertThat(rseA.article()).isEqualTo("Alias de A");
        assertThat(rseA.version()).isEqualTo(1);
    }

    // ── CA-06 ──────────────────────────────────────────────────────────────

    @Test
    void reimport_keepsAliases_andAppliesThem() {
        articleService.putAlias(authA, new ControleArticleService.AliasBody(RSE_KEY, "Réservoir RSE 150", 0));

        // Re-import with one more line whose text cleans to the same raw_key
        importService.importParsed(authA, fakeFile(List.of(line("ca-4", 8, " RSE  150 D "))));

        assertThat(articleOf(authA, "ca-1")).isEqualTo("Réservoir RSE 150");
        assertThat(articleOf(authA, "ca-4")).isEqualTo("Réservoir RSE 150");
        assertThat(articleOf(authA, "ca-3")).isEqualTo("BC (à préciser)");
        var rse = articleService.listArticles(authA).stream()
                .filter(r -> r.rawKey().equals(RSE_KEY)).findFirst().orElseThrow();
        assertThat(rse.lineCount()).isEqualTo(3);
        assertThat(rse.version()).isEqualTo(1);
    }
}
