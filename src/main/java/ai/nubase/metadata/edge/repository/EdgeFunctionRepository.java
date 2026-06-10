package ai.nubase.metadata.edge.repository;

import ai.nubase.metadata.edge.entity.EdgeFunction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EdgeFunctionRepository extends JpaRepository<EdgeFunction, UUID> {

    Optional<EdgeFunction> findByProjectRefAndSlug(String projectRef, String slug);

    List<EdgeFunction> findByProjectRefOrderByCreatedAtDesc(String projectRef);

    boolean existsByProjectRefAndSlug(String projectRef, String slug);
}
