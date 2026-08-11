package ai.nubase.platform.mcp;

import java.util.Set;
import java.util.UUID;

public record AutomationPrincipal(
        String actor,
        UUID grantId,
        String tokenJti,
        long tokenVersion,
        Set<String> scopes,
        String allowedRefPrefix,
        int maxProjects,
        String approvalBinding) {

    public AutomationPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
