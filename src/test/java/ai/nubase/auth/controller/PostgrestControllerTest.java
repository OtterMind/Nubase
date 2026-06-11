package ai.nubase.auth.controller;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.postgrest.api.ApiRequest;
import ai.nubase.postgrest.api.ApiRequestParser;
import ai.nubase.postgrest.api.Preferences;
import ai.nubase.postgrest.api.RangeHeader;
import ai.nubase.postgrest.auth.PostgrestRequestContext;
import ai.nubase.postgrest.query.QueryExecutor;
import ai.nubase.postgrest.query.QueryPlan;
import ai.nubase.postgrest.query.QueryPlanner;
import ai.nubase.postgrest.query.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgrestControllerTest {

    private ApiRequestParser requestParser;
    private QueryPlanner queryPlanner;
    private QueryExecutor queryExecutor;
    private JdbcTemplate jdbcTemplate;
    private PostgrestController controller;

    @BeforeEach
    void setUp() {
        requestParser = mock(ApiRequestParser.class);
        queryPlanner = mock(QueryPlanner.class);
        queryExecutor = mock(QueryExecutor.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new PostgrestController(
                requestParser,
                queryPlanner,
                queryExecutor,
                new ObjectMapper(),
                new PostgrestRequestContext(jdbcTemplate, new ObjectMapper()));
    }

    @AfterEach
    void tearDown() {
        MultiTenancyContext.forceClear();
    }

    @Test
    void handleRequestRequiresTenantContext() {
        ResponseEntity<?> response = controller.handleRequest(
                request("GET", "/rest/v1/todos"),
                new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"Missing tenant context\"}");
    }

    @Test
    void handleGetExecutesPlannedQueryAndReturnsJsonArray() throws Exception {
        setTenantContext("public", true);
        MockHttpServletRequest request = request("GET", "/rest/v1/todos");
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ApiRequest apiRequest = apiRequest("public", "todos", "GET", Map.of("accept", "application/json"));
        QueryPlan plan = QueryPlan.builder()
                .type(QueryPlan.QueryType.SELECT)
                .schema("public")
                .table("todos")
                .build();
        when(requestParser.parse(request, "public", "todos")).thenReturn(apiRequest);
        when(queryPlanner.plan(apiRequest)).thenReturn(plan);
        when(queryExecutor.execute(plan, null, null)).thenReturn(QueryResult.builder()
                .data(List.of(Map.of("id", 1, "text", "ship tests")))
                .totalCount(1)
                .build());

        ResponseEntity<?> response = controller.handleRequest(request, servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(response.getBody()).isInstanceOf(String.class);
        var json = new ObjectMapper().readTree((String) response.getBody());
        assertThat(json.get(0).get("id").asInt()).isEqualTo(1);
        assertThat(json.get(0).get("text").asText()).isEqualTo("ship tests");
        assertThat(servletResponse.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        verify(jdbcTemplate).execute("SET LOCAL ROLE \"service_role\"");
        verify(jdbcTemplate).execute("RESET ROLE");
    }

    @Test
    void acceptProfileOverridesSchemaForReadRequests() throws Exception {
        setTenantContext("public", true);
        MockHttpServletRequest request = request("GET", "/rest/v1/memories");
        request.addHeader("Accept-Profile", "mem");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ApiRequest apiRequest = apiRequest("mem", "memories", "GET", Map.of());
        QueryPlan plan = QueryPlan.builder().type(QueryPlan.QueryType.SELECT).schema("mem").table("memories").build();
        when(requestParser.parse(request, "mem", "memories")).thenReturn(apiRequest);
        when(queryPlanner.plan(apiRequest)).thenReturn(plan);
        when(queryExecutor.execute(plan, null, null)).thenReturn(QueryResult.builder()
                .data(List.of())
                .totalCount(0)
                .build());

        ResponseEntity<?> response = controller.handleRequest(request, servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(requestParser).parse(request, "mem", "memories");
    }

    @Test
    void invalidProfileHeaderFallsBackToDefaultSchema() throws Exception {
        setTenantContext("public", true);
        MockHttpServletRequest request = request("GET", "/rest/v1/todos");
        request.addHeader("Accept-Profile", "mem;drop table users");

        ApiRequest apiRequest = apiRequest("public", "todos", "GET", Map.of());
        QueryPlan plan = QueryPlan.builder().type(QueryPlan.QueryType.SELECT).schema("public").table("todos").build();
        when(requestParser.parse(request, "public", "todos")).thenReturn(apiRequest);
        when(queryPlanner.plan(apiRequest)).thenReturn(plan);
        when(queryExecutor.execute(plan, null, null)).thenReturn(QueryResult.builder().data(List.of()).build());

        ResponseEntity<?> response = controller.handleRequest(request, new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(requestParser).parse(request, "public", "todos");
    }

    @Test
    void objectAcceptHeaderReturnsFirstRowAsObject() throws Exception {
        setTenantContext("public", true);
        MockHttpServletRequest request = request("GET", "/rest/v1/todos");
        String objectAccept = "application/vnd.pgrst.object+json";

        ApiRequest apiRequest = apiRequest("public", "todos", "GET", Map.of("accept", objectAccept));
        QueryPlan plan = QueryPlan.builder().type(QueryPlan.QueryType.SELECT).schema("public").table("todos").build();
        when(requestParser.parse(request, "public", "todos")).thenReturn(apiRequest);
        when(queryPlanner.plan(apiRequest)).thenReturn(plan);
        when(queryExecutor.execute(plan, null, null)).thenReturn(QueryResult.builder()
                .data(List.of(Map.of("id", 7, "text", "single row")))
                .build());

        ResponseEntity<?> response = controller.handleRequest(request, new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo(objectAccept);
        assertThat(response.getBody()).isInstanceOf(String.class);
        var json = new ObjectMapper().readTree((String) response.getBody());
        assertThat(json.get("id").asInt()).isEqualTo(7);
        assertThat(json.get("text").asText()).isEqualTo("single row");
    }

    @Test
    void rangeRequestSetsContentRangeHeader() throws Exception {
        setTenantContext("public", true);
        MockHttpServletRequest request = request("GET", "/rest/v1/todos");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ApiRequest apiRequest = apiRequest("public", "todos", "GET", Map.of());
        apiRequest.setRange(RangeHeader.builder().unit("items").start(10L).end(11L).build());
        QueryPlan plan = QueryPlan.builder().type(QueryPlan.QueryType.SELECT).schema("public").table("todos").build();
        when(requestParser.parse(request, "public", "todos")).thenReturn(apiRequest);
        when(queryPlanner.plan(apiRequest)).thenReturn(plan);
        when(queryExecutor.execute(plan, null, null)).thenReturn(QueryResult.builder()
                .data(List.of(Map.of("id", 11), Map.of("id", 12)))
                .totalCount(42)
                .build());

        ResponseEntity<?> response = controller.handleRequest(request, servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(servletResponse.getHeader("Content-Range")).isEqualTo("items 10-11/42");
    }

    @Test
    void returnMinimalPreferenceSuppressesResponseBody() throws Exception {
        setTenantContext("public", true);
        MockHttpServletRequest request = request("POST", "/rest/v1/todos");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        Preferences preferences = Preferences.builder()
                .returnPreference(Preferences.ReturnPreference.MINIMAL)
                .build();
        ApiRequest apiRequest = apiRequest("public", "todos", "POST", Map.of());
        apiRequest.setPreferences(preferences);
        QueryPlan plan = QueryPlan.builder().type(QueryPlan.QueryType.INSERT).schema("public").table("todos").build();
        when(requestParser.parse(request, "public", "todos")).thenReturn(apiRequest);
        when(queryPlanner.plan(apiRequest)).thenReturn(plan);
        when(queryExecutor.execute(plan, null, preferences)).thenReturn(QueryResult.builder().data(List.of()).build());

        ResponseEntity<?> response = controller.handleRequest(request, servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNull();
        assertThat(servletResponse.getHeader("Preference-Applied")).isEqualTo("return=minimal");
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private ApiRequest apiRequest(String schema, String table, String method, Map<String, String> headers) {
        return ApiRequest.builder()
                .schema(schema)
                .table(table)
                .method(method)
                .headers(headers)
                .build();
    }

    private void setTenantContext(String schema, boolean serviceRole) {
        MultiTenancyContext.setContext(MultiTenancyContext.ContextData.builder()
                .appCode("test-project")
                .databaseKey("test-project")
                .schemaName(schema)
                .jwtSecret("test-jwt-secret-test-jwt-secret-test-jwt-secret")
                .apikey("service-role-token")
                .serviceRole(serviceRole)
                .build());
    }
}
