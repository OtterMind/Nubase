package ai.nubase.deploy.controller;

import ai.nubase.auth.annotation.RequireServiceRole;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AppDeploymentAdminControllerTest {

    @Test
    void appWorkerDeployIsPlatformAdminAuthenticatedButNotTenantServiceRoleGuarded() throws Exception {
        assertThat(AppDeploymentAdminController.class.isAnnotationPresent(RequireServiceRole.class)).isFalse();
        assertThat(AppDeploymentAdminController.class
                .getMethod("deployAppWorker", String.class, java.util.List.class, java.util.List.class)
                .isAnnotationPresent(RequireServiceRole.class)).isFalse();
    }

    @Test
    void otherAdminEndpointsStillRequireTenantServiceRole() {
        Set<String> serviceRoleMethods = Arrays.stream(AppDeploymentAdminController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RequireServiceRole.class))
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertThat(serviceRoleMethods).contains(
                "create",
                "list",
                "get",
                "logs",
                "recordStep",
                "complete",
                "rollback",
                "listAppWorkers",
                "getAppWorker",
                "deleteAppWorker"
        );
    }
}
