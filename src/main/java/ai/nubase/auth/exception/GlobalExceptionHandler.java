package ai.nubase.auth.exception;

import ai.nubase.auth.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Global exception handler for all REST controllers
 * Returns Supabase-compatible error responses
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        ErrorResponse error = ErrorResponse.of(
                "validation_failed",
                "Validation failed: " + errors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle invalid credentials
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("Invalid credentials: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                ex.getErrorCode(),
                "Invalid login credentials"
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle user not found
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle email already exists
     */
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        log.warn("Email already exists: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                ex.getErrorCode(),
                "User already registered"
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * Handle weak password
     */
    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<ErrorResponse> handleWeakPassword(WeakPasswordException ex) {
        log.warn("Weak password: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle invalid token
     */
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        log.warn("Invalid token: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * Handle forbidden access (403)
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        log.warn("Forbidden access: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                ex.getErrorCode(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                "server_error",
                "An unexpected error occurred: " + ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Handle rate limiting / lockout (429)
     */
    @ExceptionHandler(ai.nubase.auth.service.RateLimiterService.RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
            ai.nubase.auth.service.RateLimiterService.RateLimitExceededException ex) {
        log.warn("Rate limit hit: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                "over_request_rate_limit",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("Upload too large: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                "payload_too_large",
                "App worker upload exceeds maximum size. Check NUBASE_MULTIPART_MAX_FILE_SIZE, NUBASE_MULTIPART_MAX_REQUEST_SIZE, NUBASE_APP_WORKER_MAX_FILE_SIZE, and NUBASE_APP_WORKER_MAX_REQUEST_SIZE."
        );

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartException(MultipartException ex) {
        if (isUploadSizeException(ex)) {
            log.warn("Multipart upload too large: {}", ex.getMessage());
            ErrorResponse error = ErrorResponse.of(
                    "payload_too_large",
                    "App worker upload exceeds maximum size. Check NUBASE_MULTIPART_MAX_FILE_SIZE, NUBASE_MULTIPART_MAX_REQUEST_SIZE, NUBASE_APP_WORKER_MAX_FILE_SIZE, and NUBASE_APP_WORKER_MAX_REQUEST_SIZE."
            );
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error);
        }
        log.warn("Invalid multipart request: {}", ex.getMessage());
        ErrorResponse error = ErrorResponse.of(
                "invalid_request",
                ex.getMessage() != null ? ex.getMessage() : "Invalid multipart request"
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus responseStatus = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        String errorCode = responseStatus == HttpStatus.PAYLOAD_TOO_LARGE
                ? "payload_too_large"
                : responseStatus.getReasonPhrase().toLowerCase().replace(' ', '_');

        log.warn("Request failed with status {}: {}", responseStatus.value(), reason);

        ErrorResponse error = ErrorResponse.of(
                errorCode,
                reason != null ? reason : responseStatus.getReasonPhrase()
        );
        return ResponseEntity.status(responseStatus).body(error);
    }

    /**
     * Handle illegal argument
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid request: {}", ex.getMessage());

        ErrorResponse error = ErrorResponse.of(
                "invalid_request",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle generic runtime exceptions
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime error: ", ex);

        ErrorResponse error = ErrorResponse.of(
                "server_error",
                ex.getMessage() != null ? ex.getMessage() : "An error occurred"
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error: ", ex);

        ErrorResponse error = ErrorResponse.of(
                "server_error",
                "An unexpected error occurred"
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private boolean isUploadSizeException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (current instanceof MaxUploadSizeExceededException
                    || name.contains("SizeLimitExceeded")
                    || name.contains("FileSizeLimitExceeded")
                    || (message != null && message.toLowerCase().contains("maximum upload size exceeded"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
