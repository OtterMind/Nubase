package ai.nubase.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
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
@Table(name = "app_deployment_steps")
public class AppDeploymentStep {

    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id", nullable = false)
    private AppDeployment deployment;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "step_name", nullable = false, length = 128)
    private String stepName;

    @Column(name = "target_name")
    private String targetName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (startedAt == null) startedAt = now;
        if (completedAt == null) completedAt = now;
    }
}
