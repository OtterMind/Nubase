package ai.nubase.metadata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps a third-party identity {@code (external_platform, external_user_id)} to a dedicated Nubase
 * {@link PlatformUser}. Used when a project is created via the root key on behalf of an external
 * user: we resolve (or lazily create) a non-super-admin shadow platform user and make it the
 * project owner, so different external users' projects stay segregated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "platform_external_identities",
        uniqueConstraints = @UniqueConstraint(name = "uq_platform_external_identity",
                columnNames = {"external_platform", "external_user_id"}))
public class PlatformExternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_platform", nullable = false, length = 64)
    private String externalPlatform;

    @Column(name = "external_user_id", nullable = false, length = 255)
    private String externalUserId;

    /** The Nubase platform user this external identity maps to. */
    @Column(name = "platform_user_id", nullable = false)
    private UUID platformUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
