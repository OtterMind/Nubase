package ai.nubase.common.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Metadata JPA configuration, isolated from routing datasource JPA repositories.
 */
@Configuration
@EnableJpaRepositories(
        // Precise subpackages, not the whole ai.nubase.metadata tree: anything new
        // placed under it should opt into this persistence unit explicitly rather
        // than being swept into the metadata datasource by accident.
        basePackages = {"ai.nubase.metadata.repository", "ai.nubase.metadata.edge.repository"},
        entityManagerFactoryRef = "metadataEntityManagerFactory",
        transactionManagerRef = "metadataTransactionManager"
)
public class MetadataJpaConfig {

    @Bean(name = "metadataEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean metadataEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("metadataDataSource") DataSource metadataDataSource) {
        return builder
                .dataSource(metadataDataSource)
                .packages("ai.nubase.metadata.entity", "ai.nubase.metadata.edge.entity")
                .persistenceUnit("metadata")
                .build();
    }

    @Bean(name = "metadataTransactionManager")
    public PlatformTransactionManager metadataTransactionManager(
            @Qualifier("metadataEntityManagerFactory") EntityManagerFactory metadataEntityManagerFactory) {
        return new JpaTransactionManager(metadataEntityManagerFactory);
    }
}
