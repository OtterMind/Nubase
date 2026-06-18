package ai.nubase.deploy.service;

public class AppWorkerDeploymentException extends RuntimeException {
    public AppWorkerDeploymentException(String message) {
        super(message);
    }

    public AppWorkerDeploymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
