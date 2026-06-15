#!/usr/bin/env bash
set -Eeuo pipefail

DATA_DIR="${NUBASE_DATA_DIR:-/data}"
PGDATA="${PGDATA:-${DATA_DIR}/postgres}"
REDIS_DATA_DIR="${REDIS_DATA_DIR:-${DATA_DIR}/redis}"
SECRETS_DIR="${SECRETS_DIR:-${DATA_DIR}/secrets}"
POSTGRES_DB="${POSTGRES_DB:-postgrest_metadata}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"

mkdir -p "$PGDATA" "$REDIS_DATA_DIR" "$SECRETS_DIR"
chown -R postgres:postgres "$PGDATA"
chmod 700 "$PGDATA"

log() {
  printf '[nubase] %s\n' "$*"
}

ensure_secret_file() {
  local file="$1"
  local bytes="$2"

  if [ ! -s "$file" ]; then
    umask 077
    openssl rand -base64 "$bytes" > "$file"
  fi
}

ensure_secrets() {
  if [ -z "${PGRST_ENCRYPTION_MASTER_KEY:-}" ]; then
    local key_file="${PGRST_ENCRYPTION_MASTER_KEY_FILE:-${SECRETS_DIR}/pgrst_encryption_master_key}"
    ensure_secret_file "$key_file" 32
    export PGRST_ENCRYPTION_MASTER_KEY_FILE="$key_file"
    export PGRST_ENCRYPTION_MASTER_KEY
    PGRST_ENCRYPTION_MASTER_KEY="$(tr -d '\n' < "$key_file")"
  fi

  if [ -z "${METADATA_SERVICE_ROLE_KEY:-}" ]; then
    local service_role_file="${SECRETS_DIR}/metadata_service_role_key"
    ensure_secret_file "$service_role_file" 48
    export METADATA_SERVICE_ROLE_KEY
    METADATA_SERVICE_ROLE_KEY="$(tr -d '\n' < "$service_role_file")"
  fi
}

run_psql() {
  su postgres -c "psql -v ON_ERROR_STOP=1 -p '$POSTGRES_PORT' -U '$POSTGRES_USER' -d '$1' -c \"$2\""
}

init_postgres() {
  if [ ! -s "$PGDATA/PG_VERSION" ]; then
    log "Initializing Postgres data directory"
    local pwfile
    pwfile="$(mktemp)"
    chmod 600 "$pwfile"
    printf '%s\n' "$POSTGRES_PASSWORD" > "$pwfile"
    chown postgres:postgres "$pwfile"

    su postgres -c "/usr/lib/postgresql/15/bin/initdb -D '$PGDATA' --username='$POSTGRES_USER' --pwfile='$pwfile' --auth-host=scram-sha-256 --auth-local=trust"
    rm -f "$pwfile"

    {
      printf "\nlisten_addresses = '*'\n"
      printf "port = %s\n" "$POSTGRES_PORT"
    } >> "$PGDATA/postgresql.conf"

    {
      printf "\nhost all all 127.0.0.1/32 scram-sha-256\n"
      printf "host all all ::1/128 scram-sha-256\n"
      printf "host all all all scram-sha-256\n"
    } >> "$PGDATA/pg_hba.conf"
  fi

  log "Starting Postgres"
  su postgres -c "/usr/lib/postgresql/15/bin/pg_ctl -D '$PGDATA' -w start"

  if ! su postgres -c "psql -p '$POSTGRES_PORT' -U '$POSTGRES_USER' -d postgres -tAc \"SELECT 1 FROM pg_database WHERE datname = '$POSTGRES_DB'\"" | grep -q 1; then
    log "Creating metadata database ${POSTGRES_DB}"
    su postgres -c "createdb -p '$POSTGRES_PORT' -U '$POSTGRES_USER' '$POSTGRES_DB'"
  fi

  log "Ensuring pgvector extension exists"
  run_psql "$POSTGRES_DB" "CREATE EXTENSION IF NOT EXISTS vector;"
}

start_redis() {
  log "Starting Redis"
  redis-server \
    --bind 127.0.0.1 \
    --port "${REDIS_PORT:-6379}" \
    --dir "$REDIS_DATA_DIR" \
    --daemonize yes
}

start_backend() {
  local default_metadata_url="jdbc:postgresql://127.0.0.1:5432/postgrest_metadata?allowMultiQueries=true"
  if [ -z "${METADATA_DB_URL:-}" ] || [ "${METADATA_DB_URL:-}" = "$default_metadata_url" ]; then
    export METADATA_DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}?allowMultiQueries=true"
  fi

  export METADATA_DB_USER="${METADATA_DB_USER:-$POSTGRES_USER}"
  export METADATA_DB_PASSWORD="${METADATA_DB_PASSWORD:-$POSTGRES_PASSWORD}"
  export POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
  export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export SERVER_PORT="${SERVER_PORT:-9999}"

  # The jar serves both the API and the bundled Studio UI (/studio) on this one port.
  log "Starting backend (API + Studio UI) on port ${SERVER_PORT}"
  java ${JAVA_OPTS:-} -jar /opt/nubase/backend/app.jar &
  BACKEND_PID=$!
}

shutdown() {
  log "Stopping services"
  [ -n "${BACKEND_PID:-}" ] && kill "$BACKEND_PID" 2>/dev/null || true
  redis-cli -h 127.0.0.1 -p "${REDIS_PORT:-6379}" shutdown 2>/dev/null || true
  su postgres -c "/usr/lib/postgresql/15/bin/pg_ctl -D '$PGDATA' -m fast -w stop" 2>/dev/null || true
}

trap shutdown EXIT INT TERM

ensure_secrets
init_postgres
start_redis
start_backend

wait "$BACKEND_PID"
