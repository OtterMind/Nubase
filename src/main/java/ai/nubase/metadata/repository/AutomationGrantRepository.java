package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.AutomationGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AutomationGrantRepository extends JpaRepository<AutomationGrant, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AutomationGrant g where g.id = :id")
    Optional<AutomationGrant> findLockedById(@Param("id") UUID id);
}
