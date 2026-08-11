package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.AutomationProjectOperation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AutomationProjectOperationRepository
        extends JpaRepository<AutomationProjectOperation, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO automation_project_operations (
                id, actor, action, idempotency_key, request_hash, status,
                project_ref, task_id, run_id, spec_digest, approval_id,
                grant_id, token_jti, created_at, updated_at
            ) VALUES (
                :id, :actor, :action, :idempotencyKey, :requestHash, 'PENDING',
                :projectRef, :taskId, :runId, :specDigest, :approvalId,
                :grantId, :tokenJti, :now, :now
            )
            ON CONFLICT ON CONSTRAINT uq_automation_project_operation DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("id") UUID id,
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("projectRef") String projectRef,
            @Param("taskId") String taskId,
            @Param("runId") String runId,
            @Param("specDigest") String specDigest,
            @Param("approvalId") String approvalId,
            @Param("grantId") UUID grantId,
            @Param("tokenJti") String tokenJti,
            @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AutomationProjectOperation> findByActorAndActionAndIdempotencyKey(
            String actor, String action, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AutomationProjectOperation>
            findFirstByActorAndGrantIdAndActionAndProjectRefAndStatusOrderByCreatedAtDesc(
                    String actor,
                    UUID grantId,
                    String action,
                    String projectRef,
                    String status);

    @Query("""
            SELECT COUNT(o)
            FROM AutomationProjectOperation o
            WHERE o.actor = :actor
              AND o.grantId = :grantId
              AND o.action = :action
              AND o.projectRef = :projectRef
              AND o.taskId = :taskId
              AND o.runId = :runId
              AND o.specDigest = :specDigest
              AND ((:approvalId IS NULL AND o.approvalId IS NULL)
                   OR o.approvalId = :approvalId)
              AND o.status IN ('PENDING', 'SUCCEEDED', 'FAILED')
            """)
    long countMatchingTrace(
            @Param("actor") String actor,
            @Param("grantId") UUID grantId,
            @Param("action") String action,
            @Param("projectRef") String projectRef,
            @Param("taskId") String taskId,
            @Param("runId") String runId,
            @Param("specDigest") String specDigest,
            @Param("approvalId") String approvalId);

    @Query(value = """
            SELECT *
            FROM automation_project_operations
            WHERE action = 'platformProjectProvision'
              AND status = 'PENDING'
              AND updated_at <= :dueBefore
            ORDER BY created_at
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AutomationProjectOperation> findPendingProvisionForRecovery(
            @Param("dueBefore") Instant dueBefore);
}
