package ai.nubase.metadata.cron.repository;

import ai.nubase.metadata.cron.entity.ScheduledJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, UUID> {

    Optional<ScheduledJob> findByProjectRefAndName(String projectRef, String name);

    List<ScheduledJob> findByProjectRefOrderByNameAsc(String projectRef);

    boolean existsByProjectRefAndName(String projectRef, String name);

    List<ScheduledJob> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant now, Pageable pageable);

    /**
     * Compare-and-set claim: advances next_run_at and takes the lock in one UPDATE,
     * so concurrent runner instances cannot fire the same occurrence twice and a job
     * still locked by a slow previous run is not re-entered. Returns 0 when another
     * instance already claimed the occurrence or the job is still running.
     */
    @Modifying
    @Query("update ScheduledJob j set j.nextRunAt = :nextRunAt, j.lockedUntil = :lockedUntil, j.lastRunAt = :now " +
            "where j.id = :id and j.nextRunAt = :expectedNextRunAt " +
            "and (j.lockedUntil is null or j.lockedUntil < :now)")
    int claim(@Param("id") UUID id,
              @Param("expectedNextRunAt") Instant expectedNextRunAt,
              @Param("nextRunAt") Instant nextRunAt,
              @Param("lockedUntil") Instant lockedUntil,
              @Param("now") Instant now);

    /**
     * Releases the claim lock and records the outcome. next_run_at is recomputed
     * from completion time, so occurrences missed while a slow run held the lock
     * coalesce instead of firing back-to-back. The expectedNextRunAt guard prevents
     * an old run from overwriting an admin schedule change made while it was running.
     */
    @Modifying
    @Query("update ScheduledJob j set j.lockedUntil = null, j.lastStatus = :status, j.nextRunAt = :nextRunAt " +
            "where j.id = :id and j.nextRunAt = :expectedNextRunAt")
    int complete(@Param("id") UUID id,
                 @Param("expectedNextRunAt") Instant expectedNextRunAt,
                 @Param("status") String status,
                 @Param("nextRunAt") Instant nextRunAt);

    /**
     * Fallback when complete()'s schedule guard does not match (an admin re-anchored
     * the schedule while the run was in flight): release the lock and record the
     * outcome WITHOUT touching next_run_at, preserving the admin's new schedule. The
     * lockToken guard (the exact locked_until written at claim) ensures an expired
     * claim cannot release a lock that a newer claim now holds.
     */
    @Modifying
    @Query("update ScheduledJob j set j.lockedUntil = null, j.lastStatus = :status " +
            "where j.id = :id and j.lockedUntil = :lockToken")
    int releaseLock(@Param("id") UUID id,
                    @Param("lockToken") Instant lockToken,
                    @Param("status") String status);

    /**
     * Admin edits intentionally update only user-controlled fields. A managed entity
     * save would flush a stale locked_until/next_run_at snapshot and could erase a
     * runner claim taken after the entity was read.
     */
    @Modifying
    @Query("""
            update ScheduledJob j set
                j.description = :description,
                j.cronExpression = :cronExpression,
                j.targetType = :targetType,
                j.functionSlug = :functionSlug,
                j.httpMethod = :httpMethod,
                j.requestPath = :requestPath,
                j.requestBody = :requestBody,
                j.dbFunctionName = :dbFunctionName,
                j.dbFunctionArgs = :dbFunctionArgs,
                j.timeoutSeconds = :timeoutSeconds,
                j.enabled = :enabled,
                j.nextRunAt = :nextRunAt,
                j.updatedAt = :updatedAt
            where j.id = :id
            """)
    int updateAdminFields(@Param("id") UUID id,
                          @Param("description") String description,
                          @Param("cronExpression") String cronExpression,
                          @Param("targetType") String targetType,
                          @Param("functionSlug") String functionSlug,
                          @Param("httpMethod") String httpMethod,
                          @Param("requestPath") String requestPath,
                          @Param("requestBody") String requestBody,
                          @Param("dbFunctionName") String dbFunctionName,
                          @Param("dbFunctionArgs") String dbFunctionArgs,
                          @Param("timeoutSeconds") Integer timeoutSeconds,
                          @Param("enabled") Boolean enabled,
                          @Param("nextRunAt") Instant nextRunAt,
                          @Param("updatedAt") Instant updatedAt);
}
