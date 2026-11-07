package ai.nubase.postgrest.query;

import ai.nubase.postgrest.multidb.SchemaCacheManager;
import ai.nubase.postgrest.schema.Routine;
import ai.nubase.postgrest.schema.SchemaCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PostgREST returns scalar function results as bare JSON values. Clients such as
 * supabase-js rely on this: `rpc('allow_login')` must yield `true`, not
 * `[{"result": true}]`.
 */
@DisplayName("RPC scalar result shape")
class RpcScalarResultTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaCache schemaCache;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        schemaCache = mock(SchemaCache.class);
        SchemaCacheManager schemaCacheManager = mock(SchemaCacheManager.class);
        when(schemaCacheManager.getSchemaCache(any())).thenReturn(schemaCache);
        executor = new QueryExecutor(jdbcTemplate, new ObjectMapper(), schemaCacheManager);
    }

    private QueryResult call(String function, Routine routine, List<Map<String, Object>> rows) {
        when(schemaCache.getRoutine("public", function)).thenReturn(routine);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(rows);
        QueryPlan plan = QueryPlan.builder()
                .type(QueryPlan.QueryType.CALL_FUNCTION)
                .schema("public")
                .table(function)
                .build();
        return executor.execute(plan, null, null);
    }

    private static Routine routine(String name, String returnType, boolean returnsSet, boolean returnsComposite) {
        return Routine.builder()
                .schema("public")
                .name(name)
                .returnType(returnType)
                .returnsSet(returnsSet)
                .returnsComposite(returnsComposite)
                .parameters(new ArrayList<>())
                .build();
    }

    private static Map<String, Object> row(String column, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(column, value);
        return row;
    }

    @Test
    @DisplayName("boolean function returns the bare value")
    void scalarFunctionUnwrapsResult() {
        QueryResult result = call("allow_login", routine("allow_login", "boolean", false, false),
                List.of(row("result", true)));

        assertThat(result.isScalar()).isTrue();
        assertThat(result.getScalarValue()).isEqualTo(true);
    }

    @Test
    @DisplayName("SETOF scalar function returns an array of values")
    void setOfScalarFunctionReturnsValues() {
        QueryResult result = call("ids", routine("ids", "SETOF integer", true, false),
                List.of(row("ids", 1), row("ids", 2)));

        assertThat(result.isScalar()).isTrue();
        assertThat(result.getScalarValue()).isEqualTo(List.of(1, 2));
    }

    @Test
    @DisplayName("void function returns null")
    void voidFunctionReturnsNull() {
        QueryResult result = call("touch", routine("touch", "void", false, false),
                List.of(row("result", "")));

        assertThat(result.isScalar()).isTrue();
        assertThat(result.getScalarValue()).isNull();
    }

    @Test
    @DisplayName("composite and TABLE results keep the row shape")
    void compositeAndTableResultsKeepRows() {
        List<Map<String, Object>> rows = List.of(Map.of("id", 1, "name", "a"));

        assertThat(call("one_user", routine("one_user", "users", false, true), rows).isScalar()).isFalse();
        assertThat(call("users_table", routine("users_table", "TABLE(id integer, name text)", true, false), rows)
                .isScalar()).isFalse();
        assertThat(call("records", routine("records", "SETOF record", true, false), rows).isScalar()).isFalse();
    }

    @Test
    @DisplayName("unknown routine keeps the previous row shape")
    void unknownRoutineKeepsRows() {
        QueryResult result = call("missing", null, List.of(row("result", 1)));

        assertThat(result.isScalar()).isFalse();
        assertThat(result.getData()).containsExactly(row("result", 1));
    }
}
