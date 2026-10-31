package tn.hydra.cash;

/**
 * Mode de règlement d'un mouvement de caisse.
 *
 * <p>Architecture rule (Hydra design philosophy): every {@code cash_movement}
 * must carry a payment method — Tunisian BTP caisse needs to know <em>how</em>
 * money moved (Espèces / Chèque / Virement / Traite / Carte / Autre), not just
 * how much. Most day-to-day chantier operations are Espèces; Chèque /
 * Virement / Traite are common for supplier payments (OUT) and client
 * settlements (IN).
 *
 * <p>Codes are the stable API + DB values (stored verbatim in the
 * {@code cash_movement.payment_method} column, see V26 migration); labels are
 * French for the UI. Mirrors the {@code TicketStatus} pattern (label +
 * lenient {@code from} parser).
 *
 * <p>Step 1 (this enum + the V26 migration) only declares the type. Step 2
 * will wire it into the cash records (MovementResponse /
 * MovementDetailResponse / Create / Update requests) and the JdbcTemplate SQL
 * in {@link CashService}, plus service-level validation (default CASH when
 * absent, reject unknown codes).
 */
public enum PaymentMethod {
    CASH("Espèces"),
    CHEQUE("Chèque"),
    VIREMENT("Virement"),
    TRAITE("Traite"),
    CARD("Carte"),
    OTHER("Autre");

    private final String label;

    PaymentMethod(String label) { this.label = label; }

    /** French label for the UI (e.g. {@code "Espèces"}, {@code "Chèque"}). */
    public String label() { return label; }

    /**
     * Lenient parser: null / blank → {@link #CASH} (the default for a
     * Tunisian chantier caisse). Throws {@link IllegalArgumentException} for
     * unknown codes so Step 2 service validation can surface a clean
     * {@code 400} to the caller rather than silently coercing.
     */
    public static PaymentMethod from(String code) {
        if (code == null || code.isBlank()) return CASH;
        return PaymentMethod.valueOf(code.trim().toUpperCase());
    }
}
