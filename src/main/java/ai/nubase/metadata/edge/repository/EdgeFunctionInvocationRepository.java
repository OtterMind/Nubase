package ai.nubase.metadata.edge.repository;

import ai.nubase.metadata.edge.entity.EdgeFunctionInvocation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EdgeFunctionInvocationRepository extends JpaRepository<EdgeFunctionInvocation, UUID> {

    List<EdgeFunctionInvocation> findByProjectRefOrderByCreatedAtDesc(String projectRef, Pageable pageable);

    List<EdgeFunctionInvocation> findByProjectRefAndFunctionSlugOrderByCreatedAtDesc(
            String projectRef,
            String functionSlug,
            Pageable pageable
    );

    long deleteByCreatedAtBefore(Instant cutoff);
}
