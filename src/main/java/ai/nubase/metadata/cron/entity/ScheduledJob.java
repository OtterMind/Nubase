package ai.nubase.metadata.cron.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
// Dirty-checked updates write ONLY modified columns. This is load-bearing for
// concurrency, not an optimization: the admin path loads + mutates + saves this
// entity, and a full-column UPDATE would write back its load-time snapshot of the
// runner-owned columns (locked_until, last_run_at, last_status), erasing a claim
// taken by a runner instance between the SELECT and the flush.
@org.hibernate.annotations.DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "scheduled_jobs",
        uniqueConstraints = @UniqueConstraint(name = "uq_scheduled_jobs_project_name", columnNames = {"project_ref", "name"})
)
public class ScheduledJob {

    public static final String TARGET_EDGE_FUNCTION = "edge_function";
    public static final String TARGET_DB_FUNCTION = "db_function";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_ref", nullable = false, length = 128)
    private String projectRef;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "cron_expression", nullable = false, length = 128)
    private String cronExpression;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "function_slug", length = 128)
    private String functionSlug;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Column(name = "request_path", columnDefinition = "TEXT")
    private String requestPath;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "db_function_name", length = 255)
    private String dbFunctionName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "db_function_args", columnDefinition = "jsonb")
    private String dbFunctionArgs;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_status", length = 32)
    private String lastStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (enabled == null) enabled = Boolean.TRUE;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (enabled == null) enabled = Boolean.TRUE;
        updatedAt = Instant.now();
    }
}
