package ai.nubase.cron.service;

import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.cron.CronProperties;
import ai.nubase.cron.dto.ScheduledJobDtos.CreateScheduledJobRequest;
import ai.nubase.cron.dto.ScheduledJobDtos.UpdateScheduledJobRequest;
import ai.nubase.cron.target.DbFunctionJobTarget;
import ai.nubase.metadata.cron.entity.ScheduledJob;
import ai.nubase.metadata.cron.entity.ScheduledJobRun;
import ai.nubase.metadata.cron.repository.ScheduledJobRepository;
import ai.nubase.metadata.cron.repository.ScheduledJobRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.nubase.cron.service.CronExceptions.CronException;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobAdminService {

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ScheduledJobRepository jobRepository;
    private final ScheduledJobRunRepository runRepository;
    private final CronProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<ScheduledJob> listJobs() {
        return jobRepository.findByProjectRefOrderByNameAsc(projectRef());
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public ScheduledJob getJob(String name) {
        return findJob(name);
    }

    @Transactional("metadataTransactionManager")
    public ScheduledJob createJob(CreateScheduledJobRequest request) {
        String projectRef = projectRef();
        if (jobRepository.existsByProjectRefAndName(projectRef, request.name())) {
            throw new CronException(HttpStatus.CONFLICT, "JOB_EXISTS", "Scheduled job already exists: " + request.name());
        }
        String cron = normalizeCron(request.cronExpression());
        ScheduledJob job = ScheduledJob.builder()
                .projectRef(projectRef)
                .name(request.name())
                .description(request.description())
                .cronExpression(cron)
                .targetType(validateTargetType(request.targetType()))
                .functionSlug(request.functionSlug())
                .httpMethod(normalizeHttpMethod(request.httpMethod()))
                .requestPath(request.requestPath())
                .requestBody(request.requestBody())
                .dbFunctionName(request.dbFunctionName())
                .dbFunctionArgs(writeArgs(request.dbFunctionArgs()))
                .timeoutSeconds(validateTimeout(request.timeoutSeconds()))
                .enabled(request.enabled() == null ? Boolean.TRUE : request.enabled())
                .nextRunAt(CronExpressions.next(cron, Instant.now()))
                .build();
        validateTarget(job);
        return jobRepository.save(job);
    }

    @Transactional("metadataTransactionManager")
    public ScheduledJob updateJob(String name, UpdateScheduledJobRequest request) {
        ScheduledJob job = findJob(name);
        if (request.description() != null) job.setDescription(request.description());
        if (StringUtils.hasText(request.cronExpression())) {
            job.setCronExpression(normalizeCron(request.cronExpression()));
        }
        if (request.functionSlug() != null) job.setFunctionSlug(request.functionSlug());
        if (request.httpMethod() != null) job.setHttpMethod(normalizeHttpMethod(request.httpMethod()));
        if (request.requestPath() != null) job.setRequestPath(request.requestPath());
        if (request.requestBody() != null) job.setRequestBody(request.requestBody());
        if (request.dbFunctionName() != null) job.setDbFunctionName(request.dbFunctionName());
        if (request.dbFunctionArgs() != null) job.setDbFunctionArgs(writeArgs(request.dbFunctionArgs()));
        if (request.timeoutSeconds() != null) job.setTimeoutSeconds(validateTimeout(request.timeoutSeconds()));
        if (request.enabled() != null) job.setEnabled(request.enabled());
        validateTarget(job);
        // Re-anchor the schedule on any change; a job re-enabled after a long pause
        // must not immediately fire for a long-past occurrence.
        job.setNextRunAt(CronExpressions.next(job.getCronExpression(), Instant.now()));
        job.setUpdatedAt(Instant.now());
        jobRepository.updateAdminFields(
                job.getId(),
                job.getDescription(),
                job.getCronExpression(),
                job.getTargetType(),
                job.getFunctionSlug(),
                job.getHttpMethod(),
                job.getRequestPath(),
                job.getRequestBody(),
                job.getDbFunctionName(),
                job.getDbFunctionArgs(),
                job.getTimeoutSeconds(),
                job.getEnabled(),
                job.getNextRunAt(),
                job.getUpdatedAt()
        );
        return job;
    }

    @Transactional("metadataTransactionManager")
    public void deleteJob(String name) {
        jobRepository.delete(findJob(name));
    }

    @Transactional(transactionManager = "metadataTransactionManager", readOnly = true)
    public List<ScheduledJobRun> listRuns(String jobName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (StringUtils.hasText(jobName)) {
            return runRepository.findByJobIdOrderByStartedAtDesc(findJob(jobName).getId(), PageRequest.of(0, safeLimit));
        }
        return runRepository.findByProjectRefOrderByStartedAtDesc(projectRef(), PageRequest.of(0, safeLimit));
    }

    private ScheduledJob findJob(String name) {
        return jobRepository.findByProjectRefAndName(projectRef(), name)
                .orElseThrow(() -> new CronException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Scheduled job not found: " + name));
    }

    private String normalizeCron(String expression) {
        try {
            return CronExpressions.normalize(expression);
        } catch (IllegalArgumentException e) {
            throw new CronException(HttpStatus.BAD_REQUEST, "INVALID_CRON", "Invalid cron expression: " + e.getMessage());
        }
    }

    private String validateTargetType(String targetType) {
        if (ScheduledJob.TARGET_EDGE_FUNCTION.equals(targetType) || ScheduledJob.TARGET_DB_FUNCTION.equals(targetType)) {
            return targetType;
        }
        throw new CronException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_TYPE",
                "targetType must be edge_function or db_function");
    }

    private void validateTarget(ScheduledJob job) {
        if (ScheduledJob.TARGET_EDGE_FUNCTION.equals(job.getTargetType())) {
            if (!StringUtils.hasText(job.getFunctionSlug())) {
                throw new CronException(HttpStatus.BAD_REQUEST, "FUNCTION_SLUG_REQUIRED",
                        "functionSlug is required for edge_function jobs");
            }
        } else {
            String fn = job.getDbFunctionName();
            if (!StringUtils.hasText(fn) || !DbFunctionJobTarget.FUNCTION_NAME.matcher(fn).matches()) {
                throw new CronException(HttpStatus.BAD_REQUEST, "INVALID_DB_FUNCTION",
                        "dbFunctionName must be a valid Postgres identifier");
            }
        }
    }

    private String normalizeHttpMethod(String method) {
        if (!StringUtils.hasText(method)) return null;
        String upper = method.toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(upper)) {
            throw new CronException(HttpStatus.BAD_REQUEST, "INVALID_HTTP_METHOD", "Unsupported HTTP method: " + method);
        }
        return upper;
    }

    private Integer validateTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) return null;
        if (timeoutSeconds < 1 || timeoutSeconds > properties.getMaxTimeoutSeconds()) {
            throw new CronException(HttpStatus.BAD_REQUEST, "INVALID_TIMEOUT",
                    "timeoutSeconds must be between 1 and " + properties.getMaxTimeoutSeconds());
        }
        return timeoutSeconds;
    }

    private String writeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(args);
        } catch (Exception e) {
            throw new CronException(HttpStatus.BAD_REQUEST, "INVALID_ARGS", "dbFunctionArgs must be a JSON object");
        }
    }

    private String projectRef() {
        String projectRef = MultiTenancyContext.getAppCode();
        if (!StringUtils.hasText(projectRef)) {
            throw new CronException(HttpStatus.UNAUTHORIZED, "TENANT_CONTEXT_REQUIRED", "Project context is required");
        }
        return projectRef;
    }
}
