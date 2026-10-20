#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_IMAGE="docker.io/library/postgres:17.11"
MARIADB_IMAGE="docker.io/library/mariadb:11.4.13"
SUFFIX="$$-${RANDOM}"
PG_NAME="homeclaim-pg-it-${SUFFIX}"
MARIADB_NAME="homeclaim-mariadb-it-${SUFFIX}"

engine=""
if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    engine="podman"
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    engine="docker"
else
    cat >&2 <<'EOF'
No working Docker or Podman runtime was detected. The database integration task
requires isolated disposable PostgreSQL and MariaDB instances. Start the pinned
services yourself and run :homeclaim-platform-paper:databaseIntegrationTest with
the HOMECLAIM_TEST_POSTGRES_* and HOMECLAIM_TEST_MARIADB_* variables documented
in docs/db/README.md. Do not point them at production databases.
EOF
    exit 2
fi

cleanup() {
    "$engine" rm --force "$PG_NAME" "$MARIADB_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

pg_password="$(openssl rand -hex 24)"
mariadb_password="$(openssl rand -hex 24)"
mariadb_root_password="$(openssl rand -hex 24)"

"$engine" run --detach --name "$PG_NAME" \
    --env POSTGRES_DB=homeclaim_test \
    --env POSTGRES_USER=homeclaim_test \
    --env "POSTGRES_PASSWORD=${pg_password}" \
    --publish 127.0.0.1::5432 \
    "$PG_IMAGE" >/dev/null

"$engine" run --detach --name "$MARIADB_NAME" \
    --env MARIADB_DATABASE=homeclaim_test \
    --env MARIADB_USER=homeclaim_test \
    --env "MARIADB_PASSWORD=${mariadb_password}" \
    --env "MARIADB_ROOT_PASSWORD=${mariadb_root_password}" \
    --publish 127.0.0.1::3306 \
    "$MARIADB_IMAGE" >/dev/null

published_port() {
    local mapping
    mapping="$("$engine" port "$1" "$2")"
    printf '%s\n' "${mapping##*:}"
}

pg_port="$(published_port "$PG_NAME" 5432/tcp)"
mariadb_port="$(published_port "$MARIADB_NAME" 3306/tcp)"

wait_for_service() {
    local name="$1" command="$2" attempt
    for attempt in $(seq 1 90); do
        if "$engine" exec "$name" sh -lc "$command" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    "$engine" logs "$name" >&2 || true
    echo "Timed out waiting for isolated database container $name" >&2
    return 1
}

wait_for_service "$PG_NAME" 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
wait_for_service "$MARIADB_NAME" 'mariadb-admin ping -h 127.0.0.1 -uroot "-p$MARIADB_ROOT_PASSWORD" --silent'

cd "$ROOT_DIR"
export HOMECLAIM_TEST_POSTGRES_JDBC_URL="jdbc:postgresql://127.0.0.1:${pg_port}/homeclaim_test"
export HOMECLAIM_TEST_POSTGRES_USERNAME=homeclaim_test
export HOMECLAIM_TEST_POSTGRES_PASSWORD="$pg_password"
export HOMECLAIM_TEST_MARIADB_JDBC_URL="jdbc:mariadb://127.0.0.1:${mariadb_port}/homeclaim_test"
export HOMECLAIM_TEST_MARIADB_USERNAME=homeclaim_test
export HOMECLAIM_TEST_MARIADB_PASSWORD="$mariadb_password"

./gradlew :homeclaim-platform-paper:databaseIntegrationTest --no-daemon
