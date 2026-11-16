package tn.hydra.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Primary DataSource: puts the {@link TenantContext} on every connection it hands out.
 *
 * <ul>
 *   <li>borrow: {@code set_config('app.org_id', …, false), set_config('app.user_id', …, false)}
 *       (session level, so it covers autocommit code, JdbcTemplate, JPA and transactions alike);</li>
 *   <li>close: resets both to {@code ''} before the connection goes back to the pool. A connection
 *       left in an open transaction is rolled back first (the pool would roll it back anyway, and
 *       that rollback would also undo the reset). If the reset fails the physical connection is
 *       evicted from the pool, so a tagged connection is never reused.</li>
 * </ul>
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    static final String SET_SQL =
            "select set_config('app.org_id', ?, false), set_config('app.user_id', ?, false)";
    static final String RESET_SQL =
            "select set_config('app.org_id', '', false), set_config('app.user_id', '', false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return tag(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return tag(obtainTargetDataSource().getConnection(username, password));
    }

    private Connection tag(Connection raw) throws SQLException {
        try (PreparedStatement ps = raw.prepareStatement(SET_SQL)) {
            ps.setString(1, text(TenantContext.orgId()));
            ps.setString(2, text(TenantContext.userId()));
            ps.execute();
            // Pool configured without autocommit: make the setting survive a later rollback.
            if (!raw.getAutoCommit()) raw.commit();
        } catch (SQLException | RuntimeException e) {
            raw.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                TenantAwareDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ResettingHandler(raw));
    }

    /** Lets Spring close the underlying pool on shutdown (inferred destroy method). */
    public void close() throws Exception {
        if (obtainTargetDataSource() instanceof AutoCloseable c) c.close();
    }

    private static String text(UUID id) {
        return id == null ? "" : id.toString();
    }

    private final class ResettingHandler implements InvocationHandler {
        private final Connection raw;
        private boolean closed;

        ResettingHandler(Connection raw) {
            this.raw = raw;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    if (!closed) {
                        closed = true;
                        resetThenClose();
                    }
                    return null;
                }
                case "isClosed" -> {
                    if (closed) return true;
                }
                case "unwrap" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                }
                case "isWrapperFor" -> {
                    if (((Class<?>) args[0]).isInstance(proxy)) return true;
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantAware[" + raw + "]";
                }
                default -> { }
            }
            try {
                return method.invoke(raw, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        private void resetThenClose() throws SQLException {
            try {
                if (!raw.isClosed()) {
                    boolean autoCommit = raw.getAutoCommit();
                    if (!autoCommit) raw.rollback();
                    try (Statement st = raw.createStatement()) {
                        st.execute(RESET_SQL);
                    }
                    if (!autoCommit) raw.commit();
                }
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not reset tenant on a connection; evicting it from the pool", e);
                evict();
            } finally {
                raw.close();
            }
        }

        private void evict() {
            try {
                DataSource target = obtainTargetDataSource();
                if (target.isWrapperFor(HikariDataSource.class)) {
                    target.unwrap(HikariDataSource.class).evictConnection(raw);
                    return;
                }
                // Not Hikari: make sure the physical connection is not reused.
                raw.abort(Runnable::run);
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not evict connection", e);
            }
        }
    }
}
