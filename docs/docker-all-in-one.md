# Docker All-in-One Image

This repository can publish one Docker image that runs:

- Nubase backend **and the Studio UI** on port `9999` (the Studio is bundled into the jar and served at `/studio`, same-origin with the API)
- PostgreSQL 15 with `pgvector`, restricted to container loopback by default
- Redis internally for OAuth state support

The marketing website in `frontend/apps/www` is not included.

## Supported Systems

The GitHub Actions workflow publishes a multi-architecture Linux image for:

- `linux/amd64`
- `linux/arm64`

Users on Linux, macOS Docker Desktop, and Windows Docker Desktop can use the same Docker tag. Docker selects the matching architecture automatically.

This is a Linux container image, which is the normal Docker distribution format for this stack. macOS and Windows support comes from Docker Desktop running Linux containers:

- macOS Intel: pulls `linux/amd64`
- macOS Apple Silicon: pulls `linux/arm64`
- Windows on Intel/AMD with Docker Desktop Linux containers: pulls `linux/amd64`
- Windows on ARM with Docker Desktop Linux containers: pulls `linux/arm64`

Native Windows containers are not supported because PostgreSQL, pgvector, Java, and Next.js are packaged here as a Linux container runtime.

## Run

```bash
docker run -d \
  --name nubase \
  -p 9999:9999 \
  -v nubase_data:/data \
  docker.io/ottermind/nubase:latest
```

Open:

- Studio UI: http://localhost:9999/studio
- API: http://localhost:9999
- Postgres: internal only by default; its password is randomly generated and persisted under `/data/secrets`

### Accessing from a remote server

The Studio UI and the API are served by a **single process on one port (`9999`), same-origin**.
When the container runs on a remote host, just open `http://<server-ip>:9999/studio` (or your
domain). The UI calls the API with relative paths, so login and every request follow whatever
host you opened — there is no hardcoded `localhost` and no rebuild when the IP or domain
changes. Only port `9999` needs to be reachable.

### Optional PostgreSQL host access

Keep PostgreSQL internal unless a trusted local administration tool requires direct access. To opt
in, provide a password file with permissions `0600`, enable external access explicitly, and bind the
port to host loopback only:

```bash
docker run -d \
  --name nubase \
  -p 9999:9999 \
  -p 127.0.0.1:5432:5432 \
  -v nubase_data:/data \
  --mount type=bind,src=/absolute/path/to/postgres-password,dst=/run/secrets/postgres_password,readonly \
  -e NUBASE_POSTGRES_EXTERNAL_ACCESS=true \
  -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password \
  docker.io/ottermind/nubase:latest
```

The explicit password must be at least 16 characters and must not use a known default. Existing
volumes created by an older image fail closed when no persisted or explicit password is available;
rotate the database password before upgrading, then provide it through `POSTGRES_PASSWORD_FILE`.

### Studio signup & email verification

This image defaults `NUBASE_PLATFORM_EMAIL_VERIFICATION_ENABLED=false`, so a Studio account can be
created directly without an email code — the image ships without SMTP, so verification codes can't
be delivered. To require email verification (e.g. a hardened self-host), set
`-e NUBASE_PLATFORM_EMAIL_VERIFICATION_ENABLED=true` and configure the mail env vars (`SMTP_HOST`,
`SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM_ADDRESS`). A plain `java -jar` / production
deploy keeps verification **on** by default (`application.yml`), independent of this image.

The container creates persistent secrets under the `/data` volume when they are not provided through environment variables. Keep the same volume across restarts so encrypted project credentials remain readable.

For production-like installs, set stable secrets explicitly:

```bash
docker run -d \
  --name nubase \
  -p 9999:9999 \
  -v nubase_data:/data \
  -e PGRST_ENCRYPTION_MASTER_KEY="$(openssl rand -base64 32)" \
  -e METADATA_SERVICE_ROLE_KEY="$(openssl rand -base64 48)" \
  docker.io/ottermind/nubase:latest
```

## Publish From GitHub Actions

Create these repository secrets:

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN`

Push a tag to publish:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow builds `Dockerfile.all-in-one` and pushes `linux/amd64` and `linux/arm64` images to Docker Hub.
