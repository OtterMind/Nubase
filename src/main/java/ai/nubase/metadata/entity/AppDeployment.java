package ai.nubase.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_deployments")
public class AppDeployment {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_ROLLED_BACK = "rolled_back";
    public static final String STATUS_ROLLBACK_FAILED = "rollback_failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_ref", nullable = false, length = 128)
    private String projectRef;

    @Column(name = "app_name", nullable = false, length = 128)
    private String appName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "public_url", columnDefinition = "TEXT")
    private String publicUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_summary", columnDefinition = "jsonb")
    private String manifestSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "agent_id", length = 128)
    private String agentId;

    @Column(name = "run_id", length = 128)
    private String runId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (status == null || status.isBlank()) status = STATUS_RUNNING;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
