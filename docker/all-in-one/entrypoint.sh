#!/usr/bin/env bash
set -Eeuo pipefail

DATA_DIR="${NUBASE_DATA_DIR:-/data}"
PGDATA="${PGDATA:-${DATA_DIR}/postgres}"
REDIS_DATA_DIR="${REDIS_DATA_DIR:-${DATA_DIR}/redis}"
SECRETS_DIR="${SECRETS_DIR:-${DATA_DIR}/secrets}"
POSTGRES_DB="${POSTGRES_DB:-postgrest_metadata}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_EXTERNAL_ACCESS="${NUBASE_POSTGRES_EXTERNAL_ACCESS:-false}"
POSTGRES_PASSWORD_FROM_ENV="${POSTGRES_PASSWORD:-}"
POSTGRES_PASSWORD_FILE_FROM_ENV="${POSTGRES_PASSWORD_FILE:-}"
POSTGRES_PASSWORD=""

mkdir -p "$PGDATA" "$REDIS_DATA_DIR" "$SECRETS_DIR"
chown -R postgres:postgres "$PGDATA"
chmod 700 "$PGDATA"
chmod 700 "$SECRETS_DIR"

log() {
  printf '[nubase] %s\n' "$*"
}

fail() {
  log "ERROR: $*"
  exit 1
}

ensure_secret_file() {
  local file="$1"
  local bytes="$2"

  if [ ! -s "$file" ]; then
    umask 077
    openssl rand -base64 "$bytes" > "$file"
  fi
  chmod 600 "$file"
}

validate_postgres_settings() {
  case "${POSTGRES_EXTERNAL_ACCESS,,}" in
    true)
      POSTGRES_EXTERNAL_ACCESS="true"
      ;;
    false)
      POSTGRES_EXTERNAL_ACCESS="false"
      ;;
    *)
      fail "NUBASE_POSTGRES_EXTERNAL_ACCESS must be either true or false"
      ;;
  esac

  if [[ ! "$POSTGRES_PORT" =~ ^[0-9]+$ ]] || (( POSTGRES_PORT < 1 || POSTGRES_PORT > 65535 )); then
    fail "POSTGRES_PORT must be an integer between 1 and 65535"
  fi
}

