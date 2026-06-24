package ai.nubase.postgrest.multidb;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Lightweight row for the admin project-listing query
 * ({@code GET /auth/v1/admin/projects}).
 * <p>
 * Carries only the columns the dashboard needs. Unlike {@link DatabaseConfig} this projection
 * does not decrypt anything — the listing has no use for the DB password or JWT secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectListItem {

    private String dbKey;
    private String appCode;
    private String appName;
    private String dbName;
    private String description;
    private String schemaName;
    private String initStatus;
    private String healthStatus;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    /** The tenant's service-role JWT. Returned to anyone who can see the project. */
    private String serviceRoleToken;
}
