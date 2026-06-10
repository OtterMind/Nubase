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
@Table(
        name = "edge_function_versions",
        uniqueConstraints = @UniqueConstraint(name = "uq_edge_function_versions_no", columnNames = {"function_id", "version_no"})
)
public class EdgeFunctionVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "function_id", nullable = false)
    private EdgeFunction function;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "source_hash", nullable = false, length = 128)
    private String sourceHash;

    @Column(name = "artifact_uri", columnDefinition = "TEXT")
    private String artifactUri;

    @Column(name = "artifact_type", nullable = false, length = 64)
    private String artifactType;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "provider_deployment_id", columnDefinition = "TEXT")
    private String providerDeploymentId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "deployed_by_platform_user_id")
    private UUID deployedByPlatformUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @PrePersist
    void onCreate() {
        if (artifactType == null || artifactType.isBlank()) artifactType = "source_bundle";
        if (provider == null || provider.isBlank()) provider = "local";
        if (status == null || status.isBlank()) status = "pending";
        if (createdAt == null) createdAt = Instant.now();
    }
}
