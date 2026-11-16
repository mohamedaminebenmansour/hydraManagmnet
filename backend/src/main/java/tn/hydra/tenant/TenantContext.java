package tn.hydra.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Current tenant (organisation) and user of the running thread.
 *
 * <p>{@link TenantAwareDataSource} copies these values into the Postgres session variables
 * {@code app.org_id} / {@code app.user_id} each time a connection is <b>borrowed</b> from the pool.
 * So {@link #runAs} must wrap code that opens its own connection: calling it inside a transaction
 * that already holds a connection does not change that connection.
 *
 * <p>Filled for HTTP requests by {@link TenantContextFilter}; non-request code (bootstrap,
 * future jobs) uses {@link #runAs} / {@link #callAs}.
 */
public final class TenantContext {

    /** orgId and userId may each be null. */
    public record Tenant(UUID orgId, UUID userId) {}

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    /** The current tenant, or null when none is set. */
    public static Tenant current() {
        return CURRENT.get();
    }

    public static UUID orgId() {
        Tenant t = CURRENT.get();
        return t == null ? null : t.orgId();
    }

    public static UUID userId() {
        Tenant t = CURRENT.get();
        return t == null ? null : t.userId();
    }

    public static void set(UUID orgId, UUID userId) {
        CURRENT.set(new Tenant(orgId, userId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code block} with the given org/user, then restores what was there before. */
    public static void runAs(UUID orgId, UUID userId, Runnable block) {
        callAs(orgId, userId, () -> {
            block.run();
            return null;
        });
    }

    /** Same as {@link #runAs} but returns the block's result. */
    public static <T> T callAs(UUID orgId, UUID userId, Supplier<T> block) {
        Tenant previous = CURRENT.get();
        CURRENT.set(new Tenant(orgId, userId));
        try {
            return block.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
