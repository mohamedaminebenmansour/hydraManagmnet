package tn.hydra.organization;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TunisianIdsTest — pure JUnit, no Spring.
 * CO-01 MF validation + CO-02 RIB validation.
 */
class TunisianIdsTest {

    // ── CO-01: Matricule Fiscal ────────────────────────────────────────

    @Test
    void mf_valid_with_slashes() {
        assertThat(TunisianIds.normalizeMf("1234567/a/b/m/000")).isEqualTo("1234567ABM000");
        assertThat(TunisianIds.validateMf("1234567/a/b/m/000")).isEmpty();
        assertThat(TunisianIds.formatMf("1234567ABM000")).isEqualTo("1234567/A/B/M/000");
    }

    @Test
    void mf_valid_with_spaces() {
        assertThat(TunisianIds.validateMf("1234567 A B M 000")).isEmpty();
    }

    @Test
    void mf_invalid_check_letter_U() {
        Optional<String> err = TunisianIds.validateMf("1234567UBM000");
        assertThat(err).isPresent();
        assertThat(err.get()).contains("I, O ou U");
    }

    @Test
    void mf_invalid_tva_code_Z() {
        Optional<String> err = TunisianIds.validateMf("1234567AZM000");
        assertThat(err).isPresent();
        assertThat(err.get()).contains("Code TVA");
    }

    @Test
    void mf_invalid_category_X() {
        Optional<String> err = TunisianIds.validateMf("1234567ABX000");
        assertThat(err).isPresent();
        assertThat(err.get()).contains("Catégorie");
    }

    @Test
    void mf_invalid_too_short() {
        Optional<String> err = TunisianIds.validateMf("123456ABM000");
        assertThat(err).isPresent();
        assertThat(err.get()).contains("Format attendu");
    }

    // ── CO-02: RIB ─────────────────────────────────────────────────────

    @Test
    void rib_valid() {
        assertThat(TunisianIds.validateRib("07040005810111129653")).isEmpty();
        assertThat(TunisianIds.ibanFromRib("07040005810111129653"))
                .isEqualTo("TN5907040005810111129653");
    }

    @Test
    void rib_valid_with_spaces() {
        assertThat(TunisianIds.validateRib("07 040 0058101111296 53")).isEmpty();
    }

    @Test
    void rib_invalid_key() {
        Optional<String> err = TunisianIds.validateRib("07040005810111129654");
        assertThat(err).isPresent();
        assertThat(err.get()).contains("Clé RIB incorrecte");
    }

    @Test
    void rib_invalid_too_short() {
        Optional<String> err = TunisianIds.validateRib("0704000581011112965");
        assertThat(err).isPresent();
        assertThat(err.get()).contains("20 chiffres");
    }

    @Test
    void rib_demo_valid() {
        // Demo RIB from step 6: fake bank code 99, valid key
        assertThat(TunisianIds.validateRib("99001000000012345646")).isEmpty();
    }

    // ── Formatting ──────────────────────────────────────────────────────

    @Test
    void rib_format() {
        assertThat(TunisianIds.formatRib("07040005810111129653"))
                .isEqualTo("07 040 0058101111296 53");
    }

    @Test
    void iban_format() {
        assertThat(TunisianIds.formatIban("TN5907040005810111129653"))
                .isEqualTo("TN59 0704 0005 8101 1112 9653");
    }

    @Test
    void rib_mask() {
        assertThat(TunisianIds.maskRib("07040005810111129653"))
                .isEqualTo("****************9653");
    }
}
