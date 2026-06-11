package ai.nubase.cron.service;

import ai.nubase.cron.CronProperties;
import ai.nubase.cron.target.ScheduledJobTarget;
import ai.nubase.cron.target.ScheduledJobTarget.RunOutcome;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.metadata.cron.entity.ScheduledJobRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The scheduler loop. Each tick scans due jobs and claims them one by one with
 * a compare-and-set on next_run_at (see ScheduledJobRepository.claim), so any
 * number of Nubase instances can run concurrently without an external lock
 * service and without double-firing. A job that is still running when its next
 * occurrence comes due stays locked; missed occurrences coalesce into a single
 * run when the lock clears.
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobRunner {

    private static final Duration MIN_LOCK = Duration.ofMinutes(10);

    private final ScheduledJobStore store;
    private final CronProperties properties;
    private final CronTenantContext tenantContext;
    private final Executor jobExecutor;
    private final Map<String, ScheduledJobTarget> targets;

    public ScheduledJobRunner(ScheduledJobStore store,
                              CronProperties properties,
                              CronTenantContext tenantContext,
                              @Qualifier("cronJobExecutor") Executor jobExecutor,
                              List<ScheduledJobTarget> targets) {
        this.store = store;
        this.properties = properties;
        this.tenantContext = tenantContext;
        this.jobExecutor = jobExecutor;
        this.targets = targets.stream().collect(Collectors.toMap(ScheduledJobTarget::type, Function.identity()));
    }

    @Scheduled(
            initialDelayString = "${nubase.cron.tick-ms:30000}",
            fixedDelayString = "${nubase.cron.tick-ms:30000}"
    )
    public void tick() {
        Instant now = Instant.now();
        List<ScheduledJob> due = store.findDue(now, properties.getMaxJobsPerTick());
        for (ScheduledJob job : due) {
            Instant scheduledFor = job.getNextRunAt();
            Instant next;
            try {
                next = CronExpressions.next(job.getCronExpression(), now);
            } catch (IllegalArgumentException e) {
                log.warn("Scheduled job has an invalid cron expression, skipping: project={}, job={}, error={}",
                        job.getProjectRef(), job.getName(), e.getMessage());
                continue;
            }
            Instant lockedUntil = now.plus(lockDuration(job));
            if (!store.claim(job.getId(), scheduledFor, next, lockedUntil, now)) {
                // Another instance claimed this occurrence, or the previous run still
                // holds the lock — either way this instance has nothing to do.
                continue;
            }
            // The claim advanced next_run_at to `next` and wrote `lockedUntil`; both
            // must travel with the run — complete()'s guards compare against the
            // CLAIMED values, not the pre-claim entity snapshot.
            submitClaimedJob(new Claim(job, scheduledFor, next, lockedUntil));
        }
    }

    /** A successfully claimed occurrence: the row state as the claim left it. */
    private record Claim(ScheduledJob job, Instant scheduledFor, Instant claimedNextRunAt, Instant lockToken) {
    }

    private void submitClaimedJob(Claim claim) {
        ScheduledJob job = claim.job();
        try {
            jobExecutor.execute(() -> {
                try {
                    runClaimedJob(claim);
                } catch (Exception e) {
                    log.warn("Scheduled job runner failed after claim: project={}, job={}, error={}",
                            job.getProjectRef(), job.getName(), e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Scheduled job executor rejected claimed job: project={}, job={}, error={}",
                    job.getProjectRef(), job.getName(), e.toString());
            recordRejectedJob(claim, e);
        }
    }

    private void recordRejectedJob(Claim claim, Exception e) {
        ScheduledJob job = claim.job();
        try {
            store.recordRun(ScheduledJobRun.builder()
                    .jobId(job.getId())
                    .projectRef(job.getProjectRef())
                    .jobName(job.getName())
                    .targetType(job.getTargetType())
                    .scheduledFor(claim.scheduledFor())
                    .startedAt(Instant.now())
                    .finishedAt(Instant.now())
                    .status(ScheduledJobRun.STATUS_FAILED)
                    .errorMessage(truncate("EXECUTOR_REJECTED: " + e, 1000))
                    .build());
            completeClaim(claim, ScheduledJobRun.STATUS_FAILED);
        } catch (Exception completeError) {
            log.warn("Failed to record rejected scheduled job: job={}, error={}", job.getName(), completeError.toString());
        }
    }

    @Scheduled(
            initialDelayString = "${nubase.cron.run-history-retention-scan-ms:3600000}",
            fixedDelayString = "${nubase.cron.run-history-retention-scan-ms:3600000}"
    )
    public void pruneRunHistory() {
        int retentionDays = properties.getRunHistoryRetentionDays();
        if (retentionDays <= 0) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = store.pruneRuns(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} scheduled job runs older than {}", deleted, cutoff);
        }
    }

    private void runClaimedJob(Claim claim) {
        ScheduledJob job = claim.job();
        Instant started = Instant.now();
        RunOutcome outcome;
        try {
            ScheduledJobTarget target = targets.get(job.getTargetType());
            if (target == null) {
                outcome = RunOutcome.failure(null, "UNKNOWN_TARGET_TYPE: " + job.getTargetType());
            } else {
                outcome = tenantContext.runAs(job.getProjectRef(), () -> target.execute(job));
            }
        } catch (Exception e) {
            log.warn("Scheduled job failed: project={}, job={}, error={}",
                    job.getProjectRef(), job.getName(), e.toString());
            outcome = RunOutcome.failure(null, truncate(e.toString(), 1000));
        }

        String status = outcome.success() ? ScheduledJobRun.STATUS_SUCCESS : ScheduledJobRun.STATUS_FAILED;
        try {
            store.recordRun(ScheduledJobRun.builder()
                    .jobId(job.getId())
                    .projectRef(job.getProjectRef())
                    .jobName(job.getName())
                    .targetType(job.getTargetType())
                    .scheduledFor(claim.scheduledFor())
                    .startedAt(started)
                    .finishedAt(Instant.now())
                    .status(status)
                    .result(outcome.result())
                    .errorMessage(truncate(outcome.errorMessage(), 1000))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to record scheduled job run: job={}, error={}", job.getName(), e.toString());
        }
        try {
            completeClaim(claim, status);
        } catch (Exception e) {
            log.warn("Failed to complete scheduled job claim: job={}, status={}, error={}",
                    job.getName(), status, e.toString());
        }
    }

    private void completeClaim(Claim claim, String status) {
        ScheduledJob job = claim.job();
        // Recompute from completion time: occurrences missed while the job was
        // running coalesce instead of firing back-to-back. The guard compares
        // against the value the claim wrote; a mismatch means an admin re-anchored
        // the schedule mid-run, in which case we only release the lock and keep
        // the admin's new next_run_at.
        boolean completed = store.complete(job.getId(), claim.claimedNextRunAt(), status,
                CronExpressions.next(job.getCronExpression(), Instant.now()));
        if (!completed) {
            boolean released = store.releaseLock(job.getId(), claim.lockToken(), status);
            log.info("Schedule changed while job ran; preserved the new schedule: job={}, lockReleased={}",
                    job.getName(), released);
        }
    }

    private Duration lockDuration(ScheduledJob job) {
        int timeoutSeconds = job.getTimeoutSeconds() == null
                ? properties.getDefaultTimeoutSeconds()
                : job.getTimeoutSeconds();
        Duration doubled = Duration.ofSeconds(Math.max(1, timeoutSeconds) * 2L);
        return doubled.compareTo(MIN_LOCK) < 0 ? MIN_LOCK : doubled;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
