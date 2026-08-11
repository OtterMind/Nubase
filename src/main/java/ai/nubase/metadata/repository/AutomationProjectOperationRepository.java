package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.AutomationProjectOperation;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
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
                grant_id, token_jti, token_version, attempt_count,
                next_attempt_at, created_at, updated_at
            ) VALUES (
                :id, :actor, :action, :idempotencyKey, :requestHash, 'PENDING',
                :projectRef, :taskId, :runId, :specDigest, :approvalId,
                :grantId, :tokenJti,
                (SELECT token_version FROM automation_grants WHERE id = :grantId),
                0, :now, :now, :now
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
    Optional<AutomationProjectOperation> findByActorAndGrantIdAndActionAndIdempotencyKey(
            String actor, UUID grantId, String action, String idempotencyKey);

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

    @Query("""
            SELECT COUNT(o)
            FROM AutomationProjectOperation o
            WHERE o.actor = :actor
              AND o.grantId = :grantId
              AND o.tokenVersion = :tokenVersion
              AND o.action = 'platformProjectCreate'
              AND o.projectRef = :projectRef
              AND o.taskId = :taskId
              AND o.runId = :runId
              AND o.specDigest = :specDigest
              AND ((:approvalId IS NULL AND o.approvalId IS NULL)
                   OR o.approvalId = :approvalId)
              AND o.status = 'SUCCEEDED'
              AND o.createdAt <= :createdAt
            """)
    long countSuccessfulCreateLineage(
            @Param("actor") String actor,
            @Param("grantId") UUID grantId,
            @Param("tokenVersion") Long tokenVersion,
            @Param("projectRef") String projectRef,
            @Param("taskId") String taskId,
            @Param("runId") String runId,
            @Param("specDigest") String specDigest,
            @Param("approvalId") String approvalId,
            @Param("createdAt") Instant createdAt);

    @QueryHints({
            @QueryHint(name = "jakarta.persistence.query.timeout", value = "1500"),
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "0")
    })
    @Query(value = """
            SELECT id AS "id", grant_id AS "grantId"
            FROM automation_project_operations
            WHERE action = 'platformProjectProvision'
              AND status = 'PENDING'
              AND next_attempt_at <= :dueAt
              AND (claim_token IS NULL OR claimed_until <= :dueAt)
            ORDER BY next_attempt_at, created_at, id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ProvisionRecoveryCandidate> findClaimableProvisionCandidates(
            @Param("dueAt") Instant dueAt);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE automation_project_operations
            SET claim_token = :claimToken,
                claimed_until = :claimedUntil,
                updated_at = :claimedAt
            WHERE id IN (:operationIds)
              AND action = 'platformProjectProvision'
              AND status = 'PENDING'
              AND (claim_token IS NULL OR claimed_until <= :eligibleAt)
            """, nativeQuery = true)
    int claimProvisionCandidates(
            @Param("operationIds") List<UUID> operationIds,
            @Param("claimToken") UUID claimToken,
            @Param("eligibleAt") Instant eligibleAt,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimedUntil") Instant claimedUntil);

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "1500"))
    @Query(value = """
            SELECT *
            FROM automation_project_operations
            WHERE id = :id
            FOR UPDATE NOWAIT
            """, nativeQuery = true)
    Optional<AutomationProjectOperation> findRecoveryLockedById(@Param("id") UUID id);

    interface ProvisionRecoveryCandidate {

        UUID getId();

        UUID getGrantId();
    }
}
