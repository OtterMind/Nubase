package ai.nubase.metadata.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * EntityManager-based implementation of {@link PlatformOneTimeTokenRepositoryCustom}. Using the native
 * query directly (rather than a {@code @Query} on the repository interface) keeps the {@code ON CONFLICT}
 * SQL away from Spring Data's startup query parser.
 */
public class PlatformOneTimeTokenRepositoryImpl implements PlatformOneTimeTokenRepositoryCustom {

    @PersistenceContext(unitName = "metadata")
    private EntityManager entityManager;

    @Override
    public void upsert(String email, String purpose, String tokenHash, Instant expiresAt) {
        entityManager.createNativeQuery(
                        "INSERT INTO platform_one_time_tokens (id, email, purpose, token_hash, expires_at, created_at) "
                                + "VALUES (gen_random_uuid(), :email, :purpose, :hash, :expiresAt, NOW()) "
                                + "ON CONFLICT (email, purpose) "
                                + "DO UPDATE SET token_hash = EXCLUDED.token_hash, expires_at = EXCLUDED.expires_at")
                .setParameter("email", email)
                .setParameter("purpose", purpose)
                .setParameter("hash", tokenHash)
                .setParameter("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .executeUpdate();
    }
}
