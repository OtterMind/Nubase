package ai.nubase.functions.service;

import ai.nubase.functions.executor.EdgeFunctionExecutorProperties;
import ai.nubase.metadata.edge.repository.EdgeFunctionInvocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeFunctionInvocationRetentionService {

    private final EdgeFunctionInvocationRepository invocationRepository;
    private final EdgeFunctionExecutorProperties properties;

    @Scheduled(
            initialDelayString = "${nubase.functions.executor.invocation-log-retention-scan-ms:3600000}",
            fixedDelayString = "${nubase.functions.executor.invocation-log-retention-scan-ms:3600000}"
    )
    @Transactional("metadataTransactionManager")
    public void pruneExpiredInvocations() {
        int retentionDays = properties.getInvocationLogRetentionDays();
        if (retentionDays <= 0) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        long deleted = invocationRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} edge function invocation logs older than {}", deleted, cutoff);
        }
    }
}
