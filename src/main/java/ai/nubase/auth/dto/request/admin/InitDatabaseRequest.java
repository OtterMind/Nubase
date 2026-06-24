package ai.nubase.auth.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for initializing a new database with Supabase schema structure.
 * <p>
 * This will:
 * 1. Create a new PostgreSQL database
 * 2. Create a dedicated database user with random password
 * 3. Grant permissions to the user
 * 4. Initialize Supabase schemas (auth, storage, public)
 * 5. Create auth and storage tables
 * 6. Setup roles and RLS policies
 * 7. Save configuration to database_configs table
 * <p>
 * Supabase Standard Architecture:
 * - auth schema: authentication tables (users, sessions, refresh_tokens, identities)
 * - storage schema: file storage tables (buckets, objects)
 * - public schema: user business tables (default PostgreSQL schema)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InitDatabaseRequest {

    // ========== Database Identity ==========

    /**
     * Database key - unique identifier for the database configuration
     * Example: "tenant1", "company-abc"
     */
    @NotBlank(message = "Database key is required")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Database key must match pattern [a-zA-Z0-9_-]+")
    private String dbKey;

    /**
     * Physical database name (will be created in PostgreSQL)
     * Example: "tenant1_db", "company_abc_db"
     */
    @NotBlank(message = "Database name is required")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "Database name must start with lowercase letter and contain only lowercase letters, numbers, and underscores")
    private String dbName;

    /**
     * Application/Tenant code (unique identifier for JWT)
     * Example: "tenant1", "app-abc"
     */
    @NotBlank(message = "App code is required")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "App code must match pattern [a-zA-Z0-9_-]+")
    private String appCode;

    /**
     * Application/Tenant display name
     * Example: "Tenant 1", "ABC Company"
     */
    private String appName;

    /**
     * Description of the database
     */
    private String description;

    // ========== Supabase Roles ==========

    /**
     * Service role name (defaults to "service_role")
     * This role has full admin privileges and can bypass RLS
     */
    private String serviceRole;

    /**
     * Authenticated role name (defaults to "authenticated")
     * This role is used for authenticated users, must follow RLS policies
     */
    private String authenticatedRole;

    /**
     * Anonymous role name (defaults to "anon")
     * This role is used for anonymous/unauthenticated access
     */
    private String anonRole;

    // ========== Optional Configuration ==========

    /**
     * Connection pool size (defaults to 10)
     */
    private Integer poolSize;

    /**
     * Optional name of the third-party platform creating this project (the integrating system).
     * Together with {@link #externalUserId} it keys the external → Nubase user mapping, so it is
     * required whenever {@code externalUserId} is provided.
     */
    private String externalPlatform;

    /**
     * Optional creator identity in the calling third-party system — this is NOT a Nubase platform
     * user id. When provided (together with {@link #externalPlatform}) the project is owned by the
     * dedicated, non-super-admin Nubase user mapped from this external identity. Also persisted to
     * {@code database_configs.created_by} for audit.
     */
    private String externalUserId;
}
