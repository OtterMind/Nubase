package ai.nubase.postgrest.multidb;

import ai.nubase.common.enums.DatabaseInitStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConfigRepositoryCreateOnlyTest {

    @Test
    void insertIfAbsentUsesOneAtomicPostgresStatementAndMapsConflictToFalse() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.isEncrypted(anyString())).thenReturn(true);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        DatabaseConfigRepository repository = new DatabaseConfigRepository(
                jdbcTemplate, encryptionService);

        assertThat(repository.insertIfAbsent(config())).isTrue();
        assertThat(repository.insertIfAbsent(config())).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement)
                .contains("INSERT INTO public.database_configs")
                .contains("ON CONFLICT (db_key) DO NOTHING")
                .doesNotContain("UPDATE public.database_configs"));
    }

    private static DatabaseConfig config() {
        return DatabaseConfig.builder()
                .dbKey("goai_notes")
                .dbName("goai_notes")
                .jdbcUrl("jdbc:postgresql://localhost:5432/goai_notes")
                .dbUser("goai_notes_user")
                .dbPasswordEncrypted("encrypted-password")
                .dbSchemas(List.of("public"))
                .enabled(false)
                .appCode("goai_notes")
                .appName("Notes")
                .initStatus(DatabaseInitStatus.PENDING_INIT.name())
                .build();
    }
}
