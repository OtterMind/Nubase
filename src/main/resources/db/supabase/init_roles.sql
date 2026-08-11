-- Supabase Roles and Permissions Initialization
-- Creates roles and grants appropriate permissions on auth and storage schemas
-- Based on Supabase's role-based access control model


-- Grant usage on auth schema
GRANT USAGE ON SCHEMA auth TO ${db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA auth TO ${db_user};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA auth TO ${db_user};

-- Grant usage on storage schema
GRANT USAGE ON SCHEMA storage TO ${db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA storage TO ${db_user};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA storage TO ${db_user};

-- Grant usage on public schema (for business data)
GRANT USAGE ON SCHEMA public TO ${db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${db_user};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${db_user};

-- MEM-BEGIN: db_user grants on mem schema
-- Each MEM-block is stripped by DatabaseInitService when nubase.mem.enabled=false
-- (the schema does not exist in that case, so granting on it would fail with
-- 'schema "mem" does not exist').
GRANT USAGE ON SCHEMA mem TO ${db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mem TO ${db_user};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA mem TO ${db_user};
-- MEM-END

-- AI_GATEWAY-BEGIN: db_user grants on ai_gateway schema
-- Stripped by DatabaseInitService when nubase.ai-gateway.enabled=false (the schema
-- does not exist in that case). db_user owns the schema, so these are belt-and-braces.
GRANT USAGE ON SCHEMA ai_gateway TO ${db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ai_gateway TO ${db_user};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA ai_gateway TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA ai_gateway
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA ai_gateway
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${db_user};
-- AI_GATEWAY-END

-- ASSETS-BEGIN: db_user grants on assets schema
-- Stripped by DatabaseInitService when nubase.assets.enabled=false (the schema
-- does not exist in that case).
GRANT USAGE ON SCHEMA assets TO ${db_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA assets TO ${db_user};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA assets TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA assets
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA assets
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${db_user};
-- ASSETS-END

-- Set default privileges for future tables in auth schema
ALTER DEFAULT PRIVILEGES IN SCHEMA auth
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA auth
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${db_user};

-- Set default privileges for future tables in storage schema
ALTER DEFAULT PRIVILEGES IN SCHEMA storage
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA storage
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${db_user};

-- Set default privileges for future tables in public schema
-- Specify FOR ROLE to ensure the privileges are set for objects created by dbUser
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${db_user};
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${db_user};

-- MEM-BEGIN: db_user default privileges on mem schema
ALTER DEFAULT PRIVILEGES IN SCHEMA mem
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${db_user};
ALTER DEFAULT PRIVILEGES IN SCHEMA mem
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${db_user};
-- MEM-END


-- ==================================================
-- SERVICE ROLE (Admin - can bypass RLS)
-- ==================================================
-- CREATE ROLE ${service_role} NOLOGIN NOINHERIT;

-- Grant usage on auth schema
GRANT USAGE ON SCHEMA auth TO ${service_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA auth TO ${service_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA auth TO ${service_role};

-- Grant usage on storage schema
GRANT USAGE ON SCHEMA storage TO ${service_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA storage TO ${service_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA storage TO ${service_role};

-- Grant usage on public schema (for business data)
GRANT USAGE ON SCHEMA public TO ${service_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${service_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${service_role};

-- Set default privileges for future tables in auth schema
ALTER DEFAULT PRIVILEGES IN SCHEMA auth
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA auth
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${service_role};

-- Set default privileges for future tables in storage schema
ALTER DEFAULT PRIVILEGES IN SCHEMA storage
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA storage
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${service_role};

-- Set default privileges for future tables in public schema
-- For any user creating tables
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${service_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${service_role};

-- MEM-BEGIN: service_role grants on mem schema
GRANT USAGE ON SCHEMA mem TO ${service_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mem TO ${service_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA mem TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA mem
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA mem
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${service_role};
-- MEM-END

-- AI_GATEWAY-BEGIN: service_role grants on ai_gateway schema
GRANT USAGE ON SCHEMA ai_gateway TO ${service_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ai_gateway TO ${service_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA ai_gateway TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ai_gateway
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ai_gateway
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${service_role};
-- AI_GATEWAY-END

-- ASSETS-BEGIN: service_role grants on assets schema
GRANT USAGE ON SCHEMA assets TO ${service_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA assets TO ${service_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA assets TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA assets
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${service_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA assets
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${service_role};
-- ASSETS-END

-- Grant bypass RLS privilege to service role
ALTER ROLE ${service_role} BYPASSRLS;

-- ==================================================
-- AUTHENTICATED ROLE (Authenticated users - must follow RLS)
-- ==================================================
-- CREATE ROLE ${authenticated_role} NOLOGIN NOINHERIT;

-- Grant usage on auth schema (limited access)
GRANT USAGE ON SCHEMA auth TO ${authenticated_role};

-- Grant usage on storage schema
GRANT USAGE ON SCHEMA storage TO ${authenticated_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA storage TO ${authenticated_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA storage TO ${authenticated_role};

-- Grant usage on public schema (for business data)
GRANT USAGE ON SCHEMA public TO ${authenticated_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${authenticated_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${authenticated_role};

-- MEM-BEGIN: authenticated_role grants on mem schema (RLS further restricts)
GRANT USAGE ON SCHEMA mem TO ${authenticated_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mem TO ${authenticated_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA mem TO ${authenticated_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA mem
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${authenticated_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA mem
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${authenticated_role};
-- MEM-END

-- Set default privileges for future tables in storage schema
ALTER DEFAULT PRIVILEGES IN SCHEMA storage
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${authenticated_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA storage
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${authenticated_role};

-- Set default privileges for future tables in public schema
-- For any user creating tables
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${authenticated_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${authenticated_role};

-- Remove direct access to auth tables (must go through RLS and service APIs)
REVOKE ALL ON TABLE auth.users FROM ${authenticated_role};
REVOKE ALL ON TABLE auth.sessions FROM ${authenticated_role};
REVOKE ALL ON TABLE auth.refresh_tokens FROM ${authenticated_role};
REVOKE ALL ON TABLE auth.identities FROM ${authenticated_role};

-- ==================================================
-- ANON ROLE (Anonymous/unauthenticated - most restricted)
-- ==================================================
-- CREATE ROLE ${anon_role} NOLOGIN NOINHERIT;

-- Grant usage on auth schema (very limited)
GRANT USAGE ON SCHEMA auth TO ${anon_role};

-- Grant usage on storage schema (read-only for public buckets)
GRANT USAGE ON SCHEMA storage TO ${anon_role};
GRANT SELECT ON ALL TABLES IN SCHEMA storage TO ${anon_role};

-- Grant usage on public schema (typically read-only or limited)
GRANT USAGE ON SCHEMA public TO ${anon_role};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${anon_role};
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO ${anon_role};

-- MEM-BEGIN: anon role gets USAGE on mem schema (RLS rejects all queries)
GRANT USAGE ON SCHEMA mem TO ${anon_role};
-- MEM-END

-- Set default privileges for future tables in public schema
-- For any user creating tables
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${anon_role};
ALTER DEFAULT PRIVILEGES FOR ROLE ${db_user} IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ${anon_role};

-- Remove all access to auth tables (must use signup/login APIs)
REVOKE ALL ON TABLE auth.users FROM ${anon_role};
REVOKE ALL ON TABLE auth.sessions FROM ${anon_role};
REVOKE ALL ON TABLE auth.refresh_tokens FROM ${anon_role};
REVOKE ALL ON TABLE auth.identities FROM ${anon_role};

-- ==================================================
-- ROW LEVEL SECURITY POLICIES
-- ==================================================

-- Auth users table: users can only see their own data
DROP POLICY IF EXISTS "Users can view own user data" ON auth.users;
CREATE POLICY "Users can view own user data"
    ON auth.users
    FOR SELECT
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update own user data" ON auth.users;
CREATE POLICY "Users can update own user data"
    ON auth.users
    FOR UPDATE
    USING (auth.uid() = id);

-- Auth sessions table: users can only see their own sessions
DROP POLICY IF EXISTS "Users can view own sessions" ON auth.sessions;
CREATE POLICY "Users can view own sessions"
    ON auth.sessions
    FOR SELECT
    USING (auth.uid() = user_id);

-- Auth refresh_tokens table: users can only manage their own tokens
DROP POLICY IF EXISTS "Users can view own refresh tokens" ON auth.refresh_tokens;
CREATE POLICY "Users can view own refresh tokens"
    ON auth.refresh_tokens
    FOR SELECT
    USING (auth.uid() = user_id);

-- Auth identities table: users can only see their own identities
DROP POLICY IF EXISTS "Users can view own identities" ON auth.identities;
CREATE POLICY "Users can view own identities"
    ON auth.identities
    FOR SELECT
    USING (auth.uid() = user_id);

-- Storage buckets: authenticated users can read all buckets
DROP POLICY IF EXISTS "Authenticated users can view buckets" ON storage.buckets;
CREATE POLICY "Authenticated users can view buckets"
    ON storage.buckets
    FOR SELECT
    TO ${authenticated_role}
    USING (true);

-- Storage buckets: bucket owners can manage their buckets
DROP POLICY IF EXISTS "Bucket owners can manage buckets" ON storage.buckets;
CREATE POLICY "Bucket owners can manage buckets"
    ON storage.buckets
    FOR ALL
    TO ${authenticated_role}
    USING (auth.uid() = owner);

-- Storage objects: users can view public bucket objects
DROP POLICY IF EXISTS "Public bucket objects are viewable" ON storage.objects;
CREATE POLICY "Public bucket objects are viewable"
    ON storage.objects
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM storage.buckets
            WHERE buckets.id = objects.bucket_id
            AND buckets.public = true
        )
    );

-- Storage objects: users can manage their own objects
DROP POLICY IF EXISTS "Users can manage own objects" ON storage.objects;
CREATE POLICY "Users can manage own objects"
    ON storage.objects
    FOR ALL
    TO ${authenticated_role}
    USING (auth.uid() = owner);

-- MEM-BEGIN: row-level security policies on mem.* tables
-- ==================================================
-- MEM SCHEMA POLICIES (authenticated users see only own memories)
-- ==================================================

-- mem.memories: authenticated users can manage their own memories
DROP POLICY IF EXISTS "Users can manage own memories" ON mem.memories;
CREATE POLICY "Users can manage own memories"
    ON mem.memories
    FOR ALL
    TO ${authenticated_role}
    USING (auth.uid() = user_id);

-- mem.memory_history: authenticated users can view history of their own memories
DROP POLICY IF EXISTS "Users can view own memory history" ON mem.memory_history;
CREATE POLICY "Users can view own memory history"
    ON mem.memory_history
    FOR SELECT
    TO ${authenticated_role}
    USING (
        EXISTS (
            SELECT 1 FROM mem.memories m
            WHERE m.id = memory_history.memory_id
            AND m.user_id = auth.uid()
        )
    );

-- mem.entities: authenticated users can manage entities they own
DROP POLICY IF EXISTS "Users can manage own entities" ON mem.entities;
CREATE POLICY "Users can manage own entities"
    ON mem.entities
    FOR ALL
    TO ${authenticated_role}
    USING (auth.uid() = user_id);

-- mem.session_messages: authenticated users can only see messages they own.
-- Enforced via the user_id FK column — same shape as mem.memories.
-- Agent/run-only sessions (user_id IS NULL) are invisible to authenticated callers
-- and only reachable via service_role.
DROP POLICY IF EXISTS "Users can manage own session messages" ON mem.session_messages;
CREATE POLICY "Users can manage own session messages"
    ON mem.session_messages
    FOR ALL
    TO ${authenticated_role}
    USING (auth.uid() = user_id);

-- mem.config: authenticated users can READ the config (the Studio settings page
-- needs to display the current values), but only service_role can mutate.
-- service_role bypasses RLS, so we only need to grant authenticated SELECT.
DROP POLICY IF EXISTS "Authenticated can read mem config" ON mem.config;
CREATE POLICY "Authenticated can read mem config"
    ON mem.config
    FOR SELECT
    TO ${authenticated_role}
    USING (true);
-- MEM-END

-- ASSETS-BEGIN: read grants and policies on assets.* tables
-- General assets are public by definition (served apikey-free at /assets/v1/**).
-- Bounded marker ownership metadata stays visible only to service_role, which
-- bypasses RLS; authenticated users may read all other metadata and settings.
GRANT USAGE ON SCHEMA assets TO ${authenticated_role};
GRANT SELECT ON ALL TABLES IN SCHEMA assets TO ${authenticated_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA assets
    GRANT SELECT ON TABLES TO ${authenticated_role};
GRANT USAGE ON SCHEMA assets TO ${anon_role};
GRANT SELECT ON ALL TABLES IN SCHEMA assets TO ${anon_role};

DROP POLICY IF EXISTS "Asset files are readable" ON assets.files;
CREATE POLICY "Asset files are readable"
    ON assets.files
    FOR SELECT
    USING (path <> '__goai_e2e' AND path !~ '^__goai_e2e/');

DROP POLICY IF EXISTS "Asset settings are readable" ON assets.settings;
CREATE POLICY "Asset settings are readable"
    ON assets.settings
    FOR SELECT
    USING (true);
-- ASSETS-END

-- ==================================================
-- GRANT ROLES TO DATABASE CONNECTION USER
-- ==================================================
-- Allow the database connection user to SET ROLE to these roles
GRANT ${service_role} TO ${db_user};
GRANT ${authenticated_role} TO ${db_user};
GRANT ${anon_role} TO ${db_user};

CREATE OR REPLACE FUNCTION pgrst_watch() RETURNS event_trigger AS $$
BEGIN
NOTIFY pgrst, 'reload schema';
END;
$$ LANGUAGE plpgsql;

DROP EVENT TRIGGER IF EXISTS pgrst_watch;
CREATE EVENT TRIGGER pgrst_watch
ON ddl_command_end
EXECUTE PROCEDURE pgrst_watch();
