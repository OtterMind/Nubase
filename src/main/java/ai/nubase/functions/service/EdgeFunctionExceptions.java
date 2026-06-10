package ai.nubase.functions.service;

import org.springframework.http.HttpStatus;

public final class EdgeFunctionExceptions {

    private EdgeFunctionExceptions() {
    }

    public static class EdgeFunctionException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public EdgeFunctionException(HttpStatus status, String code, String message) {
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
}
