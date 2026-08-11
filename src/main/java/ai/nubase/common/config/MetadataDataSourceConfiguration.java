package ai.nubase.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Separate configuration for the metadata database to avoid circular dependencies.
 * This configuration is loaded before MultiDatabaseConfiguration
 */
@Slf4j
@Configuration
public class MetadataDataSourceConfiguration {

    @Value("${spring.datasource.metadata.url}")
    private String metadataUrl;

    @Value("${spring.datasource.metadata.username}")
    private String metadataUsername;

    @Value("${spring.datasource.metadata.password}")
    private String metadataPassword;

    /**
     * Metadata DataSource — the only DB the application strictly needs at startup.
     * Stores tenant configurations, platform users, settings, etc.
     *
     * <p>Annotated with {@link FlywayDataSource} so Spring Boot's Flyway auto-config
     * targets this DataSource instead of the primary (routing) one. Without that
     * qualifier Flyway would hit RoutingDataSource → GuardianDataSource → block.
     */
    @Bean(name = "metadataDataSource")
    @FlywayDataSource
    public DataSource metadataDataSource() {
        log.info("Configuring metadata DataSource: {}", metadataUrl);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(metadataUrl);
        config.setUsername(metadataUsername);
        config.setPassword(metadataPassword);
        config.setDriverClassName("org.postgresql.Driver");

        // Metadata pool settings - smaller pool since it's for configuration only
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);

        config.setPoolName("MetadataPool");
        config.setAutoCommit(true);
        config.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(config);
    }

    /**
     * JdbcTemplate for metadata database
     * Used by DatabaseConfigRepository
     */
    @Bean(name = "metadataJdbcTemplate")
    public JdbcTemplate metadataJdbcTemplate(
            @Qualifier("metadataDataSource") DataSource metadataDataSource) {
        log.info("Creating metadataJdbcTemplate with metadataDataSource");
        return new JdbcTemplate(metadataDataSource);
    }
}
