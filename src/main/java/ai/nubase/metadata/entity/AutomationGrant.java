package ai.nubase.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "automation_grants")
public class AutomationGrant {

    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String scopes;

    @Column(name = "token_version", nullable = false)
    private Long tokenVersion;

    @Column(name = "allowed_ref_prefix", nullable = false, length = 40)
    private String allowedRefPrefix;

    @Column(name = "max_projects", nullable = false)
    private Integer maxProjects;

    @Column(name = "approval_binding", length = 255)
    private String approvalBinding;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (active == null) active = Boolean.TRUE;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
