package ai.nubase.cron;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "nubase.cron")
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class CronProperties {

    private boolean enabled = true;
    /** Scheduler tick interval; due jobs are claimed and run on each tick. */
    private long tickMs = 30_000;
    /** Upper bound of jobs claimed per tick per instance. */
    private int maxJobsPerTick = 50;
    /** Maximum number of scheduled jobs executing concurrently per instance. */
    private int maxConcurrentJobs = 8;
    /** Queue capacity for claimed jobs waiting for an execution thread. */
    private int executionQueueCapacity = 100;
    /** Applied when a job has no timeout_seconds of its own. */
    private int defaultTimeoutSeconds = 60;
    /** Hard cap for per-job timeout_seconds. */
    private int maxTimeoutSeconds = 600;
    /** Run history retention; 0 disables pruning. */
    private int runHistoryRetentionDays = 30;
    private long runHistoryRetentionScanMs = 3_600_000;
}
