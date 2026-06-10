package ai.nubase.metadata.edge.repository;

import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EdgeFunctionVersionRepository extends JpaRepository<EdgeFunctionVersion, UUID> {

    List<EdgeFunctionVersion> findByFunctionOrderByVersionNoDesc(EdgeFunction function);

    Optional<EdgeFunctionVersion> findFirstByFunctionOrderByVersionNoDesc(EdgeFunction function);
}
