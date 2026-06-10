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
        basePackages = {"ai.nubase.metadata"},
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
                .packages("ai.nubase.metadata")
                .persistenceUnit("metadata")
                .build();
    }

    @Bean(name = "metadataTransactionManager")
    public PlatformTransactionManager metadataTransactionManager(
            @Qualifier("metadataEntityManagerFactory") EntityManagerFactory metadataEntityManagerFactory) {
        return new JpaTransactionManager(metadataEntityManagerFactory);
    }
}
