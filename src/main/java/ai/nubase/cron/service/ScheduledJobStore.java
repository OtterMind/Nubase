package ai.nubase.cron.service;

import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.metadata.cron.entity.ScheduledJobRun;
import ai.nubase.metadata.cron.repository.ScheduledJobRepository;
import ai.nubase.metadata.cron.repository.ScheduledJobRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Short metadata-DB transactions for the runner. The runner itself is never
 * transactional — job execution is remote work that must not hold a pool
 * connection (same rule as the functions invocation path).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobStore {

    private final ScheduledJobRepository jobRepository;
    private final ScheduledJobRunRepository runRepository;

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<ScheduledJob> findDue(Instant now, int limit) {
        return jobRepository.findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now, PageRequest.of(0, limit));
    }

    @Transactional("metadataTransactionManager")
    public boolean claim(UUID jobId, Instant expectedNextRunAt, Instant nextRunAt, Instant lockedUntil, Instant now) {
        return jobRepository.claim(jobId, expectedNextRunAt, nextRunAt, lockedUntil, now) == 1;
    }

    @Transactional("metadataTransactionManager")
    public boolean complete(UUID jobId, Instant expectedNextRunAt, String status, Instant nextRunAt) {
        return jobRepository.complete(jobId, expectedNextRunAt, status, nextRunAt) == 1;
    }

    // REQUIRES_NEW for symmetry with the functions invocation log: the run row must
    // survive whatever the caller does with the exception afterwards.
    @Transactional(transactionManager = "metadataTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordRun(ScheduledJobRun run) {
        runRepository.save(run);
    }

    @Transactional("metadataTransactionManager")
    public int pruneRuns(Instant cutoff) {
        return runRepository.deleteByStartedAtBefore(cutoff);
    }
}
