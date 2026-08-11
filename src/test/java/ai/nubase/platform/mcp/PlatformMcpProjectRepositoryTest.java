package ai.nubase.platform.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformMcpProjectRepositoryTest {

    @Test
    void ownershipLookupBindsBothDatabaseKeyAndApplicationCodeToTheReference() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(List.of());
        PlatformMcpProjectRepository repository =
                new PlatformMcpProjectRepository(jdbcTemplate);

        repository.findOwnedByRef("agentteams.local", "goai_notes");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                any(RowMapper.class),
                eq(PlatformMcpProjectRepository.EXTERNAL_PLATFORM),
                eq("agentteams.local"),
                eq("goai_notes"),
                eq("goai_notes"));
        assertThat(sql.getValue())
                .contains("c.db_key = ?")
                .contains("c.app_code = ?");
    }
}
