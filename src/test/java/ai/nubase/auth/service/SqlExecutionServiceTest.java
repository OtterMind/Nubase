package ai.nubase.auth.service;

import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.metadata.entity.SqlExecutionRecord;
import ai.nubase.metadata.repository.SqlExecutionRecordRepository;
import ai.nubase.postgrest.multidb.DatabaseConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SqlExecutionServiceTest {

    @AfterEach
    void tearDown() {
        MultiTenancyContext.clear();
    }

    @Test
    void executeSqlReturnsGenericErrorAndDoesNotPersistExceptionDetails() throws Exception {
        String sql = "select sensitive_value from private_table";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlExecutionRecordRepository repository = mock(SqlExecutionRecordRepository.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(sql)).thenThrow(new SQLException("sensitive driver detail"));
        setInitializedContext();

        var response = new SqlExecutionService(jdbcTemplate, repository).executeSql(request(sql));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).isEqualTo("SQL execution failed");
        assertThat(response.getError()).doesNotContain("sensitive driver detail");
        ArgumentCaptor<SqlExecutionRecord> record = ArgumentCaptor.forClass(SqlExecutionRecord.class);
        verify(repository).save(record.capture());
        assertThat(record.getValue().getErrorMessage()).isEqualTo("SQL execution failed");
        assertThat(record.getValue().getErrorStackTrace()).isNull();
        assertThat(record.getValue().getExecutionResult()).doesNotContain("sensitive driver detail");
    }

    @Test
    void executionAuditStoresOnlyResultMetadata() throws Exception {
        String sql = "select secret_column from private_table";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlExecutionRecordRepository repository = mock(SqlExecutionRecordRepository.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(sql)).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(statement.getMoreResults()).thenReturn(false);
        when(statement.getUpdateCount()).thenReturn(-1);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("secret_column");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("sensitive result value");
        setInitializedContext();

        var response = new SqlExecutionService(jdbcTemplate, repository).executeSql(request(sql));

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<SqlExecutionRecord> record = ArgumentCaptor.forClass(SqlExecutionRecord.class);
        verify(repository).save(record.capture());
        assertThat(record.getValue().getExecutionResult()).contains("statementCount");
        assertThat(record.getValue().getExecutionResult()).doesNotContain("sensitive result value");
        assertThat(record.getValue().getExecutionResult()).doesNotContain("secret_column");
    }

    @Test
    void dryRunReturnsGenericErrorWithoutWritingAuditRecord() throws Exception {
        String sql = "select sensitive_value from private_table";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlExecutionRecordRepository repository = mock(SqlExecutionRecordRepository.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(invocation -> {
            if (sql.equals(invocation.getArgument(0))) {
                throw new SQLException("sensitive driver detail");
            }
            return false;
        });
        setInitializedContext();

        var response = new SqlExecutionService(jdbcTemplate, repository).dryRunSql(request(sql));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getError()).isEqualTo("SQL dry-run failed");
        assertThat(response.getError()).doesNotContain("sensitive driver detail");
        verify(connection).rollback();
        verifyNoInteractions(repository);
    }

    private void setInitializedContext() {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("demo")
                .databaseKey("demo")
                .schemaName("public")
                .serviceRole(true)
                .databaseConfig(DatabaseConfig.builder()
                        .dbKey("demo")
                        .schemaName("public")
                        .dbSchemas(List.of("public"))
                        .initStatus(DatabaseInitStatus.INITIALIZED.name())
                        .build())
                .build());
    }

    private ExecuteSqlRequest request(String sql) {
        ExecuteSqlRequest request = new ExecuteSqlRequest();
        request.setQuery(sql);
        return request;
    }
}
