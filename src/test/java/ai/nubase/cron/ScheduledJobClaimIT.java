package ai.nubase.cron;

import ai.nubase.cron.service.ScheduledJobStore;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.metadata.cron.repository.ScheduledJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the compare-and-set claim semantics against a real database: exactly
 * one claim per occurrence, no re-entry while locked, lock release on complete.
 * This is the multi-instance safety property — it cannot be proven with mocks.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("ScheduledJob claim CAS (dev metadata DB)")
class ScheduledJobClaimIT {

    private static final String PROJECT_REF = "cron-claim-it";

    @Autowired
    private ScheduledJobRepository jobRepository;
    @Autowired
    private ScheduledJobStore store;

    @AfterEach
    void cleanup() {
        jobRepository.deleteAll(jobRepository.findByProjectRefOrderByNameAsc(PROJECT_REF));
    }

    @Test
    void onlyOneClaimSucceedsPerOccurrenceAndLockBlocksReentry() {
        Instant due = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        ScheduledJob job = jobRepository.save(ScheduledJob.builder()
                .projectRef(PROJECT_REF)
                .name("claim-test")
                .cronExpression("0 */5 * * * *")
                .targetType(ScheduledJob.TARGET_DB_FUNCTION)
                .dbFunctionName("noop")
                .enabled(true)
                .nextRunAt(due)
                .build());

        Instant now = Instant.now();
        Instant next = now.plus(5, ChronoUnit.MINUTES);
        Instant lockedUntil = now.plus(10, ChronoUnit.MINUTES);

        // First claim wins; a second instance with the same expected next_run_at loses.
        assertThat(store.claim(job.getId(), due, next, lockedUntil, now)).isTrue();
        assertThat(store.claim(job.getId(), due, next, lockedUntil, now)).isFalse();

        // The job is due again (simulate by matching the advanced next_run_at) but the
        // lock is still held — claim must fail until the lock clears.
        assertThat(store.claim(job.getId(), next, next.plus(5, ChronoUnit.MINUTES), lockedUntil, now)).isFalse();

        // complete() releases the lock; the next occurrence can then be claimed.
        store.complete(job.getId(), next, "success", next);
        assertThat(store.claim(job.getId(), next, next.plus(5, ChronoUnit.MINUTES),
                now.plus(10, ChronoUnit.MINUTES), now)).isTrue();

        ScheduledJob reloaded = jobRepository.findByProjectRefAndName(PROJECT_REF, "claim-test").orElseThrow();
        assertThat(reloaded.getLastStatus()).isEqualTo("success");
    }
}
