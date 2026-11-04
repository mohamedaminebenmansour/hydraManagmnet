package tn.hydra.config.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import tn.hydra.organization.CompanyProfileService;
import tn.hydra.organization.GroupementService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DemoEntrepriseScenario (S1-5 step 6): company profile + groupement for the demo org.
 */
@Component
@Profile("demo")
public class DemoEntrepriseScenario implements DemoScenario {

    private static final Logger log = LoggerFactory.getLogger(DemoEntrepriseScenario.class);

    private final CompanyProfileService profileService;
    private final GroupementService groupementService;
    private final JdbcTemplate jdbc;

    public DemoEntrepriseScenario(CompanyProfileService profileService,
                                  GroupementService groupementService, JdbcTemplate jdbc) {
        this.profileService = profileService;
        this.groupementService = groupementService;
        this.jdbc = jdbc;
    }

    @Override public String key() { return "entreprise"; }

    @Override
    public void apply(DemoContext ctx) {
        UUID orgId = ctx.orgId();
        Authentication gerant = ctx.auth(DemoScenarioRunner.GERANT_EMAIL);

        // Profile: skip if exists
        Integer profileExists = jdbc.queryForObject(
                "select count(*) from company_profile where org_id = ?", Integer.class, orgId);
        if (profileExists == null || profileExists == 0) {
            profileService.update(gerant, new CompanyProfileService.UpdateRequest(
                    "TN", "HYDRA Démo SARL", null, "SARL", "1234567/A/A/M/000", "1234567A",
                    new BigDecimal("50000.000"), true,
                    "Zone industrielle, rue de l'exemple", "1000", "Tunis", "Tunis",
                    "+216 70 000 000", "contact@demo.hydra.tn", null,
                    "Banque Démo", "99001000000012345646", 0));
            log.info("[DemoEntrepriseScenario] created company profile");
        }

        // Groupement: skip if exists
        Integer grpExists = jdbc.queryForObject(
                "select count(*) from groupement where org_id = ? and nom = 'Groupement Démo AEP'",
                Integer.class, orgId);
        if (grpExists == null || grpExists == 0) {
            groupementService.create(gerant, new GroupementService.GroupementInput(
                    "Groupement Démo AEP", "SOLIDAIRE", ctx.projectId(), "AO-DÉMO-01", null, 0,
                    List.of(
                            new GroupementService.MemberInput(
                                    "HYDRA Démo SARL", null, "TN", "MANDATAIRE",
                                    new BigDecimal("60.00"), null, true),
                            new GroupementService.MemberInput(
                                    "Partenaire Démo SA", "7654321/B/A/M/000", "TN", "MEMBRE",
                                    new BigDecimal("40.00"), "Fourniture et pose des conduites DN 300", false))));
            log.info("[DemoEntrepriseScenario] created groupement");
        }
    }
}
