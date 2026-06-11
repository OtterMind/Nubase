package ai.nubase.cron.target;

import ai.nubase.metadata.cron.entity.ScheduledJob;

/**
 * One executable target type for scheduled jobs. Implementations run inside an
 * established tenant context (service-role) and must not manage transactions.
 */
public interface ScheduledJobTarget {

    String type();

    RunOutcome execute(ScheduledJob job) throws Exception;

    record RunOutcome(boolean success, String result, String errorMessage) {

        public static RunOutcome success(String result) {
            return new RunOutcome(true, result, null);
        }

        public static RunOutcome failure(String result, String errorMessage) {
            return new RunOutcome(false, result, errorMessage);
        }
    }
}
