package ai.nubase.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the tenant auth helper SQL contract without requiring a live tenant DB.
 */
class AuthSchemaHelperSqlTest {

    @Test
    void initAuthSchemaDefinesSupabaseCompatibleAuthHelpers() throws Exception {
        String sql = readInitAuthSchema();

        assertThat(sql)
                .contains("CREATE OR REPLACE FUNCTION auth.jwt()")
                .contains("RETURNS jsonb")
                .contains("current_setting('request.jwt.claims', true)")
                .contains("'{}'::jsonb")
                .contains("CREATE OR REPLACE FUNCTION auth.uid()")
                .contains("NULLIF(auth.jwt() ->> 'sub', '')::uuid")
                .contains("CREATE OR REPLACE FUNCTION auth.role()")
                .contains("RETURNS text")
                .contains("current_setting('request.jwt.claim.role', true)")
                .contains("NULLIF(auth.jwt() ->> 'role', '')");
    }

    @Test
    void authRoleCommentDocumentsJwtRoleNotBusinessRole() throws Exception {
        String sql = readInitAuthSchema();

        assertThat(sql)
                .contains("database/JWT role claim")
                .contains("anon, authenticated, service_role")
                .contains("Application roles such as admin/manager should be modeled in app tables or app metadata");
    }

    private static String readInitAuthSchema() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/supabase/init_auth_schema.sql");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
