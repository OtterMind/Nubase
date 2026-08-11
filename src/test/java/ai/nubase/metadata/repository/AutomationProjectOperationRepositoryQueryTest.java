package ai.nubase.metadata.repository;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.assertj.core.api.Assertions.assertThatCode;

class AutomationProjectOperationRepositoryQueryTest {

    @Test
    void pendingInsertIsAcceptedByTheRuntimeSqlParser() throws Exception {
        Query query = AutomationProjectOperationRepository.class
                .getMethod(
                        "insertPendingIfAbsent",
                        java.util.UUID.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        java.util.UUID.class,
                        String.class,
                        java.time.Instant.class)
                .getAnnotation(Query.class);

        assertThatCode(() -> CCJSqlParserUtil.parse(query.value()))
                .doesNotThrowAnyException();
    }
}
