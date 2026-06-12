package ai.nubase.cron;

import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.metadata.cron.repository.ScheduledJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the claim-erasure race: the admin path loads the entity,
 * a runner claims the job (writing locked_until) AFTER that load, and the admin
 * transaction then flushes. Without @DynamicUpdate the flush was a full-column
 * UPDATE that wrote the load-time locked_until=null snapshot back, erasing the
 * live claim and allowing the same occurrence to run twice. With @DynamicUpdate
 * only the admin-touched columns are written and the claim must survive.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("ScheduledJob admin-update vs claim race (dev metadata DB)")
class ScheduledJobUpdateRaceIT {

    private static final String PROJECT_REF = "cron-update-race-it";

    @Autowired
    private ScheduledJobRepository jobRepository;
    @Autowired
    @Qualifier("metadataTransactionManager")
    private PlatformTransactionManager metadataTransactionManager;

    @AfterEach
    void cleanup() {
        jobRepository.deleteAll(jobRepository.findByProjectRefOrderByNameAsc(PROJECT_REF));
    }

    @Test
    void adminUpdateDoesNotEraseAConcurrentClaim() {
        Instant due = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        ScheduledJob job = jobRepository.save(ScheduledJob.builder()
                .projectRef(PROJECT_REF)
                .name("race-test")
                .cronExpression("0 */5 * * * *")
                .targetType(ScheduledJob.TARGET_DB_FUNCTION)
                .dbFunctionName("noop")
                .enabled(true)
                .nextRunAt(due)
                .build());

        Instant claimedNext = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant lockToken = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        TransactionTemplate adminTx = new TransactionTemplate(metadataTransactionManager);
        adminTx.executeWithoutResult(status -> {
            // Admin loads the entity — its snapshot has locked_until = null.
            ScheduledJob loaded = jobRepository.findById(job.getId()).orElseThrow();

            // A runner claims the job in a separate committed transaction.
            TransactionTemplate claimTx = new TransactionTemplate(metadataTransactionManager);
            claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            claimTx.executeWithoutResult(s2 -> {
                int claimed = jobRepository.claim(job.getId(), due, claimedNext, lockToken, Instant.now());
                assertThat(claimed).isEqualTo(1);
            });

            // Admin mutates an unrelated field and commits — exactly what
            // ScheduledJobAdminService.updateJob does.
            loaded.setDescription("changed while claimed");
            jobRepository.save(loaded);
        });

        ScheduledJob after = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(after.getDescription()).isEqualTo("changed while claimed");
        assertThat(after.getLockedUntil())
                .as("the runner's claim lock must survive the admin update flush")
                .isEqualTo(lockToken);
        assertThat(after.getNextRunAt())
                .as("the claimed next_run_at must survive the admin update flush")
                .isEqualTo(claimedNext);
    }
}
