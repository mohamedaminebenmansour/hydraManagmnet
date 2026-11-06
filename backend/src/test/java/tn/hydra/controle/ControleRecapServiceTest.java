package tn.hydra.controle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ControleRecapServiceTest — CB-05: an alias change updates rubrique and famille.
 * Also checks the échéance account kind and the recap go through import and /data.
 * Builds a small fake import in code (no real data).
 */
@SpringBootTest
@Transactional
@Rollback
class ControleRecapServiceTest {

    @Autowired private ControleArticleService articleService;
    @Autowired private ControleImportService importService;
    @Autowired private ControleQueryService queryService;
    @Autowired private JdbcTemplate jdbc;

    private Authentication auth;

    @BeforeEach
    void provision() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String email = "cb." + userId + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "CB Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, 'x', 'Owner', true, true, false)
                """, userId, orgId, email);
        auth = new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_READ"),
                        new SimpleGrantedAuthority("PERM_CTL_JUSTIFY"),
                        new SimpleGrantedAuthority("PERM_CTL_IMPORT")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        List<ControleImportFile.Line> lines = List.of(
                line("cb-1", 5, "c1", "RSE 150 D"),
                line("cb-2", 6, "c1", "rse 150 d"),
                line("cb-3", 7, "caisse-echeance", "Local GDA"));
        importService.importParsed(auth, new ControleImportFile.ImportFile(
                new ControleImportFile.Meta("fake.xlsx", "2024-12-31", lines.size(), 0),
                List.of(new ControleImportFile.Account("c1", "Caisse Factice", "especes", "Société Factice", "", ""),
                        new ControleImportFile.Account("caisse-echeance", "Caisse à échéance", "echeance", "SIGAT", "", null)),
                List.of(), lines, List.of(),
                List.of(new ControleImportFile.Recap("Hajeb", "RSE 150 D", 69877.503),
                        new ControleImportFile.Recap("Hajeb", "Local GDA", 7125.66))));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    private static ControleImportFile.Line line(String id, int row, String acct, String cat) {
        return new ControleImportFile.Line(id, "Caisse", row, "2024-03-01", acct, "Hajeb",
                0, 100, "especes", "", "", cat, "Ligne factice", "", "", "sortie", "", List.of(), null);
    }

    private ControleQueryService.CtlLine lineOf(String id) {
        return queryService.getData(auth).lines().stream()
                .filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    // ── CB-05 ──────────────────────────────────────────────────────────────

    @Test
    void aliasChange_updatesRubriqueAndFamille() {
        // at import
        assertThat(lineOf("cb-1").rubrique()).isEqualTo("RSE 150 D");
        assertThat(lineOf("cb-1").famille()).isEqualTo("Ouvrages");
        assertThat(lineOf("cb-3").rubrique()).isEqualTo("Local GDA");
        assertThat(lineOf("cb-3").famille()).isEqualTo("Ouvrages");

        // the gérant moves « rse 150 d » to a sous-traitant
        articleService.putAlias(auth, new ControleArticleService.AliasBody("rse 150 d", "ST Hamza Msalmi", 0));
        for (String id : new String[]{"cb-1", "cb-2"}) {
            var l = lineOf(id);
            assertThat(l.article()).isEqualTo("ST Hamza Msalmi");
            assertThat(l.rubrique()).isEqualTo("ST Hamza Msalmi");
            assertThat(l.famille()).isEqualTo("Sous-traitance");
        }

        // then to « Travaux divers / GC »: rubrique by raw_key (no « gc » in it)
        articleService.putAlias(auth, new ControleArticleService.AliasBody("rse 150 d", "Travaux divers / GC", 1));
        assertThat(lineOf("cb-2").rubrique()).isEqualTo("Travaux Divers");
        assertThat(lineOf("cb-2").famille()).isEqualTo("Travaux");
        assertThat(lineOf("cb-3").rubrique()).isEqualTo("Local GDA");   // other raw_key untouched
    }

    @Test
    void recap_andEcheanceAccount_storedAndReturned() {
        var data = queryService.getData(auth);
        assertThat(data.accounts()).extracting(ControleQueryService.CtlAccount::kind).contains("echeance");
        assertThat(data.recap())
                .extracting(ControleQueryService.CtlRecap::proj, ControleQueryService.CtlRecap::rubrique)
                .containsExactly(tuple("Hajeb", "Local GDA"), tuple("Hajeb", "RSE 150 D"));
        assertThat(data.recap().get(1).amount()).isEqualByComparingTo(new BigDecimal("69877.503"));

        // a re-import replaces the recap
        importService.importParsed(auth, new ControleImportFile.ImportFile(
                new ControleImportFile.Meta("fake.xlsx", "2024-12-31", 0, 0),
                List.of(), List.of(), List.of(), List.of()));
        assertThat(queryService.getData(auth).recap()).isEmpty();
    }
}
