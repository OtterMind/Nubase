package ai.nubase.metadata.repository;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void idempotencyLookupRequiresGrantLineage() {
        assertThatCode(() -> AutomationProjectOperationRepository.class.getMethod(
                "findByActorAndGrantIdAndActionAndIdempotencyKey",
                String.class,
                java.util.UUID.class,
                String.class,
                String.class))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AutomationProjectOperationRepository.class.getMethod(
                "findByActorAndActionAndIdempotencyKey",
                String.class,
                String.class,
                String.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void recoveryCandidatesAreClaimedWithSkipLockedAndAnExplicitDueTime()
            throws Exception {
        Query query = AutomationProjectOperationRepository.class
                .getMethod("findClaimableProvisionCandidates", java.time.Instant.class)
                .getAnnotation(Query.class);

        assertThatCode(() -> CCJSqlParserUtil.parse(query.value()))
                .doesNotThrowAnyException();
        String sql = query.value().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("next_attempt_at <= :dueat")
                .contains("claim_token is null or claimed_until <= :dueat")
                .contains("order by next_attempt_at, created_at, id")
                .contains("limit 100 for update skip locked")
                .doesNotContain("updated_at <=")
                .doesNotContain("for update nowait");
    }

    @Test
    void recoveryClaimsPersistATokenAndBoundedLeaseBeforeProcessing() throws Exception {
        Query claimQuery = AutomationProjectOperationRepository.class
                .getMethod(
                        "claimProvisionCandidates",
                        java.util.List.class,
                        java.util.UUID.class,
                        java.time.Instant.class,
                        java.time.Instant.class,
                        java.time.Instant.class)
                .getAnnotation(Query.class);
        Query recoveryLockQuery = AutomationProjectOperationRepository.class
                .getMethod("findRecoveryLockedById", java.util.UUID.class)
                .getAnnotation(Query.class);

        assertThatCode(() -> CCJSqlParserUtil.parse(claimQuery.value()))
                .doesNotThrowAnyException();
        assertThatCode(() -> CCJSqlParserUtil.parse(recoveryLockQuery.value()))
                .doesNotThrowAnyException();
        String claimSql = claimQuery.value().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        String lockSql = recoveryLockQuery.value().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        assertThat(claimSql)
                .contains("set claim_token = :claimtoken")
                .contains("claimed_until = :claimeduntil")
                .contains("claim_token is null or claimed_until <= :eligibleat");
        assertThat(lockSql).contains("for update nowait");
    }

    @Test
    void grantRecoveryUsesADedicatedNonBlockingLock() throws Exception {
        Query grantLockQuery = AutomationGrantRepository.class
                .getMethod("findRecoveryLockedById", java.util.UUID.class)
                .getAnnotation(Query.class);

        assertThatCode(() -> CCJSqlParserUtil.parse(grantLockQuery.value()))
                .doesNotThrowAnyException();
        assertThat(grantLockQuery.value().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " "))
                .contains("from automation_grants")
                .contains("for update nowait");
    }

    @Test
    void recoveryRequiresSuccessfulCreateGrantAndTokenLineage() {
        assertThatCode(() -> AutomationProjectOperationRepository.class.getMethod(
                "countSuccessfulCreateLineage",
                String.class,
                java.util.UUID.class,
                Long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                java.time.Instant.class))
                .doesNotThrowAnyException();
    }

    @Test
    void migrationTerminalizesLegacyPendingProvisionBeforeTokenVersionBackfill()
            throws Exception {
        String migration = new ClassPathResource(
                "db/migration/V19__harden_platform_provision_outbox.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        int reauthenticationRequired = migration.indexOf(
                "set status = 'failed', response_json = "
                        + "'{\"error\":\"operation_reauth_required\"}'");
        int tokenVersionBackfill = migration.indexOf(
                "update automation_project_operations operation "
                        + "set token_version = grant_row.token_version");
        assertThat(reauthenticationRequired).isGreaterThanOrEqualTo(0);
        assertThat(tokenVersionBackfill).isGreaterThan(reauthenticationRequired);
        assertThat(migration)
                .contains("where action = 'platformprojectprovision' and status = 'pending'")
                .contains("add column attempt_count integer not null default 0")
                .contains("add column next_attempt_at timestamptz")
                .contains("add column claim_token uuid")
                .contains("add column claimed_until timestamptz")
                .contains("chk_automation_project_operation_claim_pair")
                .contains("idx_automation_project_operations_provision_due");
    }
}
