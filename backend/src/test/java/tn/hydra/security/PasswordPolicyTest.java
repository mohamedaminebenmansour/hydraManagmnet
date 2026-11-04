package tn.hydra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tn.hydra.user.AppUser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PasswordPolicyTest — validates the password policy rules (U1):
 * min length, common-password blocklist, personal/company info, max length.
 */
class PasswordPolicyTest {

    private PasswordPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PasswordPolicy();
        ReflectionTestUtils.setField(policy, "minLength", 12);
        policy.loadCommonPasswords(); // load the blocklist from classpath
    }

    @Test
    void elevenChars_refused() {
        List<String> errors = policy.validate(null, "Abc1234xyz!");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).contains("12 caractères");
    }

    @Test
    void commonPassword_refused() {
        // "password1234" is likely in the 10k common list or contains "password"
        List<String> errors = policy.validate(null, "password1234");
        assertThat(errors).isNotEmpty();
    }

    @Test
    void companyNameInside_refused() {
        // contains "chantier" and "sigat"
        List<String> errors = policy.validate(null, "Sigat2026Chantier");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.contains("nom") || m.contains("entreprise"));
    }

    @Test
    void fullNameInside_refused() {
        AppUser user = new AppUser();
        user.setEmail("jean.dupont@hydra.tn");
        user.setFullName("Jean Dupont");
        List<String> errors = policy.validate(user, "JeanDupont2026xy!");
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(m -> m.contains("nom") || m.contains("email"));
    }

    @Test
    void fourWordPassphrase_accepted() {
        // a 4-word passphrase, 20+ chars, no forbidden words, not common
        List<String> errors = policy.validate(null, "correct-cheval-vitres-batterie");
        assertThat(errors).isEmpty();
    }

    @Test
    void oneHundredTwentyNineChars_refused() {
        String tooLong = "a".repeat(129);
        List<String> errors = policy.validate(null, tooLong);
        assertThat(errors).anyMatch(m -> m.contains("Maximum") || m.contains("128"));
    }

    @Test
    void emailLocalPartInside_refused() {
        AppUser user = new AppUser();
        user.setEmail("mohamed.benali@hydra.tn"); // local part "mohamed.benali" contains "mohamed" (7 chars)
        user.setFullName("Mohamed Benali");
        List<String> errors = policy.validate(user, "Mohamed2026xyzqw!");
        assertThat(errors).isNotEmpty();
    }

    @Test
    void qwertyInside_refused() {
        List<String> errors = policy.validate(null, "qwerty1234567");
        assertThat(errors).isNotEmpty();
    }
}
