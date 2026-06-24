package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.PlatformExternalIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformExternalIdentityRepository extends JpaRepository<PlatformExternalIdentity, Long> {

    Optional<PlatformExternalIdentity> findByExternalPlatformAndExternalUserId(
            String externalPlatform, String externalUserId);
}
