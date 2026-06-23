-- Supabase Auth Schema Initialization
-- Creates authentication-related tables in the 'auth' schema
-- Based on Supabase GoTrue authentication system

-- Ensure pgcrypto extension is available
-- CREATE EXTENSION IF NOT EXISTS "pgcrypto" SCHEMA public;

-- Create auth.users table
CREATE TABLE IF NOT EXISTS auth.users
(
    id                          UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    instance_id                 UUID,
    aud                         VARCHAR(255),
    role                        VARCHAR(255)             DEFAULT '${authenticated_role}',

    -- Email related
    email                       VARCHAR(255) UNIQUE,
    encrypted_password          VARCHAR(255),
    email_confirmed_at          TIMESTAMP WITH TIME ZONE,

    -- Phone related
    phone                       VARCHAR(15) UNIQUE,
    phone_confirmed_at          TIMESTAMP WITH TIME ZONE,

    -- Confirmation token
    confirmation_token          VARCHAR(255),
    confirmation_sent_at        TIMESTAMP WITH TIME ZONE,

    -- Recovery token
    recovery_token              VARCHAR(255),
    recovery_sent_at            TIMESTAMP WITH TIME ZONE,

    -- Email change tokens
    email_change_token_new      VARCHAR(255),
    email_change_token_current  VARCHAR(255),
    email_change                VARCHAR(255),
    email_change_sent_at        TIMESTAMP WITH TIME ZONE,
    email_change_confirm_status SMALLINT                 DEFAULT 0,

    -- Metadata (JSONB)
    raw_app_meta_data           JSONB                    DEFAULT CAST('{}' AS JSONB),
    raw_user_meta_data          JSONB                    DEFAULT CAST('{}' AS JSONB),

    -- Timestamps
    created_at                  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at                  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_sign_in_at             TIMESTAMP WITH TIME ZONE,

    -- Other
    invited_at                  TIMESTAMP WITH TIME ZONE,
    banned_until                TIMESTAMP WITH TIME ZONE,
    deleted_at                  TIMESTAMP WITH TIME ZONE,

    -- Flags
    is_super_admin              BOOLEAN                  DEFAULT FALSE,
    is_sso_user                 BOOLEAN                  DEFAULT FALSE,
    is_anonymous                BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- Reauthentication token
    reauthentication_token      VARCHAR(255),
    reauthentication_sent_at    TIMESTAMP WITH TIME ZONE,

    CONSTRAINT users_email_check CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Create trigger function to automatically update updated_at
CREATE OR REPLACE FUNCTION auth.update_updated_at_column()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger
CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE
    ON auth.users
    FOR EACH ROW
EXECUTE FUNCTION auth.update_updated_at_column();

-- Get JWT claims helper functions
CREATE OR REPLACE FUNCTION auth.jwt()
    RETURNS jsonb AS $$
    SELECT COALESCE(
        NULLIF(current_setting('request.jwt.claims', true), '')::jsonb,
        '{}'::jsonb
    );
$$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION auth.uid()
    RETURNS uuid AS $$
    SELECT NULLIF(auth.jwt() ->> 'sub', '')::uuid;
$$ LANGUAGE sql STABLE;

-- Returns the database/JWT role claim (for example anon, authenticated, service_role).
-- Application roles such as admin/manager should be modeled in app tables or app metadata.
CREATE OR REPLACE FUNCTION auth.role()
    RETURNS text AS $$
    SELECT COALESCE(
        NULLIF(current_setting('request.jwt.claim.role', true), ''),
        NULLIF(auth.jwt() ->> 'role', '')
    );
$$ LANGUAGE sql STABLE;

-- Add comments
COMMENT ON TABLE auth.users IS 'Auth users table';
COMMENT ON COLUMN auth.users.raw_app_meta_data IS 'Application metadata controlled by the service';
COMMENT ON COLUMN auth.users.raw_user_meta_data IS 'User metadata that can be updated by the user';
COMMENT ON COLUMN auth.users.encrypted_password IS 'BCrypt hashed password';
COMMENT ON COLUMN auth.users.confirmation_token IS 'Token for email confirmation';
COMMENT ON COLUMN auth.users.recovery_token IS 'Token for password recovery';
COMMENT ON COLUMN auth.users.role is 'role enum: ${authenticated_role},${service_role}';

-- Enable Row Level Security
ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;

-- Create auth.sessions table
CREATE TABLE IF NOT EXISTS auth.sessions
(
    id           UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,

    -- Session information
    aal          VARCHAR(10), -- Authentication Assurance Level (aal1, aal2)
    factor_id    UUID,
    not_after    TIMESTAMP WITH TIME ZONE,
    refreshed_at TIMESTAMP WITH TIME ZONE,
    user_agent   TEXT,
    ip           VARCHAR(64),
    tag          VARCHAR(255),

    -- Timestamps
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create trigger
CREATE TRIGGER update_sessions_updated_at
    BEFORE UPDATE
    ON auth.sessions
    FOR EACH ROW
EXECUTE FUNCTION auth.update_updated_at_column();

-- Add comments
COMMENT ON TABLE auth.sessions IS 'User sessions table';
COMMENT ON COLUMN auth.sessions.aal IS 'Authentication Assurance Level (aal1 for password, aal2 for MFA)';
COMMENT ON COLUMN auth.sessions.factor_id IS 'MFA factor ID if AAL2';
COMMENT ON COLUMN auth.sessions.not_after IS 'Session expiration time';
COMMENT ON COLUMN auth.sessions.refreshed_at IS 'Last time session was refreshed';
COMMENT ON COLUMN auth.sessions.user_agent IS 'User agent string from the request';
COMMENT ON COLUMN auth.sessions.ip IS 'IP address of the user';

-- Enable Row Level Security
ALTER TABLE auth.sessions ENABLE ROW LEVEL SECURITY;

-- Create auth.refresh_tokens table
CREATE TABLE IF NOT EXISTS auth.refresh_tokens
(
    id          BIGSERIAL PRIMARY KEY,
    instance_id UUID,
    token       VARCHAR(255) UNIQUE NOT NULL,
    user_id     UUID                NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    parent      VARCHAR(255),
    session_id  UUID REFERENCES auth.sessions (id) ON DELETE CASCADE,

    -- Token status
    revoked     BOOLEAN                  DEFAULT FALSE,

    -- Timestamps
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create trigger
CREATE TRIGGER update_refresh_tokens_updated_at
    BEFORE UPDATE
    ON auth.refresh_tokens
    FOR EACH ROW
EXECUTE FUNCTION auth.update_updated_at_column();

-- Add comments
COMMENT ON TABLE auth.refresh_tokens IS 'Refresh tokens for session management';
COMMENT ON COLUMN auth.refresh_tokens.token IS 'The refresh token string';
COMMENT ON COLUMN auth.refresh_tokens.parent IS 'Parent refresh token for rotation tracking';
COMMENT ON COLUMN auth.refresh_tokens.revoked IS 'Whether the token has been revoked';
COMMENT ON COLUMN auth.refresh_tokens.session_id IS 'Associated session ID';

-- Enable Row Level Security
ALTER TABLE auth.refresh_tokens ENABLE ROW LEVEL SECURITY;

-- Create auth.identities table
CREATE TABLE IF NOT EXISTS auth.identities
(
    id              UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    provider_id     VARCHAR(255) NOT NULL,
    user_id         UUID         NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,

    -- Identity information
    identity_data   JSONB        NOT NULL,
    provider        VARCHAR(255) NOT NULL,

    -- Timestamps
    last_sign_in_at TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Email (generated column from identity_data)
    email           VARCHAR(255) GENERATED ALWAYS AS (LOWER(identity_data ->> 'email')) STORED,

    CONSTRAINT identities_provider_id_provider_unique UNIQUE (provider_id, provider)
);

-- Create trigger
CREATE TRIGGER update_identities_updated_at
    BEFORE UPDATE
    ON auth.identities
    FOR EACH ROW
EXECUTE FUNCTION auth.update_updated_at_column();

-- Add comments
COMMENT ON TABLE auth.identities IS 'OAuth and SSO identities';
COMMENT ON COLUMN auth.identities.provider IS 'Auth provider name (email, google, github, etc.)';
COMMENT ON COLUMN auth.identities.provider_id IS 'Unique ID from the provider';
COMMENT ON COLUMN auth.identities.identity_data IS 'Identity data from the provider';
COMMENT ON COLUMN auth.identities.email IS 'Email extracted from identity_data';

-- Enable Row Level Security
ALTER TABLE auth.identities ENABLE ROW LEVEL SECURITY;

-- Create indexes for users table
CREATE INDEX IF NOT EXISTS users_email_idx ON auth.users(email) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_phone_idx ON auth.users(phone) WHERE phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_confirmation_token_idx ON auth.users(confirmation_token) WHERE confirmation_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_recovery_token_idx ON auth.users(recovery_token) WHERE recovery_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_email_change_token_new_idx ON auth.users(email_change_token_new) WHERE email_change_token_new IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_email_change_token_current_idx ON auth.users(email_change_token_current) WHERE email_change_token_current IS NOT NULL;
CREATE INDEX IF NOT EXISTS users_is_super_admin_idx ON auth.users(is_super_admin) WHERE is_super_admin = TRUE;
CREATE INDEX IF NOT EXISTS users_instance_id_idx ON auth.users(instance_id) WHERE instance_id IS NOT NULL;

-- Create indexes for sessions table
CREATE INDEX IF NOT EXISTS sessions_user_id_idx ON auth.sessions(user_id);
CREATE INDEX IF NOT EXISTS sessions_not_after_idx ON auth.sessions(not_after) WHERE not_after IS NOT NULL;

-- Create indexes for refresh_tokens table
CREATE INDEX IF NOT EXISTS refresh_tokens_token_idx ON auth.refresh_tokens(token);
CREATE INDEX IF NOT EXISTS refresh_tokens_user_id_idx ON auth.refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS refresh_tokens_session_id_idx ON auth.refresh_tokens(session_id) WHERE session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS refresh_tokens_parent_idx ON auth.refresh_tokens(parent) WHERE parent IS NOT NULL;
CREATE INDEX IF NOT EXISTS refresh_tokens_revoked_idx ON auth.refresh_tokens(revoked) WHERE revoked = FALSE;

-- Create indexes for identities table
CREATE INDEX IF NOT EXISTS identities_user_id_idx ON auth.identities(user_id);
CREATE INDEX IF NOT EXISTS identities_email_idx ON auth.identities(email) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS identities_provider_idx ON auth.identities(provider);

-- Performance optimization comments
COMMENT ON INDEX auth.users_email_idx IS 'Fast lookup by email for login';
COMMENT ON INDEX auth.refresh_tokens_token_idx IS 'Fast lookup by refresh token';
COMMENT ON INDEX auth.sessions_user_id_idx IS 'Fast lookup of user sessions';
COMMENT ON INDEX auth.identities_user_id_idx IS 'Fast lookup of user identities';

-- ============================================================================
-- Multi-Factor Authentication (MFA) tables (Supabase GoTrue parity)
-- ============================================================================

-- A factor is an enrolled authentication method (TOTP authenticator app or phone/SMS).
CREATE TABLE auth.mfa_factors
(
    id                 UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    user_id            UUID         NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    friendly_name      VARCHAR(255),
    factor_type        VARCHAR(255) NOT NULL,                 -- 'totp' | 'phone'
    status             VARCHAR(255) NOT NULL DEFAULT 'unverified', -- 'unverified' | 'verified'
    secret             VARCHAR(255),                          -- Base32 TOTP shared secret
    phone              VARCHAR(15),                           -- E.164 phone for SMS factors
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_challenged_at TIMESTAMP WITH TIME ZONE
);

CREATE TRIGGER update_mfa_factors_updated_at
    BEFORE UPDATE ON auth.mfa_factors
    FOR EACH ROW EXECUTE FUNCTION auth.update_updated_at_column();

CREATE INDEX mfa_factors_user_id_idx ON auth.mfa_factors(user_id);
CREATE UNIQUE INDEX mfa_factors_user_friendly_name_idx
    ON auth.mfa_factors(user_id, friendly_name) WHERE friendly_name IS NOT NULL;

COMMENT ON TABLE auth.mfa_factors IS 'Enrolled MFA factors (TOTP / phone)';
ALTER TABLE auth.mfa_factors ENABLE ROW LEVEL SECURITY;

-- A challenge is a single verification attempt against a factor.
CREATE TABLE auth.mfa_challenges
(
    id          UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    factor_id   UUID NOT NULL REFERENCES auth.mfa_factors (id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    verified_at TIMESTAMP WITH TIME ZONE,
    ip_address  VARCHAR(64),
    otp_code    VARCHAR(10)                                  -- only for phone factors
);

CREATE INDEX mfa_challenges_factor_id_idx ON auth.mfa_challenges(factor_id);
COMMENT ON TABLE auth.mfa_challenges IS 'MFA verification challenges';
ALTER TABLE auth.mfa_challenges ENABLE ROW LEVEL SECURITY;

-- AMR (Authentication Methods References) records which methods backed a session.
CREATE TABLE auth.mfa_amr_claims
(
    id                    UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    session_id            UUID NOT NULL REFERENCES auth.sessions (id) ON DELETE CASCADE,
    authentication_method VARCHAR(255) NOT NULL,             -- 'password' | 'otp' | 'totp' | 'oauth' | 'anonymous'
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT mfa_amr_claims_session_method_unique UNIQUE (session_id, authentication_method)
);

CREATE INDEX mfa_amr_claims_session_id_idx ON auth.mfa_amr_claims(session_id);
COMMENT ON TABLE auth.mfa_amr_claims IS 'Authentication methods references per session';
ALTER TABLE auth.mfa_amr_claims ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- One-time tokens (magic link / email-OTP / phone-OTP / reauthentication)
-- ============================================================================
CREATE TABLE auth.one_time_tokens
(
    id            UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    token_type    VARCHAR(255) NOT NULL,   -- 'magiclink' | 'otp' | 'phone_otp' | 'reauthentication'
    token_hash    VARCHAR(255) NOT NULL,   -- SHA-256 hash of the token / OTP code
    relates_to    VARCHAR(255),            -- email or phone the token was issued for
    -- PKCE: when a passwordless flow is initiated with a code challenge, verifying the
    -- token yields an auth_code (in auth.flow_state) instead of a session directly.
    code_challenge        VARCHAR(255),
    code_challenge_method VARCHAR(10),
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX one_time_tokens_user_id_idx ON auth.one_time_tokens(user_id);
CREATE INDEX one_time_tokens_token_hash_idx ON auth.one_time_tokens(token_hash);
CREATE INDEX one_time_tokens_relates_to_idx ON auth.one_time_tokens(relates_to);
COMMENT ON TABLE auth.one_time_tokens IS 'Single-use tokens for passwordless / reauthentication flows';
ALTER TABLE auth.one_time_tokens ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- Audit log (Supabase GoTrue parity)
-- ============================================================================
CREATE TABLE auth.audit_log_entries
(
    id          UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    instance_id UUID,
    payload     JSONB,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    ip_address  VARCHAR(64)              DEFAULT ''
);

CREATE INDEX audit_log_entries_created_at_idx ON auth.audit_log_entries(created_at);
COMMENT ON TABLE auth.audit_log_entries IS 'Auth audit trail (logins, signups, MFA, recovery, etc.)';
ALTER TABLE auth.audit_log_entries ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- PKCE flow state (grant_type=pkce) — also used by OAuth and SAML SSO
-- ============================================================================
CREATE TABLE auth.flow_state
(
    id                     UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    user_id                UUID REFERENCES auth.users (id) ON DELETE CASCADE,
    auth_code              VARCHAR(255) NOT NULL,
    code_challenge_method  VARCHAR(10)  NOT NULL,   -- 's256' | 'plain'
    code_challenge         VARCHAR(255) NOT NULL,
    provider_type          VARCHAR(255),            -- 'oauth' | 'magiclink' | 'saml' | ...
    authentication_method  VARCHAR(255) NOT NULL,
    auth_code_issued_at    TIMESTAMP WITH TIME ZONE,
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at             TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT flow_state_auth_code_unique UNIQUE (auth_code)
);

CREATE INDEX flow_state_created_at_idx ON auth.flow_state(created_at);
CREATE INDEX flow_state_user_id_idx ON auth.flow_state(user_id) WHERE user_id IS NOT NULL;
COMMENT ON TABLE auth.flow_state IS 'PKCE / SSO flow state — exchanges an auth_code for a session';
ALTER TABLE auth.flow_state ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- SAML 2.0 Single Sign-On (Supabase GoTrue parity)
-- ============================================================================

-- An SSO provider groups one IdP with the email domains it authenticates.
CREATE TABLE auth.sso_providers
(
    id          UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    resource_id VARCHAR(255),            -- caller-supplied external id
    enabled     BOOLEAN                  DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
COMMENT ON TABLE auth.sso_providers IS 'SSO (SAML) identity providers';
ALTER TABLE auth.sso_providers ENABLE ROW LEVEL SECURITY;

-- Email domains routed to an SSO provider.
CREATE TABLE auth.sso_domains
(
    id              UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    sso_provider_id UUID NOT NULL REFERENCES auth.sso_providers (id) ON DELETE CASCADE,
    domain          VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE UNIQUE INDEX sso_domains_domain_idx ON auth.sso_domains(LOWER(domain));
CREATE INDEX sso_domains_provider_id_idx ON auth.sso_domains(sso_provider_id);
COMMENT ON TABLE auth.sso_domains IS 'Email domains mapped to an SSO provider';
ALTER TABLE auth.sso_domains ENABLE ROW LEVEL SECURITY;

-- SAML-specific configuration for an SSO provider (the IdP metadata).
CREATE TABLE auth.saml_providers
(
    id                UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    sso_provider_id   UUID NOT NULL REFERENCES auth.sso_providers (id) ON DELETE CASCADE,
    entity_id         VARCHAR(255) NOT NULL,   -- IdP entityID
    metadata_xml      TEXT,                    -- raw IdP metadata XML
    metadata_url      VARCHAR(2048),
    sso_url           VARCHAR(2048),           -- IdP SSO (redirect) endpoint
    x509_certificate  TEXT,                    -- IdP signing certificate (PEM/base64 DER)
    attribute_mapping JSONB,                   -- maps assertion attrs -> user fields
    name_id_format    VARCHAR(255),
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT saml_providers_entity_id_unique UNIQUE (entity_id)
);
CREATE INDEX saml_providers_sso_provider_id_idx ON auth.saml_providers(sso_provider_id);
COMMENT ON TABLE auth.saml_providers IS 'SAML IdP configuration per SSO provider';
ALTER TABLE auth.saml_providers ENABLE ROW LEVEL SECURITY;

-- Outstanding SAML AuthnRequests (relay state) awaiting an IdP response.
CREATE TABLE auth.saml_relay_states
(
    id              UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    sso_provider_id UUID NOT NULL REFERENCES auth.sso_providers (id) ON DELETE CASCADE,
    request_id      VARCHAR(255) NOT NULL,   -- SAML AuthnRequest ID
    for_email       VARCHAR(255),
    redirect_to     VARCHAR(2048),
    flow_state_id   UUID REFERENCES auth.flow_state (id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX saml_relay_states_request_id_idx ON auth.saml_relay_states(request_id);
CREATE INDEX saml_relay_states_provider_id_idx ON auth.saml_relay_states(sso_provider_id);
COMMENT ON TABLE auth.saml_relay_states IS 'In-flight SAML AuthnRequests';
ALTER TABLE auth.saml_relay_states ENABLE ROW LEVEL SECURITY;
