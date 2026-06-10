package ai.nubase.metadata.edge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
        name = "edge_functions",
        uniqueConstraints = @UniqueConstraint(name = "uq_edge_functions_project_slug", columnNames = {"project_ref", "slug"})
)
public class EdgeFunction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_ref", nullable = false, length = 128)
    private String projectRef;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slug", nullable = false, length = 128)
    private String slug;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "verify_jwt", nullable = false)
    private Boolean verifyJwt;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "privileged", nullable = false)
    private Boolean privileged;

    @Column(name = "import_map", columnDefinition = "jsonb")
    private String importMap;

    @Column(name = "entrypoint", nullable = false, length = 512)
    private String entrypoint;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_version_id")
    private EdgeFunctionVersion activeVersion;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_by_platform_user_id")
    private UUID createdByPlatformUserId;

    @Column(name = "updated_by_platform_user_id")
    private UUID updatedByPlatformUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (verifyJwt == null) verifyJwt = Boolean.TRUE;
        if (enabled == null) enabled = Boolean.TRUE;
        if (privileged == null) privileged = Boolean.FALSE;
        if (entrypoint == null || entrypoint.isBlank()) entrypoint = "index.ts";
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        if (verifyJwt == null) verifyJwt = Boolean.TRUE;
        if (enabled == null) enabled = Boolean.TRUE;
        if (privileged == null) privileged = Boolean.FALSE;
        if (entrypoint == null || entrypoint.isBlank()) entrypoint = "index.ts";
        updatedAt = Instant.now();
    }
}
