package ai.nubase.mcp.safety;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRiskClassifierTest {

    private static final Path SHARED_CASES = Path.of("test-fixtures", "sql-risk-cases.json");
    private final SqlRiskClassifier classifier = new SqlRiskClassifier();

    @Test
    void matchesSharedRiskAndStatementBoundaryContract() throws Exception {
        for (RiskCase fixture : loadCases()) {
            assertThat(classifier.classify(fixture.sql()))
                    .as("%s: risk", fixture.name())
                    .isEqualTo(SqlRisk.valueOf(fixture.risk()));
            assertThat(classifier.countStatements(fixture.sql()))
                    .as("%s: statement count", fixture.name())
                    .isEqualTo(fixture.statementCount());
        }
    }

    @Test
    void reportsUnknownStatementsIndependentlyOfHighestKnownRisk() throws Exception {
        Optional<Method> analyzeMethod = Arrays.stream(SqlRiskClassifier.class.getMethods())
                .filter(method -> method.getName().equals("analyze"))
                .filter(method -> Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class}))
                .findFirst();
        assertThat(analyzeMethod).as("SqlRiskClassifier must expose analyze(String)").isPresent();

        Method analyze = analyzeMethod.orElseThrow();
        for (RiskCase fixture : loadCases()) {
            Object result = analyze.invoke(classifier, fixture.sql());
            assertThat(result.getClass().getMethod("risk").invoke(result))
                    .as("%s: detailed risk", fixture.name())
                    .isEqualTo(SqlRisk.valueOf(fixture.risk()));
            assertThat(result.getClass().getMethod("statementCount").invoke(result))
                    .as("%s: detailed statement count", fixture.name())
                    .isEqualTo(fixture.statementCount());
            assertThat(result.getClass().getMethod("hasUnknown").invoke(result))
                    .as("%s: sticky unknown", fixture.name())
                    .isEqualTo(fixture.hasUnknown());
        }
    }

    private List<RiskCase> loadCases() throws Exception {
        String json = Files.readString(SHARED_CASES);
        return new ObjectMapper().readValue(json, new TypeReference<>() {});
    }

    private record RiskCase(
            String name,
            String sql,
            String risk,
            int statementCount,
            boolean hasUnknown
    ) {}
}
