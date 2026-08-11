package ai.nubase.assets.service;

import org.springframework.http.HttpStatus;

/**
 * Assets module exceptions, rendered by AssetsExceptionHandler so the JSON error
 * contract has a single owner (same shape as the cron/functions modules).
 */
public final class AssetsExceptions {

    private AssetsExceptions() {
    }

    public static class AssetsException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public AssetsException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    public static AssetsException notFound(String path) {
        return new AssetsException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "Asset not found: " + path);
    }

    public static AssetsException conflict(String path) {
        return new AssetsException(HttpStatus.CONFLICT, "ASSET_EXISTS",
                "Asset already exists (pass upsert=true to overwrite): " + path);
    }

    public static AssetsException badRequest(String message) {
        return new AssetsException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static AssetsException tooLarge(long limit) {
        return new AssetsException(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE",
                "Asset exceeds the maximum allowed size of " + limit + " bytes");
    }

    public static AssetsException versioningRequired() {
        return new AssetsException(HttpStatus.SERVICE_UNAVAILABLE, "ASSET_VERSIONING_REQUIRED",
                "Bounded marker storage requires bucket versioning");
    }

    public static AssetsException versioningCheckFailed() {
        return new AssetsException(HttpStatus.SERVICE_UNAVAILABLE, "ASSET_VERSIONING_CHECK_FAILED",
                "Bounded marker storage versioning could not be verified");
    }

    public static AssetsException boundedPrivateStorageRequired() {
        return new AssetsException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ASSET_BOUNDED_PRIVATE_STORAGE_REQUIRED",
                "Bounded marker storage requires an explicitly private backend");
    }

    public static AssetsException forbidden() {
        return new AssetsException(HttpStatus.FORBIDDEN, "SERVICE_ROLE_REQUIRED",
                "This endpoint requires the project's service_role key");
    }
}
