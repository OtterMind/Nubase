package ai.nubase.deploy.service;

import java.util.Map;

/**
 * Live state of a deployed app worker as reported by the deploy provider
 * (e.g. a Cloudflare Workers for Platforms dispatch-namespace script).
 */
public record AppWorkerInfo(
        String workerName,
        boolean exists,
        Map<String, Object> details
) {
}
