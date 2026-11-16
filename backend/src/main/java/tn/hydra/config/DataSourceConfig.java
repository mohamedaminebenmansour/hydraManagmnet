package tn.hydra.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tn.hydra.tenant.TenantAwareDataSource;

import javax.sql.DataSource;

/**
 * Two DataSources (core 1a):
 * <ul>
 *   <li>{@code dataSource} (primary) — Hikari built from {@code spring.datasource.*}, wrapped in
 *       {@link TenantAwareDataSource}. Used by JPA, JdbcTemplate, Spring Session, Flyway (when no
 *       Flyway user is set).</li>
 *   <li>{@code systemDataSource} — owner credentials ({@code spring.flyway.user/password}, falling back
 *       to {@code spring.datasource.*}), pool of 2, exposed only as {@code @Qualifier("system") JdbcTemplate}.
 *       Allow-list: bootstrap, migrations and cross-tenant system jobs only (docs/core/DB-ROLES.md).</li>
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public TenantAwareDataSource dataSource(DataSourceProperties props, Environment env) {
        HikariDataSource hikari = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(env).bind("spring.datasource.hikari", Bindable.ofInstance(hikari));
        return new TenantAwareDataSource(hikari);
    }

    /** Declared here because Boot's JdbcTemplate auto-configuration backs off once a second JdbcTemplate exists. */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "systemDataSource", defaultCandidate = false)
    public HikariDataSource systemDataSource(
            DataSourceProperties props,
            @Value("${spring.flyway.user:${spring.datasource.username:}}") String user,
            @Value("${spring.flyway.password:${spring.datasource.password:}}") String password) {
        HikariDataSource ds = props.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .username(user)
                .password(password)
                .build();
        ds.setPoolName("hydra-system");
        ds.setMaximumPoolSize(2);
        ds.setMinimumIdle(0);
        return ds;
    }

    @Bean(defaultCandidate = false)
    @Qualifier("system")
    public JdbcTemplate systemJdbcTemplate(@Qualifier("systemDataSource") DataSource systemDataSource) {
        return new JdbcTemplate(systemDataSource);
    }
}
