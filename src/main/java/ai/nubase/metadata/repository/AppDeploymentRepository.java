package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.AppDeployment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppDeploymentRepository extends JpaRepository<AppDeployment, UUID> {

    List<AppDeployment> findByProjectRefOrderByCreatedAtDesc(String projectRef, Pageable pageable);

    Optional<AppDeployment> findByProjectRefAndId(String projectRef, UUID id);

    @Query(value = """
            SELECT *
              FROM app_deployments
             WHERE project_ref = :projectRef
               AND run_id = :runId
               AND manifest_summary ->> 'profile' = 'bounded-asset-v1'
             ORDER BY created_at DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<AppDeployment> findBoundedByProjectRefAndRunId(
            @Param("projectRef") String projectRef,
            @Param("runId") String runId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM AppDeployment d WHERE d.projectRef = :projectRef AND d.id = :id")
    Optional<AppDeployment> findByProjectRefAndIdForUpdate(
            @Param("projectRef") String projectRef,
            @Param("id") UUID id
    );
}
