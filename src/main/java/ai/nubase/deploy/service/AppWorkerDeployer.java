package ai.nubase.deploy.service;

public interface AppWorkerDeployer {
    AppWorkerDeploymentResult deploy(AppWorkerDeploymentRequest request);
}
