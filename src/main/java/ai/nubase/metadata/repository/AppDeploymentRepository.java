package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.AppDeployment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppDeploymentRepository extends JpaRepository<AppDeployment, UUID> {

    List<AppDeployment> findByProjectRefOrderByCreatedAtDesc(String projectRef, Pageable pageable);

    Optional<AppDeployment> findByProjectRefAndId(String projectRef, UUID id);
}
