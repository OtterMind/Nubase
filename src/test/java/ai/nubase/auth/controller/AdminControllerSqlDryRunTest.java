package ai.nubase.auth.controller;

import ai.nubase.auth.dto.request.admin.ExecuteSqlRequest;
import ai.nubase.auth.dto.response.admin.SqlExecutionResponse;
import ai.nubase.auth.service.AdminService;
import ai.nubase.auth.service.DatabaseInitService;
import ai.nubase.auth.service.PlatformExternalIdentityService;
import ai.nubase.auth.service.ProjectOwnershipService;
import ai.nubase.auth.service.RlsPolicyExportService;
import ai.nubase.auth.service.SchemaDdlExportService;
import ai.nubase.auth.service.SchemaInitService;
import ai.nubase.auth.service.SqlExecutionService;
import ai.nubase.metadata.repository.PlatformUserProjectRepository;
import ai.nubase.metadata.repository.PlatformUserRepository;
import ai.nubase.metadata.repository.SqlExecutionRecordRepository;
import ai.nubase.metadata.repository.SqlSnippetRepository;
import ai.nubase.postgrest.multidb.DatabaseConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static ai.nubase.test.ControllerTestSupport.json;
import static ai.nubase.test.ControllerTestSupport.mockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerSqlDryRunTest {

    private SqlExecutionService sqlExecutionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sqlExecutionService = mock(SqlExecutionService.class);
        mvc = mockMvc(new AdminController(
                mock(AdminService.class),
                sqlExecutionService,
                mock(SchemaInitService.class),
                mock(DatabaseInitService.class),
                mock(SchemaDdlExportService.class),
                mock(RlsPolicyExportService.class),
                mock(DatabaseConfigRepository.class),
                mock(PlatformUserRepository.class),
                mock(PlatformUserProjectRepository.class),
                mock(SqlSnippetRepository.class),
                mock(SqlExecutionRecordRepository.class),
                mock(PlatformExternalIdentityService.class),
                mock(ProjectOwnershipService.class)
        ));
    }

    @Test
    void dryRunSqlReturnsOkWhenValidationSucceeds() throws Exception {
        when(sqlExecutionService.dryRunSql(any(ExecuteSqlRequest.class)))
                .thenReturn(SqlExecutionResponse.successWithResults(List.of(), 9));

        ExecuteSqlRequest request = new ExecuteSqlRequest();
        request.setQuery("create table todos(id uuid primary key);");

        mvc.perform(post("/auth/v1/admin/sql/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.execution_time_ms").value(9));

        verify(sqlExecutionService).dryRunSql(any(ExecuteSqlRequest.class));
    }

    @Test
    void dryRunSqlReturnsBadRequestWhenValidationFails() throws Exception {
        when(sqlExecutionService.dryRunSql(any(ExecuteSqlRequest.class)))
                .thenReturn(SqlExecutionResponse.error("syntax error at or near \"table\"", 4));

        ExecuteSqlRequest request = new ExecuteSqlRequest();
        request.setQuery("create table");

        mvc.perform(post("/auth/v1/admin/sql/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("syntax error at or near \"table\""))
                .andExpect(jsonPath("$.execution_time_ms").value(4));
    }
}
