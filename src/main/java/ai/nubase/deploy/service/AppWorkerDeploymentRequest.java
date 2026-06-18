package ai.nubase.deploy.service;

import java.util.List;
import java.util.Map;

public record AppWorkerDeploymentRequest(
        String appCode,
        String version,
        String workerName,
        String mainModule,
        String serverEntrypointPath,
        String previewHost,
        String compatibilityDate,
        List<String> compatibilityFlags,
        Map<String, String> plainTextBindings,
        Map<String, String> secretTextBindings,
        List<AppWorkerFile> serverFiles,
        List<AppWorkerFile> assetFiles
) {
    public record AppWorkerFile(
            String path,
            byte[] content,
            String contentType
    ) {
    }
}
