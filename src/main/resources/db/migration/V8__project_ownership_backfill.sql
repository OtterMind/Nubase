-- Make project ownership complete so the admin listing can go through
-- platform_user_projects uniformly (one paginated query, super-admin via a flag).
--
-- Projects created via the metadata service-role key (root/automation) carry no
-- human user id, so historically they got no platform_user_projects row and were
-- "orphaned". We introduce a fixed SYSTEM platform user that owns such projects,
-- and backfill it for every existing project that currently has no ownership row.

-- 0. Mapping table: a third-party (platform, user id) → a dedicated Nubase platform user.
--    When a project is created via the root key on behalf of an external user, we resolve (or
--    lazily create) a non-super-admin Nubase user here and make THAT user the project owner, so
--    different external users' projects are cleanly segregated. The Nubase ownership in
--    platform_user_projects is always a real platform_users row; the external identity lives here.
CREATE TABLE IF NOT EXISTS platform_external_identities (
    id                BIGSERIAL    PRIMARY KEY,
    external_platform VARCHAR(64)  NOT NULL,
    external_user_id  VARCHAR(255) NOT NULL,
    platform_user_id  UUID         NOT NULL REFERENCES platform_users (id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_platform_external_identity UNIQUE (external_platform, external_user_id)
);
CREATE INDEX IF NOT EXISTS idx_pei_platform_user ON platform_external_identities (platform_user_id);

-- 1. Reserved system platform user. The encrypted_password is an invalid bcrypt
--    marker that can never match, and is_active = FALSE, so it can never log in.
--    Its only purpose is to be the owner of root-created / pre-ownership projects.
INSERT INTO platform_users (id, email, encrypted_password, full_name, role, is_active)
VALUES ('00000000-0000-0000-0000-000000000000',
        'system@nubase.internal',
        '!disabled-system-account',
        'System',
        'system',
        FALSE)
ON CONFLICT (id) DO NOTHING;

-- 2. Backfill ownership for existing projects with no membership row at all.
INSERT INTO platform_user_projects (user_id, db_key, role)
SELECT '00000000-0000-0000-0000-000000000000', c.db_key, 'owner'
FROM database_configs c
WHERE NOT EXISTS (
    SELECT 1 FROM platform_user_projects pup WHERE pup.db_key = c.db_key
)
ON CONFLICT (user_id, db_key) DO NOTHING;
