package tn.hydra.config.demo;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One step of the HYDRA Démo scenario pack. Each bean implementing this
 * interface is called by {@link DemoScenarioRunner} in Spring's natural bean
 * order, after the demo organisation, users and chantier have been created.
 *
 * <p>Implementations must be idempotent: re-running the runner on an already
 * seeded database must not duplicate records and must not throw. The standard
 * way is to skip a record if a ticket with the same title already exists in
 * the demo org.
 */
public interface DemoScenario {

    /** Stable key used for ordering and logging (e.g. "taches"). */
    String key();

    /** Apply this step to the demo world described by {@code ctx}. */
    void apply(DemoContext ctx);

    /**
     * Context handed to every scenario: the demo org, chantier, today, and a way
     * to build an {@link org.springframework.security.core.Authentication} for a
     * user by email, plus a lookup of a user's id by email.
     */
    final class DemoContext {
        private final UUID orgId;
        private final UUID projectId;
        private final LocalDate today;
        private final java.util.function.Function<String, org.springframework.security.core.Authentication> authFactory;
        private final java.util.function.Function<String, UUID> userIdLookup;

        public DemoContext(UUID orgId, UUID projectId, LocalDate today,
                           java.util.function.Function<String, org.springframework.security.core.Authentication> authFactory,
                           java.util.function.Function<String, UUID> userIdLookup) {
            this.orgId = orgId; this.projectId = projectId; this.today = today;
            this.authFactory = authFactory; this.userIdLookup = userIdLookup;
        }

        public UUID orgId() { return orgId; }
        public UUID projectId() { return projectId; }
        public LocalDate today() { return today; }

        /** Build an Authentication for a demo user by email. */
        public org.springframework.security.core.Authentication auth(String email) {
            return authFactory.apply(email);
        }

        /** Look up a demo user's id by email. */
        public UUID userId(String email) { return userIdLookup.apply(email); }
    }
}