ensure_postgres_password() {
  local default_password_file="${SECRETS_DIR}/postgres_password"
  local explicit_password="false"

  if [ -n "$POSTGRES_PASSWORD_FROM_ENV" ] && [ -n "$POSTGRES_PASSWORD_FILE_FROM_ENV" ]; then
    fail "Set only one of POSTGRES_PASSWORD or POSTGRES_PASSWORD_FILE"
  fi

  if [ -n "$POSTGRES_PASSWORD_FILE_FROM_ENV" ]; then
    [ -s "$POSTGRES_PASSWORD_FILE_FROM_ENV" ] || fail "POSTGRES_PASSWORD_FILE must reference a non-empty file"
    POSTGRES_PASSWORD="$(tr -d '\r\n' < "$POSTGRES_PASSWORD_FILE_FROM_ENV")"
    explicit_password="true"
  elif [ -n "$POSTGRES_PASSWORD_FROM_ENV" ]; then
    POSTGRES_PASSWORD="$POSTGRES_PASSWORD_FROM_ENV"
    explicit_password="true"
  elif [ -s "$default_password_file" ]; then
    POSTGRES_PASSWORD="$(tr -d '\r\n' < "$default_password_file")"
  elif [ -s "$PGDATA/PG_VERSION" ]; then
    fail "Existing PGDATA has no persisted password; provide a rotated password through POSTGRES_PASSWORD_FILE"
  else
    ensure_secret_file "$default_password_file" 32
    POSTGRES_PASSWORD="$(tr -d '\r\n' < "$default_password_file")"
  fi

  [ -n "$POSTGRES_PASSWORD" ] || fail "Postgres password must not be empty"
  if [ "$POSTGRES_PASSWORD" = "postgres" ]; then
    fail "The known default Postgres password is not allowed"
  fi
  if [ "$POSTGRES_EXTERNAL_ACCESS" = "true" ]; then
    [ "$explicit_password" = "true" ] || fail "External Postgres access requires POSTGRES_PASSWORD or POSTGRES_PASSWORD_FILE"
    [ "${#POSTGRES_PASSWORD}" -ge 16 ] || fail "External Postgres access requires a password of at least 16 characters"
  fi

  export POSTGRES_PASSWORD
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

configure_postgres_access() {
  local listen_addresses="127.0.0.1"
  local managed_config="${PGDATA}/nubase-postgresql.conf"
  local hba_file="${PGDATA}/pg_hba.conf"
  local hba_tmp

  if [ "$POSTGRES_EXTERNAL_ACCESS" = "true" ]; then
    listen_addresses="*"
  fi

  {
    printf '# Managed by the Nubase all-in-one entrypoint.\n'
    printf "listen_addresses = '%s'\n" "$listen_addresses"
    printf 'port = %s\n' "$POSTGRES_PORT"
  } > "$managed_config"
  chown postgres:postgres "$managed_config"
  chmod 600 "$managed_config"

  sed -i "/^[[:space:]]*include_if_exists[[:space:]]*=[[:space:]]*'nubase-postgresql.conf'[[:space:]]*$/d" "$PGDATA/postgresql.conf"
  printf "\ninclude_if_exists = 'nubase-postgresql.conf'\n" >> "$PGDATA/postgresql.conf"

  hba_tmp="$(mktemp "${PGDATA}/pg_hba.conf.nubase.XXXXXX")"
  {
    printf '# BEGIN NUBASE MANAGED ACCESS\n'
    printf 'host all all 127.0.0.1/32 scram-sha-256\n'
    printf 'host all all ::1/128 scram-sha-256\n'
    if [ "$POSTGRES_EXTERNAL_ACCESS" = "true" ]; then
      printf 'host all all 0.0.0.0/0 scram-sha-256\n'
      printf 'host all all ::/0 scram-sha-256\n'
    else
      # First-match reject rules keep legacy permissive entries fail-closed.
      printf 'host all all 0.0.0.0/0 reject\n'
      printf 'host all all ::/0 reject\n'
    fi
    printf '# END NUBASE MANAGED ACCESS\n'
    sed \
      -e '/^# BEGIN NUBASE MANAGED ACCESS$/,/^# END NUBASE MANAGED ACCESS$/d' \
      -e '/^[[:space:]]*host[[:space:]]\+all[[:space:]]\+all[[:space:]]\+all[[:space:]]\+scram-sha-256[[:space:]]*$/d' \
      "$hba_file"
  } > "$hba_tmp"
  chown postgres:postgres "$hba_tmp"
  chmod 600 "$hba_tmp"
  mv "$hba_tmp" "$hba_file"
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

    if ! su postgres -c "/usr/lib/postgresql/15/bin/initdb -D '$PGDATA' --username='$POSTGRES_USER' --pwfile='$pwfile' --auth-host=scram-sha-256 --auth-local=trust"; then
      rm -f "$pwfile"
      fail "Postgres initialization failed"
    fi
    rm -f "$pwfile"
  fi

  configure_postgres_access

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
  local -a java_opts=()
  if [ -z "${METADATA_DB_URL:-}" ] || [ "${METADATA_DB_URL:-}" = "$default_metadata_url" ]; then
    export METADATA_DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}?allowMultiQueries=true"
  fi

  export METADATA_DB_USER="${METADATA_DB_USER:-$POSTGRES_USER}"
  export METADATA_DB_PASSWORD="${METADATA_DB_PASSWORD:-$POSTGRES_PASSWORD}"
  export POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
  export REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
  export SERVER_PORT="${SERVER_PORT:-9999}"

  if [ -n "${JAVA_OPTS:-}" ]; then
    read -r -a java_opts <<< "$JAVA_OPTS"
  fi

  # The jar serves both the API and the bundled Studio UI (/studio) on this one port.
  log "Starting backend (API + Studio UI) on port ${SERVER_PORT}"
  runuser -u nubase -- java "${java_opts[@]}" -jar /opt/nubase/backend/app.jar &
  BACKEND_PID=$!
}

shutdown() {
  log "Stopping services"
  [ -n "${BACKEND_PID:-}" ] && kill "$BACKEND_PID" 2>/dev/null || true
  redis-cli -h 127.0.0.1 -p "${REDIS_PORT:-6379}" shutdown 2>/dev/null || true
  su postgres -c "/usr/lib/postgresql/15/bin/pg_ctl -D '$PGDATA' -m fast -w stop" 2>/dev/null || true
}

trap shutdown EXIT INT TERM

validate_postgres_settings
ensure_postgres_password
ensure_secrets
init_postgres
start_redis
start_backend

wait "$BACKEND_PID"
