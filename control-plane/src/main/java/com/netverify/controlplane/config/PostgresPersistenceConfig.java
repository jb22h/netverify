package com.netverify.controlplane.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.StringUtils;

/**
 * Explicit PostgreSQL persistence wiring for the {@code postgres} profile only.
 *
 * <p>Default startup excludes Boot's DataSource/Flyway auto-config, so this class
 * is the sole place that creates a DataSource, runs migrations, and exposes
 * {@link JdbcTemplate}.
 */
@Configuration
@Profile("postgres")
@EnableTransactionManagement
public class PostgresPersistenceConfig {

    @Bean(destroyMethod = "close")
    public DataSource dataSource(PostgresProperties properties) {
        requireConfigured(properties);
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getUrl().trim());
        dataSource.setUsername(properties.getUser().trim());
        dataSource.setPassword(properties.getPassword());
        dataSource.setPoolName("netverify-postgres");
        return dataSource;
    }

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource, Flyway flyway) {
        // Flyway parameter forces migrate() before any repository SQL.
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    private static void requireConfigured(PostgresProperties properties) {
        if (!StringUtils.hasText(properties.getUrl())) {
            throw new IllegalStateException(
                    "postgres profile requires NETVERIFY_DB_URL (netverify.db.url)"
            );
        }
        if (!StringUtils.hasText(properties.getUser())) {
            throw new IllegalStateException(
                    "postgres profile requires NETVERIFY_DB_USER (netverify.db.user)"
            );
        }
        if (!StringUtils.hasText(properties.getPassword())) {
            throw new IllegalStateException(
                    "postgres profile requires NETVERIFY_DB_PASSWORD (netverify.db.password)"
            );
        }
    }
}
