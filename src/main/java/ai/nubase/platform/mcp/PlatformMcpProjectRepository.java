package ai.nubase.platform.mcp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlatformMcpProjectRepository {

    public static final String EXTERNAL_PLATFORM = "nubase-platform-mcp";

    private final JdbcTemplate metadataJdbcTemplate;

    public PlatformMcpProjectRepository(
            @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate) {
        this.metadataJdbcTemplate = metadataJdbcTemplate;
    }

    /**
     * Load only non-sensitive project state and require ownership by the automation actor.
     * No key, JWT secret, JDBC URL, database user, or encrypted credential is selected.
     */
    public Optional<OwnedProjectState> findOwnedByRef(String actor, String ref) {
        List<OwnedProjectState> rows = metadataJdbcTemplate.query("""
                SELECT c.db_key, c.app_code, c.app_name, c.init_status, c.enabled,
                       c.init_started_at, c.init_completed_at, pei.platform_user_id,
                       (NULLIF(BTRIM(c.jdbc_url), '') IS NOT NULL
                        AND NULLIF(BTRIM(c.db_user), '') IS NOT NULL
                        AND NULLIF(BTRIM(c.db_password_encrypted), '') IS NOT NULL
                        AND NULLIF(BTRIM(c.jwt_secret), '') IS NOT NULL
                        AND NULLIF(BTRIM(c.service_role_token), '') IS NOT NULL
                        AND NULLIF(BTRIM(c.authenticated_token), '') IS NOT NULL)
                           AS credentials_present
                FROM database_configs c
                JOIN platform_user_projects pup
                  ON pup.db_key = c.db_key AND pup.role = 'owner'
                JOIN platform_external_identities pei
                  ON pei.platform_user_id = pup.user_id
                 AND pei.external_platform = ?
                 AND pei.external_user_id = ?
                WHERE c.db_key = ?
                  AND c.app_code = ?
                """, (rs, rowNum) -> new OwnedProjectState(
                        rs.getString("db_key"),
                        rs.getString("app_code"),
                        rs.getString("app_name"),
                        rs.getString("init_status"),
                        rs.getBoolean("enabled"),
                        timestamp(rs.getTimestamp("init_started_at")),
                        timestamp(rs.getTimestamp("init_completed_at")),
                        rs.getObject("platform_user_id", UUID.class),
                        rs.getBoolean("credentials_present")),
                EXTERNAL_PLATFORM, actor, ref, ref);
        return rows.stream().findFirst();
    }

    public int countOwnedProjects(String actor) {
        Integer count = metadataJdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM database_configs c
                JOIN platform_user_projects pup
                  ON pup.db_key = c.db_key AND pup.role = 'owner'
                JOIN platform_external_identities pei
                  ON pei.platform_user_id = pup.user_id
                 AND pei.external_platform = ?
                 AND pei.external_user_id = ?
                """, Integer.class, EXTERNAL_PLATFORM, actor);
        return count == null ? 0 : count;
    }

    private static Instant timestamp(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record OwnedProjectState(
            String dbKey,
            String ref,
            String name,
            String initStatus,
            boolean enabled,
            Instant initStartedAt,
            Instant initCompletedAt,
            UUID ownerId,
            boolean credentialsPresent) {
    }
}
