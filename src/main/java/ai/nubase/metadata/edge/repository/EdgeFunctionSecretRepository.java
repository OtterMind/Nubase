package ai.nubase.metadata.edge.repository;

import ai.nubase.metadata.edge.entity.EdgeFunction;
import ai.nubase.metadata.edge.entity.EdgeFunctionSecret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EdgeFunctionSecretRepository extends JpaRepository<EdgeFunctionSecret, UUID> {

    List<EdgeFunctionSecret> findByFunctionOrderByNameAsc(EdgeFunction function);

    Optional<EdgeFunctionSecret> findByFunctionAndName(EdgeFunction function, String name);
}
