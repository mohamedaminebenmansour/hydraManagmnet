package tn.hydra.organization;

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
import tn.hydra.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GroupementServiceTest — CO-08..CO-11, CO-14, CO-15.
 *
 * After FIX 2b, validateInput collects ALL field errors in a LinkedHashMap
 * and throws CompanyProfileService.ValidationException("VALIDATION", fields).
 * The structural checks that previously threw BusinessRuleException with codes
 * MEMBERS_MIN / SELF_REQUIRED / MANDATAIRE_REQUIRED / SHARES_NOT_100 are now
 * field errors under the "membres" or "parts" keys.
 */
@SpringBootTest
@Transactional
@Rollback
class GroupementServiceTest {

    @Autowired private GroupementService service;
    @Autowired private CompanyProfileService profileService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private String ownerEmail;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "grp.owner." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "GRP Test Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", ownerId, ownerRoleId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_ORG_SETTINGS")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);

        // Create a company profile so isSelf can copy from it
        profileService.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "HYDRA Test SARL", null, "SARL", "1234567/A/A/M/000", null,
                null, true, "Zone industrielle", "1000", "Tunis", "Tunis",
                "+216 70 000 000", "test@hydra.test", null, "Banque Démo", "99001000000012345646", 0));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    private GroupementService.GroupementInput validInput(BigDecimal share1, BigDecimal share2) {
        return new GroupementService.GroupementInput(
                "Test Groupement", "SOLIDAIRE", null, "AO-TEST-01", null, 0,
                List.of(
                        new GroupementService.MemberInput("Notre entreprise", null, "TN", "MANDATAIRE", share1, null, true),
                        new GroupementService.MemberInput("Partenaire SA", "7654321/B/A/M/000", "TN", "MEMBRE", share2, "Fourniture", false)));
    }

    // CO-08: Shares 60 + 30 → "parts" field error, no rows saved
    @Test
    void shares60plus30_rejected_noRowsSaved() {
        assertThatThrownBy(() -> service.create(ownerAuth, validInput(BigDecimal.valueOf(60), BigDecimal.valueOf(30))))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION")
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((CompanyProfileService.ValidationException) ex).getFields();
                    assertThat(fields).containsKey("parts");
                    assertThat(fields.get("parts")).contains("100 %");
                });

        Integer grpCount = jdbc.queryForObject("select count(*) from groupement where org_id = ?", Integer.class, orgId);
        assertThat(grpCount).isZero();
        Integer memberCount = jdbc.queryForObject("select count(*) from groupement_member where org_id = ?", Integer.class, orgId);
        assertThat(memberCount).isZero();
    }

    // CO-09: structural errors under the "membres" key:
    //   - two mandataires → "membres" = "Choisissez un seul mandataire."
    //   - no isSelf       → "membres" = "Indiquez quelle ligne est notre entreprise."
    //   - one member      → "membres" = "Un groupement compte au moins deux membres."
    @Test
    void validationErrors_twoMandataires_noSelf_oneMember() {
        // Two mandataires
        var twoMandataires = new GroupementService.GroupementInput(
                "Test", "SOLIDAIRE", null, null, null, 0,
                List.of(
                        new GroupementService.MemberInput("A", null, "TN", "MANDATAIRE", BigDecimal.valueOf(50), null, true),
                        new GroupementService.MemberInput("B", null, "TN", "MANDATAIRE", BigDecimal.valueOf(50), null, false)));
        assertThatThrownBy(() -> service.create(ownerAuth, twoMandataires))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION")
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((CompanyProfileService.ValidationException) ex).getFields();
                    assertThat(fields.get("membres")).contains("mandataire");
                });

        // No isSelf
        var noSelf = new GroupementService.GroupementInput(
                "Test", "SOLIDAIRE", null, null, null, 0,
                List.of(
                        new GroupementService.MemberInput("A", null, "TN", "MANDATAIRE", BigDecimal.valueOf(50), null, false),
                        new GroupementService.MemberInput("B", null, "TN", "MEMBRE", BigDecimal.valueOf(50), null, false)));
        assertThatThrownBy(() -> service.create(ownerAuth, noSelf))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION")
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((CompanyProfileService.ValidationException) ex).getFields();
                    assertThat(fields.get("membres")).contains("notre entreprise");
                });

        // One member
        var oneMember = new GroupementService.GroupementInput(
                "Test", "SOLIDAIRE", null, null, null, 0,
                List.of(
                        new GroupementService.MemberInput("A", null, "TN", "MANDATAIRE", BigDecimal.valueOf(100), null, true)));
        assertThatThrownBy(() -> service.create(ownerAuth, oneMember))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION")
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((CompanyProfileService.ValidationException) ex).getFields();
                    assertThat(fields.get("membres")).contains("deux membres");
                });
    }

    // CO-10: Valid groupement saved; PUT with invalid list → "parts" field error, old members still there
    @Test
    void putWithInvalidList_oldMembersPreserved() {
        var created = service.create(ownerAuth, validInput(BigDecimal.valueOf(60), BigDecimal.valueOf(40)));
        assertThat(created.membres()).hasSize(2);

        // PUT with invalid members (shares 90)
        var badInput = new GroupementService.GroupementInput(
                "Test Modified", "SOLIDAIRE", null, null, null, created.version(),
                List.of(
                        new GroupementService.MemberInput("C", null, "TN", "MANDATAIRE", BigDecimal.valueOf(50), null, true),
                        new GroupementService.MemberInput("D", null, "TN", "MEMBRE", BigDecimal.valueOf(40), null, false)));
        assertThatThrownBy(() -> service.update(ownerAuth, created.id(), badInput))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION")
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((CompanyProfileService.ValidationException) ex).getFields();
                    assertThat(fields).containsKey("parts");
                });

        // Old members still there
        var current = service.getOne(ownerAuth, created.id());
        assertThat(current.membres()).hasSize(2);
        assertThat(current.nom()).isEqualTo("Test Groupement"); // unchanged
    }

    // CO-11: Org B cannot see/modify org A's groupement
    @Test
    void orgIsolation_orgBCannotAccessOrgA() {
        var created = service.create(ownerAuth, validInput(BigDecimal.valueOf(60), BigDecimal.valueOf(40)));

        // Create org B + user
        UUID orgB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String emailB = "grp.orgB." + userB + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgB, "Org B");
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Org B Owner', true, true, false)
                """, userB, orgB, emailB, encoder.encode("OrgBPw2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", userB, ownerRoleId, userB);
        Authentication authB = new UsernamePasswordAuthenticationToken(emailB, null,
                List.of(new SimpleGrantedAuthority("PERM_ORG_SETTINGS")));

        // GET → not found
        assertThatThrownBy(() -> service.getOne(authB, created.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        // PUT → not found
        assertThatThrownBy(() -> service.update(authB, created.id(),
                new GroupementService.GroupementInput("Hacked", "SOLIDAIRE", null, null, null, 0, List.of())))
                .isInstanceOf(ResourceNotFoundException.class);

        // Archive → not found
        assertThatThrownBy(() -> service.archive(authB, created.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Nothing changed in org A's groupement
        var current = service.getOne(ownerAuth, created.id());
        assertThat(current.nom()).isEqualTo("Test Groupement");

        // Cleanup
        jdbc.update("delete from user_role where user_id = ?", userB);
        jdbc.update("delete from app_user where id = ?", userB);
        jdbc.update("delete from organization where id = ?", orgB);
    }

    // CO-14: isSelf member — client sends "Faux Nom" → saved value is profile's raison sociale
    @Test
    void isSelfMember_raisonSocialeCopiedFromProfile() {
        var input = new GroupementService.GroupementInput(
                "Test Self", "CONJOINT", null, null, null, 0,
                List.of(
                        new GroupementService.MemberInput("Faux Nom", null, "TN", "MANDATAIRE", BigDecimal.valueOf(60), null, true),
                        new GroupementService.MemberInput("Partenaire SA", "7654321/B/A/M/000", "TN", "MEMBRE", BigDecimal.valueOf(40), null, false)));
        var created = service.create(ownerAuth, input);

        var selfMember = created.membres().stream().filter(MemberResponse -> MemberResponse.isSelf()).findFirst().orElseThrow();
        assertThat(selfMember.raisonSociale()).isEqualTo("HYDRA Test SARL"); // from profile, not "Faux Nom"
        assertThat(selfMember.matriculeFiscal()).isEqualTo("1234567AAM000"); // from profile, normalized
    }

    // CO-15: groupement with nom null + a member with empty name + role "BOSS"
    // → one 400 with the 3 keys (nom, membres[1].raisonSociale, membres[1].role), nothing saved.
    @Test
    void multiError_nomNull_emptyName_invalidRole_one400threeKeys_nothingSaved() {
        var input = new GroupementService.GroupementInput(
                null, "SOLIDAIRE", null, null, null, 0,
                List.of(
                        // Row 0: isSelf true, role MANDATAIRE (valid), share 60.00
                        new GroupementService.MemberInput("Whatever self name", null, "TN", "MANDATAIRE",
                                new BigDecimal("60.00"), null, true),
                        // Row 1: isSelf false, empty name → "membres[1].raisonSociale" error,
                        //        role "BOSS" → "membres[1].role" error, share 40.00 (valid)
                        new GroupementService.MemberInput("", null, "TN", "BOSS",
                                new BigDecimal("40.00"), null, false)));

        assertThatThrownBy(() -> service.create(ownerAuth, input))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "VALIDATION")
                .satisfies(ex -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> fields = ((CompanyProfileService.ValidationException) ex).getFields();
                    // Exactly the 3 keys expected
                    assertThat(fields).containsOnlyKeys("nom", "membres[1].raisonSociale", "membres[1].role");
                    assertThat(fields.get("nom")).contains("nom du groupement");
                    assertThat(fields.get("membres[1].raisonSociale")).contains("raison sociale");
                    assertThat(fields.get("membres[1].role")).contains("Rôle invalide");
                });

        Integer grpCount = jdbc.queryForObject("select count(*) from groupement where org_id = ?", Integer.class, orgId);
        assertThat(grpCount).isZero();
        Integer memberCount = jdbc.queryForObject("select count(*) from groupement_member where org_id = ?", Integer.class, orgId);
        assertThat(memberCount).isZero();
    }
}
