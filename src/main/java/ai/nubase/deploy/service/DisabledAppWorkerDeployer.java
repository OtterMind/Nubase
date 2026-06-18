package ai.nubase.deploy.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "nubase.functions.enabled", havingValue = "false")
public class DisabledAppWorkerDeployer implements AppWorkerDeployer {

    @Override
    public AppWorkerDeploymentResult deploy(AppWorkerDeploymentRequest request) {
        throw new AppWorkerDeploymentException("App worker deployment requires nubase.functions.enabled=true");
    }
}
