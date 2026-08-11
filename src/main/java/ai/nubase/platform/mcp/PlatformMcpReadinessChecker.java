package ai.nubase.platform.mcp;

import ai.nubase.common.enums.DatabaseInitStatus;
import ai.nubase.platform.mcp.PlatformMcpProjectRepository.OwnedProjectState;
import ai.nubase.platform.mcp.PlatformProjectDtos.Readiness;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nubase.platform-mcp", name = "enabled", havingValue = "true")
public class PlatformMcpReadinessChecker {

    private final Environment environment;
    private final PlatformMcpTenantReadinessProbe tenantReadinessProbe;

    public Readiness check(OwnedProjectState project, boolean activeGatewayCatalog) {
        boolean initialized = DatabaseInitStatus.INITIALIZED.name()
                .equalsIgnoreCase(project.initStatus());
        boolean credentials = project.credentialsPresent();
        boolean core = initialized && project.enabled() && credentials;
        boolean memoryEnabled = flag("nubase.mem.enabled", true);
        boolean assetsEnabled = flag("nubase.assets.enabled", true);
        boolean functionsEnabled = flag("nubase.functions.enabled", true);
        boolean tenantMcpEnabled = flag("spring.ai.mcp.server.enabled", true);
        boolean aiGatewayEnabled = flag("nubase.ai-gateway.enabled", true);
        PlatformMcpTenantReadinessProbe.TenantReadiness tenant = core
                ? tenantReadinessProbe.check(
                        project.dbKey(), memoryEnabled, assetsEnabled, aiGatewayEnabled)
                : PlatformMcpTenantReadinessProbe.TenantReadiness.unavailable();
        return new Readiness(
                tenant.database(),
                credentials,
                tenant.auth(),
                tenant.storage(),
                memoryEnabled && tenant.memory(),
                assetsEnabled && tenant.assets(),
                tenant.database() && functionsEnabled,
                tenant.database() && tenantMcpEnabled,
                aiGatewayEnabled && tenant.gateway() && activeGatewayCatalog);
    }

    /**
     * Returns whether all static control-plane provisioning checks pass.
     *
     * <p>This does not prove external endpoint reachability or a billable upstream model call.
     * Callers must report the result as provisioned, not runtime-ready.</p>
     */
    public boolean allRequiredStaticChecksPass(Readiness readiness) {
        return readiness.database()
                && readiness.credentials()
                && readiness.auth()
                && readiness.storage()
                && readiness.memory()
                && readiness.assets()
                && readiness.functions()
                && readiness.mcp()
                && readiness.gateway();
    }

    private boolean flag(String name, boolean defaultValue) {
        return environment.getProperty(name, Boolean.class, defaultValue);
    }
}
