-- Assets (static asset CDN) Schema Initialization
-- Creates per-project static asset tables in the 'assets' schema.
-- Files live in the global R2 bucket under the reserved {appCode}/__assets__/ prefix;
-- this schema only holds metadata and the per-project delivery settings.

-- Create assets.files table
CREATE TABLE IF NOT EXISTS assets.files
(
    id            UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    path          TEXT                NOT NULL,

    -- File metadata
    content_type  VARCHAR(255),
    size_bytes    BIGINT              NOT NULL DEFAULT 0,
    etag          VARCHAR(255),
    cache_control VARCHAR(255),
    metadata      JSONB                    DEFAULT CAST('{}' AS JSONB),

    -- Timestamps
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT files_path_unique UNIQUE (path)
);

-- Create trigger function to automatically update updated_at
CREATE OR REPLACE FUNCTION assets.update_updated_at_column()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS update_files_updated_at ON assets.files;
CREATE TRIGGER update_files_updated_at
    BEFORE UPDATE
    ON assets.files
    FOR EACH ROW
EXECUTE FUNCTION assets.update_updated_at_column();

COMMENT ON TABLE assets.files IS 'Static assets served publicly via /assets/v1/**';
COMMENT ON COLUMN assets.files.path IS 'Asset path within the project (e.g. img/logo.png)';
COMMENT ON COLUMN assets.files.content_type IS 'MIME type sent as Content-Type when serving';
COMMENT ON COLUMN assets.files.size_bytes IS 'File size in bytes';
COMMENT ON COLUMN assets.files.etag IS 'Object storage ETag, used for conditional GET (304)';
COMMENT ON COLUMN assets.files.cache_control IS 'Per-file Cache-Control override (null = project default)';

-- Enable Row Level Security
ALTER TABLE assets.files ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS files_path_idx ON assets.files (path text_pattern_ops);

-- Create assets.settings table (single-row, per-project delivery settings)
CREATE TABLE IF NOT EXISTS assets.settings
(
    id                    SMALLINT PRIMARY KEY     DEFAULT 1 CHECK (id = 1),
    -- Cache-Control applied when a file has no per-file override
    default_cache_control VARCHAR(255)        NOT NULL DEFAULT 'public, max-age=3600',
    -- Optional external CDN/custom domain prefix for public URLs (e.g. https://cdn.myapp.io);
    -- when set, public URLs are built as {custom_base_url}/{path} — the owner's CDN/domain
    -- mapping decides what sits behind the prefix
    custom_base_url       TEXT,
    -- Optional per-project max asset size override (null = platform default)
    max_file_size_bytes   BIGINT,
    -- Optional SPA fallback path served when a requested asset path is missing
    spa_fallback_path     TEXT,
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

ALTER TABLE assets.settings
    ADD COLUMN IF NOT EXISTS spa_fallback_path TEXT;

DROP TRIGGER IF EXISTS update_settings_updated_at ON assets.settings;
CREATE TRIGGER update_settings_updated_at
    BEFORE UPDATE
    ON assets.settings
    FOR EACH ROW
EXECUTE FUNCTION assets.update_updated_at_column();

COMMENT ON TABLE assets.settings IS 'Per-project static asset delivery settings (single row)';

-- Enable Row Level Security
ALTER TABLE assets.settings ENABLE ROW LEVEL SECURITY;

-- Seed the singleton settings row
INSERT INTO assets.settings (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;
