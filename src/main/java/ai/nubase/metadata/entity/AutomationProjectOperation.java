package ai.nubase.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "automation_project_operations",
        uniqueConstraints = @UniqueConstraint(name = "uq_automation_project_operation",
                columnNames = {"actor", "action", "idempotency_key"}))
public class AutomationProjectOperation {

    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "project_ref", length = 50)
    private String projectRef;

    @Column(name = "task_id", nullable = false, length = 128)
    private String taskId;

    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @Column(name = "spec_digest", nullable = false, length = 71)
    private String specDigest;

    @Column(name = "approval_id", length = 128)
    private String approvalId;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "grant_id", nullable = false)
    private UUID grantId;

    @Column(name = "token_jti", nullable = false, length = 128)
    private String tokenJti;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
