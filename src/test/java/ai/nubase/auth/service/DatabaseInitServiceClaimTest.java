package ai.nubase.auth.service;

import ai.nubase.ai.gateway.service.DefaultGatewayKeyProvisioner;
import ai.nubase.auth.dto.request.admin.InitDatabaseRequest;
import ai.nubase.auth.dto.response.admin.InitDatabaseResponse;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseInitServiceClaimTest {

    @Test
    void doesNotInitializeOrOverwriteStatusWhenAnotherWorkerOwnsLease() throws Exception {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        DefaultGatewayKeyProvisioner keyProvisioner = mock(DefaultGatewayKeyProvisioner.class);
        ProjectProvisioningLeaseService leaseService = mock(ProjectProvisioningLeaseService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseConfig config = DatabaseConfig.builder()
                .dbKey("db-demo")
                .initStatus(DatabaseInitStatus.INITIALIZING.name())
                .build();
        when(repository.findByDbKey("db-demo")).thenReturn(config);
        when(leaseService.tryAcquire("db-demo")).thenReturn(Optional.empty());
        DatabaseInitService service = new DatabaseInitService(
                repository,
                encryptionService,
                keyProvisioner,
                leaseService,
                jdbcTemplate);

        InitDatabaseResponse response = service.initializePhysicalDatabase("db-demo");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).isEqualTo("Database initialization is already in progress");
        verify(leaseService).tryAcquire(eq("db-demo"));
        verify(encryptionService, never()).decrypt(any());
    }

    @Test
    void newConfigurationRemainsDisabledAndUnavailableUntilInitializationCompletes() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        DatabaseInitService service = new DatabaseInitService(
                repository,
                mock(EncryptionService.class),
                mock(DefaultGatewayKeyProvisioner.class),
                mock(ProjectProvisioningLeaseService.class),
                mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(service, "postgresHost", "localhost");
        ReflectionTestUtils.setField(service, "postgresPort", 5432);
        InitDatabaseRequest request = new InitDatabaseRequest();
        request.setDbKey("db-demo");
        request.setDbName("db_demo");
        request.setAppCode("demo");
        request.setAppName("Demo");

        InitDatabaseResponse response = service.createDatabaseConfig(request);

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<DatabaseConfig> savedConfig = ArgumentCaptor.forClass(DatabaseConfig.class);
        verify(repository).save(savedConfig.capture());
        assertThat(savedConfig.getValue().getInitStatus())
                .isEqualTo(DatabaseInitStatus.PENDING_INIT.name());
        assertThat(savedConfig.getValue().getEnabled()).isFalse();
        assertThat(savedConfig.getValue().isAvailable()).isFalse();
    }

    @Test
    void createOnlyUsesAtomicInsertAndReturnsStableReferenceConflict() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        when(repository.insertIfAbsent(any(DatabaseConfig.class))).thenReturn(false);
        DatabaseInitService service = new DatabaseInitService(
                repository,
                mock(EncryptionService.class),
                mock(DefaultGatewayKeyProvisioner.class),
                mock(ProjectProvisioningLeaseService.class),
                mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(service, "postgresHost", "localhost");
        ReflectionTestUtils.setField(service, "postgresPort", 5432);
        InitDatabaseRequest request = new InitDatabaseRequest();
        request.setDbKey("goai_notes");
        request.setDbName("goai_notes");
        request.setAppCode("goai_notes");
        request.setAppName("Notes");

        InitDatabaseResponse response = service.createDatabaseConfigIfAbsent(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorDetails()).isEqualTo(DatabaseInitService.PROJECT_REF_EXISTS);
        verify(repository).insertIfAbsent(any(DatabaseConfig.class));
        verify(repository, never()).findByDbKey(anyString());
        verify(repository, never()).save(any(DatabaseConfig.class));
    }

    @Test
    void provisionsDefaultGatewayKeyBeforePublishingSuccessAndEnabledState() {
        DefaultGatewayKeyProvisioner keyProvisioner = mock(DefaultGatewayKeyProvisioner.class);
        DatabaseInitService service = new DatabaseInitService(
                mock(DatabaseConfigRepository.class),
                mock(EncryptionService.class),
                keyProvisioner,
                mock(ProjectProvisioningLeaseService.class),
                mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(service, "aiGatewayEnabled", true);
        ProjectProvisioningLeaseService.LeaseHandle lease =
                mock(ProjectProvisioningLeaseService.LeaseHandle.class);
        when(lease.complete("Physical database initialized successfully")).thenReturn(true);
        DataSource tenantDataSource = mock(DataSource.class);
        DatabaseConfig databaseConfig = DatabaseConfig.builder()
                .dbKey("db-demo")
                .serviceRoleToken("test-service-role-token")
                .build();

        service.publishInitializedDatabase(
                tenantDataSource,
                databaseConfig,
                lease,
                new ArrayList<>());

        InOrder order = inOrder(lease, keyProvisioner);
        order.verify(lease).renewOrThrow();
        order.verify(keyProvisioner).provision(tenantDataSource, "test-service-role-token");
        order.verify(lease).complete("Physical database initialized successfully");
    }

    @Test
    void doesNotExposeRepositoryFailureDetails(CapturedOutput output) {
        String sentinel = "sentinel-config-secret-should-not-escape";
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        doThrow(new IllegalStateException(sentinel)).when(repository).save(any(DatabaseConfig.class));
        DatabaseInitService service = new DatabaseInitService(
                repository,
                mock(EncryptionService.class),
                mock(DefaultGatewayKeyProvisioner.class),
                mock(ProjectProvisioningLeaseService.class),
                mock(JdbcTemplate.class));
        ReflectionTestUtils.setField(service, "postgresHost", "localhost");
        ReflectionTestUtils.setField(service, "postgresPort", 5432);
        InitDatabaseRequest request = new InitDatabaseRequest();
        request.setDbKey("db-demo");
        request.setDbName("db_demo");
        request.setAppCode("demo");

        InitDatabaseResponse response = service.createDatabaseConfig(request);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).doesNotContain(sentinel);
        assertThat(response.getErrorDetails()).doesNotContain(sentinel);
        assertThat(output).doesNotContain(sentinel);
    }

    @Test
    void doesNotExposePasswordBearingSqlFailures(CapturedOutput output) {
        String sentinel = "sentinel-db-password-should-not-escape";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("db_demo")))
                .thenReturn(java.util.List.of("db_demo_user"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("db_demo_user")))
                .thenReturn(true);
        doThrow(new IllegalStateException("Driver failure: " + sentinel))
                .when(jdbcTemplate).execute(anyString());
        DatabaseInitService service = new DatabaseInitService(
                mock(DatabaseConfigRepository.class),
                mock(EncryptionService.class),
                mock(DefaultGatewayKeyProvisioner.class),
                mock(ProjectProvisioningLeaseService.class),
                jdbcTemplate);

        assertThatThrownBy(() -> service.createDatabaseAndUser(
                "db_demo",
                "db_demo_user",
                sentinel,
                true,
                new ArrayList<>()))
                .hasMessageNotContaining(sentinel);
        assertThat(output).doesNotContain(sentinel);
    }
}
