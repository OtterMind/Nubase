package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.PlatformOneTimeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformOneTimeTokenRepository
        extends JpaRepository<PlatformOneTimeToken, UUID>, PlatformOneTimeTokenRepositoryCustom {

    Optional<PlatformOneTimeToken> findByEmailIgnoreCaseAndPurpose(String email, String purpose);

    void deleteByEmailIgnoreCaseAndPurpose(String email, String purpose);
}
