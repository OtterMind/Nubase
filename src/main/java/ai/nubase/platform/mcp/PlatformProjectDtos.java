package ai.nubase.platform.mcp;

import java.util.Map;

public final class PlatformProjectDtos {

    private PlatformProjectDtos() {
    }

    public record CreateRequest(
            String idempotencyKey,
            String ref,
            String name,
            String description,
            String taskId,
            String runId,
            String specDigest,
            String approvalId) {
    }

    public record ProvisionRequest(
            String idempotencyKey,
            String ref,
            String taskId,
            String runId,
            String specDigest,
            String approvalId) {
    }

    public record StatusRequest(
            String ref,
            String taskId,
            String runId,
            String specDigest,
            String approvalId) {
    }

    public record CreateResult(
            boolean created,
            String ref,
            String name,
            String state,
            Readiness readiness) {
    }

    public record ProvisionResult(
            String ref,
            String state,
            String submission,
            String verificationLevel,
            Readiness readiness) {
    }

    public record StatusResult(
            String ref,
            String name,
            String state,
            boolean enabled,
            boolean running,
            String taskId,
            String runId,
            String specDigest,
            String approvalId,
            String verificationLevel,
            Readiness readiness,
            Map<String, String> advertisedEndpoints) {
    }

    /** Static control-plane provisioning checks; not external service health probes. */
    public record Readiness(
            boolean database,
            boolean credentials,
            boolean auth,
            boolean storage,
            boolean memory,
            boolean assets,
            boolean functions,
            boolean mcp,
            boolean gateway) {
    }
}
