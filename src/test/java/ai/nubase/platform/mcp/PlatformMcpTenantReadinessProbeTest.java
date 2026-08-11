package ai.nubase.platform.mcp;

import ai.nubase.ai.gateway.util.GatewayKeyUtil;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import ai.nubase.postgrest.multidb.RoutingDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformMcpTenantReadinessProbeTest {

    private static final String SERVICE_ROLE = "service-role-test-credential";

    @Test
    void completeTenantSchemasPoliciesAndDefaultKeyAreReady() throws Exception {
        PreparedStatement keyStatement = statement(booleanRows(true));
        Connection connection = completeConnection(allPolicies(), keyStatement);
        PlatformMcpTenantReadinessProbe probe = probe();

        var readiness = probe.inspect(connection, config(), true, true, true);

        assertThat(readiness.database()).isTrue();
        assertThat(readiness.auth()).isTrue();
        assertThat(readiness.storage()).isTrue();
        assertThat(readiness.memory()).isTrue();
        assertThat(readiness.assets()).isTrue();
        assertThat(readiness.gateway()).isTrue();
        verify(keyStatement).setString(1, GatewayKeyUtil.sha256Hex(SERVICE_ROLE));
    }

    @Test
    void missingExpectedPolicyFailsOnlyTheAffectedModuleClosed() throws Exception {
        Set<String> policies = allPolicies();
        policies.remove(PlatformMcpTenantReadinessProbe.ASSETS_POLICIES.iterator().next());
        Connection connection = completeConnection(policies, statement(booleanRows(true)));

        var readiness = probe().inspect(connection, config(), true, true, true);

        assertThat(readiness.database()).isTrue();
        assertThat(readiness.auth()).isTrue();
        assertThat(readiness.storage()).isTrue();
        assertThat(readiness.memory()).isTrue();
        assertThat(readiness.assets()).isFalse();
        assertThat(readiness.gateway()).isTrue();
    }

    @Test
    void invalidConnectionStopsBeforeSchemaQueries() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isValid(5)).thenReturn(false);

        var readiness = probe().inspect(connection, config(), true, true, true);

        assertThat(readiness)
                .isEqualTo(PlatformMcpTenantReadinessProbe.TenantReadiness.unavailable());
        verify(connection, never()).prepareStatement(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void configurationDecryptionFailureReturnsOnlyUnavailableBooleans() {
        DatabaseConfigRepository repository = mock(DatabaseConfigRepository.class);
        when(repository.findByDbKey("psx_agent_teams_project"))
                .thenThrow(new IllegalStateException("credential-sentinel"));
        PlatformMcpTenantReadinessProbe probe = new PlatformMcpTenantReadinessProbe(
                repository, mock(RoutingDataSource.class));

        var readiness = probe.check("psx_agent_teams_project", true, true, true);

        assertThat(readiness)
                .isEqualTo(PlatformMcpTenantReadinessProbe.TenantReadiness.unavailable());
    }

    private static PlatformMcpTenantReadinessProbe probe() {
        return new PlatformMcpTenantReadinessProbe(
                mock(DatabaseConfigRepository.class), mock(RoutingDataSource.class));
    }

    private static DatabaseConfig config() {
        return DatabaseConfig.builder()
                .dbKey("psx_agent_teams_project")
                .serviceRoleToken(SERVICE_ROLE)
                .build();
    }

    private static Connection completeConnection(
            Set<String> policies, PreparedStatement keyStatement) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isValid(5)).thenReturn(true);
        PreparedStatement tablesStatement = statement(qualifiedRows(allTables()));
        PreparedStatement rlsStatement = statement(qualifiedRows(allRlsTables()));
        PreparedStatement policiesStatement = statement(policyRows(policies));
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("information_schema.tables")) {
                return tablesStatement;
            }
            if (sql.contains("relation.relrowsecurity")) {
                return rlsStatement;
            }
            if (sql.contains("FROM pg_policies")) {
                return policiesStatement;
            }
            if (sql.contains("FROM ai_gateway.api_keys")) {
                return keyStatement;
            }
            throw new AssertionError("Unexpected readiness query");
        });
        return connection;
    }

    private static PreparedStatement statement(ResultSet rows) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.executeQuery()).thenReturn(rows);
        return statement;
    }

    private static ResultSet qualifiedRows(Collection<String> values) throws Exception {
        List<String[]> rows = values.stream()
                .map(value -> value.split("\\.", 2))
                .toList();
        return stringRows(rows);
    }

    private static ResultSet policyRows(Collection<String> values) throws Exception {
        List<String[]> rows = values.stream()
                .map(value -> {
                    String[] parts = value.split("\\|", 2);
                    String[] table = parts[0].split("\\.", 2);
                    return new String[]{table[0], table[1], parts[1]};
                })
                .toList();
        return stringRows(rows);
    }

    private static ResultSet stringRows(List<String[]> values) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(ignored -> index.incrementAndGet() < values.size());
        when(rows.getString(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation ->
                values.get(index.get())[invocation.getArgument(0, Integer.class) - 1]);
        return rows;
    }

    private static ResultSet booleanRows(boolean value) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getBoolean(1)).thenReturn(value);
        return rows;
    }

    private static Set<String> allTables() {
        Set<String> tables = new LinkedHashSet<>();
        tables.addAll(PlatformMcpTenantReadinessProbe.AUTH_TABLES);
        tables.addAll(PlatformMcpTenantReadinessProbe.STORAGE_TABLES);
        tables.addAll(PlatformMcpTenantReadinessProbe.MEMORY_TABLES);
        tables.addAll(PlatformMcpTenantReadinessProbe.ASSETS_TABLES);
        tables.addAll(PlatformMcpTenantReadinessProbe.GATEWAY_TABLES);
        return tables;
    }

    private static Set<String> allRlsTables() {
        Set<String> tables = allTables();
        tables.removeAll(PlatformMcpTenantReadinessProbe.GATEWAY_TABLES);
        return tables;
    }

    private static Set<String> allPolicies() {
        Set<String> policies = new LinkedHashSet<>();
        policies.addAll(PlatformMcpTenantReadinessProbe.AUTH_POLICIES);
        policies.addAll(PlatformMcpTenantReadinessProbe.STORAGE_POLICIES);
        policies.addAll(PlatformMcpTenantReadinessProbe.MEMORY_POLICIES);
        policies.addAll(PlatformMcpTenantReadinessProbe.ASSETS_POLICIES);
        return policies;
    }
}
