package ai.nubase.deploy.service;

import java.time.Instant;

public record AppWorkerDeploymentResult(
        String provider,
        String providerDeploymentId,
        String previewUrl,
        String status,
        String assetManifestHash,
        int assetFileCount,
        Instant deployedAt
) {
}
