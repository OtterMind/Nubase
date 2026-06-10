package ai.nubase.metadata.repository;

import java.time.Instant;

/**
 * Custom upsert for platform one-time tokens. Implemented with a direct EntityManager native query so
 * the {@code INSERT ... ON CONFLICT} statement is NOT handed to Spring Data's JSqlParser-based query
 * enhancer (which can't parse {@code ON CONFLICT} and would fail context startup).
 */
public interface PlatformOneTimeTokenRepositoryCustom {

    /** Atomically insert-or-replace the pending code for (email, purpose) in a single statement. */
    void upsert(String email, String purpose, String tokenHash, Instant expiresAt);
}
