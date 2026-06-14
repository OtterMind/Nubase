package ai.nubase.metadata.repository;

import ai.nubase.metadata.entity.AppDeploymentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppDeploymentStepRepository extends JpaRepository<AppDeploymentStep, UUID> {

    List<AppDeploymentStep> findByDeploymentIdOrderByStepOrderAsc(UUID deploymentId);

    Optional<AppDeploymentStep> findFirstByDeploymentIdOrderByStepOrderDesc(UUID deploymentId);
}
