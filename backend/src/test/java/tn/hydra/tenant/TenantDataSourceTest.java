package tn.hydra.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Core 1a: the tenant reaches every connection and is reset when the connection goes back to the pool. */
@SpringBootTest
class TenantDataSourceTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Value("${spring.datasource.url}") private String url;
    @Value("${spring.datasource.username}") private String user;
    @Value("${spring.datasource.password}") private String password;

    @Test
    void primaryDataSourceIsTenantAware() {
        assertThat(dataSource).isInstanceOf(TenantAwareDataSource.class);
    }

    @Test
    void jdbcTemplate_insideRunAs_seesOrgAndUser_outside_seesEmpty() {
        UUID org = UUID.randomUUID();
        UUID usr = UUID.randomUUID();

        TenantContext.runAs(org, usr, () -> {
            assertThat(setting(jdbc, "app.org_id")).isEqualTo(org.toString());
            assertThat(setting(jdbc, "app.user_id")).isEqualTo(usr.toString());
            assertThat(jdbc.queryForObject("select hydra_current_org()", UUID.class)).isEqualTo(org);
            assertThat(jdbc.queryForObject("select hydra_current_user()", UUID.class)).isEqualTo(usr);
        });

        assertThat(TenantContext.current()).isNull();
        assertThat(setting(jdbc, "app.org_id")).isEmpty();
        assertThat(setting(jdbc, "app.user_id")).isEmpty();
        assertThat(jdbc.queryForObject("select hydra_current_org()", UUID.class)).isNull();
    }

    @Test
    void sameConnection_reborrowedWithoutContext_isReset() throws Exception {
        try (HikariDataSource pool = poolOfOne()) {
            TenantAwareDataSource ds = new TenantAwareDataSource(pool);
            UUID org = UUID.randomUUID();
            Object firstPhysical = TenantContext.callAs(org, UUID.randomUUID(), () -> {
                try (Connection c = ds.getConnection()) {
                    assertThat(setting(c, "app.org_id")).isEqualTo(org.toString());
                    return physicalId(c);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            try (Connection c = ds.getConnection()) {
                assertThat(physicalId(c)).isEqualTo(firstPhysical); // same backend process
                assertThat(setting(c, "app.org_id")).isEmpty();
                assertThat(setting(c, "app.user_id")).isEmpty();
            }
        }
    }

    @Test
    void connectionClosedInsideAnOpenTransaction_isStillReset() throws Exception {
        try (HikariDataSource pool = poolOfOne()) {
            TenantAwareDataSource ds = new TenantAwareDataSource(pool);
            TenantContext.runAs(UUID.randomUUID(), UUID.randomUUID(), () -> {
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try (Statement st = c.createStatement()) {
                        st.execute("select 1"); // dirty, never committed
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });

            try (Connection c = ds.getConnection()) {
                assertThat(setting(c, "app.org_id")).isEmpty();
                assertThat(setting(c, "app.user_id")).isEmpty();
            }
        }
    }

    private HikariDataSource poolOfOne() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setPoolName("tenant-test");
        return pool;
    }

    private static String setting(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("select current_setting(?, true)", String.class, name);
    }

    private static String setting(Connection c, String name) throws SQLException {
        try (var ps = c.prepareStatement("select current_setting(?, true)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static Object physicalId(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
