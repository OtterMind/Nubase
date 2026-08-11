package ai.nubase.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBootstrapSqlIdempotencyTest {

    private static final List<String> SCHEMA_RESOURCES = List.of(
            "db/supabase/init_auth_schema.sql",
            "db/supabase/init_storage_schema.sql",
            "db/supabase/init_mem_schema.sql",
            "db/supabase/init_ai_gateway_schema.sql",
            "db/supabase/init_assets_schema.sql"
    );

    @Test
    void schemaBootstrapTablesAndIndexesAreSafeToRerun() throws Exception {
        for (String resourcePath : SCHEMA_RESOURCES) {
            String sql = readResource(resourcePath);

            assertThat(Pattern.compile("(?mi)^\\s*CREATE\\s+TABLE\\s+(?!IF\\s+NOT\\s+EXISTS)")
                    .matcher(sql)
                    .find())
                    .as(resourcePath + " has a non-idempotent CREATE TABLE")
                    .isFalse();
            assertThat(Pattern.compile("(?mi)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?!IF\\s+NOT\\s+EXISTS)")
                    .matcher(sql)
                    .find())
                    .as(resourcePath + " has a non-idempotent CREATE INDEX")
                    .isFalse();
        }
    }

    @Test
    void bootstrapTriggersAreDroppedBeforeTheyAreRecreated() throws Exception {
        assertThat(readResource("db/supabase/init_auth_schema.sql"))
                .contains("DROP TRIGGER IF EXISTS update_users_updated_at ON auth.users;")
                .contains("DROP TRIGGER IF EXISTS update_sessions_updated_at ON auth.sessions;")
                .contains("DROP TRIGGER IF EXISTS update_refresh_tokens_updated_at ON auth.refresh_tokens;")
                .contains("DROP TRIGGER IF EXISTS update_identities_updated_at ON auth.identities;")
                .contains("DROP TRIGGER IF EXISTS update_mfa_factors_updated_at ON auth.mfa_factors;");
        assertThat(readResource("db/supabase/init_storage_schema.sql"))
                .contains("DROP TRIGGER IF EXISTS update_buckets_updated_at ON storage.buckets;")
                .contains("DROP TRIGGER IF EXISTS update_objects_updated_at ON storage.objects;")
                .contains("DROP TRIGGER IF EXISTS update_buckets_vectors_updated_at ON storage.buckets_vectors;")
                .contains("DROP TRIGGER IF EXISTS update_vector_indexes_updated_at ON storage.vector_indexes;");
        assertThat(readResource("db/supabase/init_roles.sql"))
                .contains("DROP EVENT TRIGGER IF EXISTS pgrst_watch;");
    }

    @Test
    void assetReadPolicyHidesBoundedOwnershipMetadataFromRlsRoles() throws Exception {
        assertThat(readResource("db/supabase/init_roles.sql"))
                .contains("""
                        DROP POLICY IF EXISTS "Asset files are readable" ON assets.files;
                        CREATE POLICY "Asset files are readable"
                            ON assets.files
                            FOR SELECT
                            USING (path <> '__goai_e2e' AND path !~ '^__goai_e2e/');
                        """)
                .contains("ALTER ROLE ${service_role} BYPASSRLS;");
    }

    private static String readResource(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
