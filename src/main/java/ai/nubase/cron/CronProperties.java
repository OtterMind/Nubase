package ai.nubase.cron;

import jakarta.annotation.PostConstruct;
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
    // NOTE: the tick and retention-scan intervals are read directly by the
    // @Scheduled placeholders in ScheduledJobRunner (${nubase.cron.tick-ms:30000},
    // ${nubase.cron.run-history-retention-scan-ms:3600000}); they are deliberately
    // not duplicated as fields here.
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

    // A tick that claims more jobs than the executor can hold gets the overflow
    // rejected and recorded as failed runs — a misconfiguration trap better caught
    // at startup than discovered as EXECUTOR_REJECTED noise in run history.
    @PostConstruct
    void validate() {
        if (maxJobsPerTick > maxConcurrentJobs + executionQueueCapacity) {
            throw new IllegalStateException(
                    "nubase.cron.max-jobs-per-tick (" + maxJobsPerTick + ") must not exceed "
                            + "max-concurrent-jobs (" + maxConcurrentJobs + ") + execution-queue-capacity ("
                            + executionQueueCapacity + "): overflow claims would be rejected every tick");
        }
        if (defaultTimeoutSeconds < 1 || maxTimeoutSeconds < defaultTimeoutSeconds) {
            throw new IllegalStateException(
                    "nubase.cron timeouts misconfigured: default-timeout-seconds=" + defaultTimeoutSeconds
                            + ", max-timeout-seconds=" + maxTimeoutSeconds);
        }
    }
}
