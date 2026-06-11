package ai.nubase.cron.target;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.cron.CronProperties;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.postgrest.api.ApiRequest;
import ai.nubase.postgrest.auth.PostgrestRequestContext;
import ai.nubase.postgrest.query.QueryExecutor;
import ai.nubase.postgrest.query.QueryPlan;
import ai.nubase.postgrest.query.QueryPlanner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Calls a named Postgres function in the tenant schema, reusing the PostgREST
 * RPC engine's SQL generation (named-arg rendering, identifier quoting) but
 * executing through its own statement so a per-job query timeout applies —
 * essential on a shared cluster where one tenant's slow function must not pin
 * connections indefinitely.
 *
 * Deliberately restricted to "call one named function": arbitrary SQL snippets
 * (which Supabase allows, on tenant-dedicated databases) are not accepted.
 * Users wanting arbitrary SQL can CREATE FUNCTION first — same expressive
 * power, but the side effect has a name and an auditable surface.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class DbFunctionJobTarget implements ScheduledJobTarget {

    public static final Pattern FUNCTION_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,127}$");
    private static final int RESULT_SNIPPET_MAX = 2000;

    private final QueryPlanner queryPlanner;
    private final QueryExecutor queryExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CronProperties properties;
    private final PostgrestRequestContext postgrestRequestContext;

    @Override
    public String type() {
        return ScheduledJob.TARGET_DB_FUNCTION;
    }

    @Override
    @Transactional
    public RunOutcome execute(ScheduledJob job) throws Exception {
        String functionName = job.getDbFunctionName();
        if (!StringUtils.hasText(functionName) || !FUNCTION_NAME.matcher(functionName).matches()) {
            return RunOutcome.failure(null, "INVALID_DB_FUNCTION: " + functionName);
        }
        Map<String, Object> args = parseArgs(job.getDbFunctionArgs());

        ApiRequest apiRequest = ApiRequest.builder()
                .schema(MultiTenancyContext.getSchemaName())
                .table(functionName)
                .method("POST")
                .headers(Map.of())
                .queryParams(List.of())
                .rpcCall(true)
                .rpcFunctionName(functionName)
                .rpcParams(args)
                .body(job.getDbFunctionArgs())
                .build();
        QueryPlan plan = queryPlanner.plan(apiRequest);
        String sql = queryExecutor.buildSqlForPlan(plan, job.getDbFunctionArgs());
        postgrestRequestContext.applySynthetic("service_role", "/cron/" + job.getName(), "POST");

        int timeoutSeconds = job.getTimeoutSeconds() == null
                ? properties.getDefaultTimeoutSeconds()
                : job.getTimeoutSeconds();
        List<Map<String, Object>> rows = jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            if (timeoutSeconds > 0) {
                // The Postgres driver enforces this by cancelling the statement
                // server-side, so a runaway function is actually stopped.
                ps.setQueryTimeout(timeoutSeconds);
            }
            return ps;
        }, new ColumnMapRowMapper());

        return RunOutcome.success(snippet(rows));
    }

    private Map<String, Object> parseArgs(String argsJson) throws Exception {
        if (!StringUtils.hasText(argsJson)) {
            return Map.of();
        }
        return objectMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {
        });
    }

    private String snippet(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "0 rows";
        }
        try {
            String json = objectMapper.writeValueAsString(rows.size() == 1 ? rows.get(0) : rows);
            return json.length() <= RESULT_SNIPPET_MAX ? json : json.substring(0, RESULT_SNIPPET_MAX);
        } catch (Exception e) {
            return rows.size() + " rows";
        }
    }
}
