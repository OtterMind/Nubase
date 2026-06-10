package ai.nubase.metadata.edge.entity;

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

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "edge_function_invocations")
public class EdgeFunctionInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "project_ref", nullable = false, length = 128)
    private String projectRef;

    @Column(name = "function_slug", nullable = false, length = 128)
    private String functionSlug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "function_version_id")
    private EdgeFunctionVersion functionVersion;

    @Column(name = "method", nullable = false, length = 16)
    private String method;

    @Column(name = "path", nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "executor_provider", length = 64)
    private String executorProvider;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "caller_type", length = 32)
    private String callerType;

    @Column(name = "caller_role", length = 64)
    private String callerRole;

    @Column(name = "caller_user_id")
    private UUID callerUserId;

    @Column(name = "caller_platform_user_id")
    private UUID callerPlatformUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
