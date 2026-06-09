package ai.nubase.common.config;

import ai.nubase.postgrest.multidb.GuardianDataSource;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spring configuration for multi-database routing and primary JPA repositories.
 * <p>
 * This configuration integrates:
 * - RoutingDataSource (dynamic multi-tenant routing)
 * - EntityManagerFactory (JPA configuration)
 * - JpaRepositories (@EnableJpaRepositories)
 * - TransactionManager (transaction management)
 * - CacheManager (Redis cache)
 * <p>
 * Note: this configuration no longer registers DatabaseRoutingInterceptor.
 * All requests are authenticated and routed uniformly by UnifiedMultiTenancyFilter.
 */
@Slf4j
@Configuration
@EnableCaching
@EnableJpaRepositories(
        basePackages = {
                "ai.nubase.auth.repository",
                "ai.nubase.ai.gateway.repository"
        },
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class MultiDatabaseConfiguration implements CachingConfigurer {

    // ApplicationContext is no longer required since DatabaseRoutingInterceptor is disabled
    // @Autowired
    // private ApplicationContext applicationContext;

    /**
     * Make the cache layer fail-soft. The cache (Caffeine or Redis) is an optimization, not a
     * source of truth — a transient backend hiccup (e.g. a momentary Redis/DNS blip on one node)
     * must never fail the underlying business operation.
     *
     * <p>Without this, a Redis {@code @CacheEvict} during project creation
     * ({@link ai.nubase.postgrest.multidb.DatabaseConfigRepository#save}) would propagate a
     * {@code RedisConnectionFailureException} and abort the whole create. Here we instead:
     * <ul>
     *   <li>GET error → treat as a cache miss (fall through to the database)</li>
     *   <li>PUT / EVICT / CLEAR error → log and continue (entry self-heals on its TTL)</li>
     * </ul>
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache GET degraded to a miss [cache={}, key={}]: {}",
                        cache.getName(), key, ex.toString());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache PUT skipped [cache={}, key={}]: {}",
                        cache.getName(), key, ex.toString());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache EVICT skipped [cache={}, key={}]: {}",
                        cache.getName(), key, ex.toString());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.warn("Cache CLEAR skipped [cache={}]: {}", cache.getName(), ex.toString());
            }
        };
    }

    /**
     * Routing DataSource - the primary datasource for the application
     * Dynamically routes to different tenant databases based on request context
     * <p>
     * Sets the metadata database as the default datasource for Hibernate startup initialization.
     */
    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("metadataDataSource") DataSource metadataDataSource) {

        log.info("Configuring routing DataSource with GuardianDataSource as default");

        RoutingDataSource routingDataSource = new RoutingDataSource();

        // Use the guardian datasource as the default - a defensive design
        // Prevents any database operation that bypasses the normal tenant authentication flow from accessing the metadata DB
        // Note: the metadataDataSource parameter is used to trigger its initialization, ensuring JPA config loads correctly
        GuardianDataSource guardianDataSource = new GuardianDataSource();
        routingDataSource.setDefaultTargetDataSource(guardianDataSource);

        log.info("✓ GuardianDataSource set as default - provides security against unauthenticated access");

        log.info("Routing DataSource configured");
        return routingDataSource;
    }

    /**
     * Primary JdbcTemplate - uses the routing datasource
     * This will automatically route to the correct tenant database
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Primary EntityManagerFactory for routing datasource
     * Uses metadataDataSource as bootstrap datasource for initialization only.
     * At runtime, RoutingDataSource intercepts all queries and routes to correct tenant DB.
     * GuardianDataSource prevents accidental metadata DB access.
     */
    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            EntityManagerFactoryBuilder builder,
            DataSource dataSource) {
        log.info("Configuring primary EntityManagerFactory for multi-tenant routing");

        LocalContainerEntityManagerFactoryBean emf = builder
                .dataSource(dataSource)  // Bootstrap only, not used at runtime
                .packages("ai.nubase.auth.entity", "ai.nubase.ai.gateway.entity")
                .persistenceUnit("default")
                .build();

        log.info("✓ Primary EntityManagerFactory configured (bootstrap with metadata DB)");
        return emf;
    }

    /**
     * Primary TransactionManager - uses the routing datasource
     * This is required for JPA and @Transactional without qualifier
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        log.info("Configuring primary JpaTransactionManager for multi-tenant JPA");
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * Pure-JDBC transaction manager bound to the same routing DataSource.
     *
     * <p>The primary {@link JpaTransactionManager} does NOT bind its connection to the
     * {@code DataSourceUtils} key in a way that lets plain {@code JdbcTemplate} calls join
     * the same transaction reliably (especially when no EntityManager is touched in the
     * method). The mem module is JdbcTemplate-only, so we expose this bean and annotate
     * mem service methods with {@code @Transactional(transactionManager = "memJdbcTransactionManager")}
     * to get real transactional semantics — atomic ADD/UPDATE/DELETE, real rollback on
     * mid-flight LLM failure.
     *
     * <p>Routes per-tenant via {@link RoutingDataSource}; the tenant is locked in at
     * transaction begin (when the filter has already set {@code MultiTenancyContext}).
     */
    @Bean(name = "memJdbcTransactionManager")
    public PlatformTransactionManager memJdbcTransactionManager(DataSource dataSource) {
        log.info("Configuring memJdbcTransactionManager (DataSourceTransactionManager) "
                + "for JDBC-only mem module operations");
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }

    /**
     * In-process Caffeine cache manager (default). No external dependency — single-node
     * self-host needs nothing more than Postgres to boot.
     *
     * <p>Selected when {@code nubase.cache.type} is unset or {@code caffeine}. Multi-node
     * deployments that need cache coherence across replicas should switch to {@code redis}
     * via {@code NUBASE_CACHE_TYPE=redis}.
     *
     * <p>Per-region settings:
     * <ul>
     *   <li>{@code databaseConfigs} / {@code schemaCaches} / {@code jwtServices} — 5-minute
     *       TTL, max 10k entries</li>
     *   <li>{@code appAuthCache} — 10-minute TTL, max 10k entries</li>
     * </ul>
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "nubase.cache", name = "type",
            havingValue = "caffeine", matchIfMissing = true)
    public CacheManager caffeineCacheManager() {
        log.info("Configuring Caffeine cache manager (in-process; for distributed caching set "
                + "nubase.cache.type=redis)");
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Default spec applied to any cache the framework asks for, in case a @Cacheable
        // appears on a region we haven't pre-declared below.
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000));

        // Pre-register the regions we know about so they exist before any @Cacheable fires
        // and the appAuthCache TTL diverges from the default.
        manager.registerCustomCache("databaseConfigs",
                Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10_000).build());
        manager.registerCustomCache("schemaCaches",
                Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10_000).build());
        manager.registerCustomCache("jwtServices",
                Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10_000).build());
        manager.registerCustomCache("appAuthCache",
                Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).maximumSize(10_000).build());
        return manager;
    }

    /**
     * Redis cache manager — opt-in via {@code nubase.cache.type=redis}.
     * Supports different TTL for different cache regions.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "nubase.cache", name = "type", havingValue = "redis")
    public CacheManager redisCacheManager(RedisConnectionFactory redisConnectionFactory) {
        log.info("Configuring Redis cache manager");

        // Configure ObjectMapper: enable type info + ignore timestamp fields
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Enable type info to support deserialization of complex objects
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );

        // Configure ignored fields (timestamp fields are not cached)
        objectMapper.addMixIn(Object.class, IgnoreTimestampFieldsMixin.class);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        // Default cache configuration: 5-minute TTL
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer)
                )
                .disableCachingNullValues();

        // Configure different TTLs for different cache regions
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // appAuthCache: 10-minute TTL (used for app authentication cache)
        cacheConfigurations.put("appAuthCache",
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(10))
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                        )
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer)
                        )
                        .disableCachingNullValues()
        );

        // databaseConfigs: 5-minute TTL
        cacheConfigurations.put("databaseConfigs", defaultConfig);

        // schemaCaches: 5-minute TTL
        cacheConfigurations.put("schemaCaches", defaultConfig);

        // jwtServices: 5-minute TTL
        cacheConfigurations.put("jwtServices", defaultConfig);

        RedisCacheManager cacheManager = RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
//            .transactionAware()
                .build();

        log.info("Redis cache manager configured with default 5-minute TTL, appAuthCache with 10-minute TTL");
        return cacheManager;
    }

    /**
     * Mixin interface: used to ignore timestamp fields.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = {"createdAt", "updatedAt", "lastUsedAt"}, ignoreUnknown = true)
    private abstract static class IgnoreTimestampFieldsMixin {
    }

}
