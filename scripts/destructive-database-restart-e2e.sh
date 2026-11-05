#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAPER_JAR="${1:?Paper server JAR required}"
PLUGIN_JAR="${2:?HomeClaim production JAR required}"
COMPANION_JAR="${3:?test-only companion JAR required}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
ENGINE=""

if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_BIN" ]] && "$JAVA_BIN" -version 2>&1 | grep -Eq 'version "(25|[2-9][6-9])|openjdk 25'; then
    : # Honor an explicitly configured Java 25+ runtime.
elif [[ -x /usr/lib/jvm/java-25-amazon-corretto/bin/java ]]; then
    JAVA_BIN=/usr/lib/jvm/java-25-amazon-corretto/bin/java
elif command -v java25 >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java25)"
elif ! java -version 2>&1 | grep -Eq 'version "(25|[2-9][6-9])|openjdk 25'; then
    echo "A Java 25+ runtime is required for the Paper 26.3 database interruption E2E" >&2
    exit 2
fi

if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    ENGINE=podman
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ENGINE=docker
else
    echo "A working Docker or Podman runtime is required for paperDatabaseInterruptionE2E" >&2
    exit 2
fi

wait_for_container() {
    local name="$1" probe="$2" attempt
    for attempt in $(seq 1 90); do
        if "$ENGINE" exec "$name" sh -lc "$probe" >/dev/null 2>&1; then return 0; fi
        sleep 1
    done
    "$ENGINE" logs "$name" >&2 || true
    echo "Timed out waiting for $name" >&2
    return 1
}

wait_for_status() {
    local status_file="$1" expected="$2" log_file="$3" attempt
    for attempt in $(seq 1 360); do
        if [[ -f "$status_file" ]] && grep -q "^${expected}" "$status_file"; then return 0; fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            tail -200 "$log_file" >&2 || true
            echo "Paper server exited before status $expected" >&2
            return 1
        fi
        sleep 1
    done
    tail -200 "$log_file" >&2 || true
    echo "Timed out waiting for Paper test companion status $expected" >&2
    return 1
}

run_dialect() {
    local dialect="$1" image="$2" db_port_internal="$3" driver="$4" container_suffix
    local container volume server_dir report_dir password root_password host_port db_bind_port game_port
    local -a network_args=()
    container_suffix="$$-${RANDOM}"
    container="homeclaim-paper-db-${dialect,,}-${container_suffix}"
    volume="homeclaim-paper-db-${dialect,,}-data-${container_suffix}"
    server_dir="$ROOT_DIR/homeclaim-platform-paper/build/destructive-db-restart/$dialect/server"
    report_dir="$ROOT_DIR/homeclaim-platform-paper/build/reports/destructive-db-restart/$dialect"
    password="$(openssl rand -hex 24)"
    root_password="$(openssl rand -hex 24)"
    mkdir -p "$report_dir"
    rm -rf "$server_dir"
    mkdir -p "$server_dir/plugins" "$server_dir/config"

    if [[ "$ENGINE" == podman ]]; then
        db_bind_port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
        network_args=(--network=host)
    fi
    "$ENGINE" volume create "$volume" >/dev/null
    if [[ "$dialect" == POSTGRES ]]; then
        if [[ "$ENGINE" == podman ]]; then
            "$ENGINE" run --detach --name "$container" "${network_args[@]}" \
                --volume "$volume:/var/lib/postgresql/data" \
                --env POSTGRES_DB=homeclaim_test --env POSTGRES_USER=homeclaim_test \
                --env "POSTGRES_PASSWORD=$password" "$image" -c listen_addresses=127.0.0.1 -p "$db_bind_port" >/dev/null
        else
            "$ENGINE" run --detach --name "$container" \
                --volume "$volume:/var/lib/postgresql/data" \
                --env POSTGRES_DB=homeclaim_test --env POSTGRES_USER=homeclaim_test \
                --env "POSTGRES_PASSWORD=$password" --publish "127.0.0.1::$db_port_internal" "$image" >/dev/null
        fi
    else
        if [[ "$ENGINE" == podman ]]; then
            "$ENGINE" run --detach --name "$container" "${network_args[@]}" \
                --volume "$volume:/var/lib/mysql" \
                --env MARIADB_DATABASE=homeclaim_test --env MARIADB_USER=homeclaim_test \
                --env "MARIADB_PASSWORD=$password" --env "MARIADB_ROOT_PASSWORD=$root_password" \
                "$image" "--port=$db_bind_port" --bind-address=127.0.0.1 >/dev/null
        else
            "$ENGINE" run --detach --name "$container" \
                --volume "$volume:/var/lib/mysql" \
                --env MARIADB_DATABASE=homeclaim_test --env MARIADB_USER=homeclaim_test \
                --env "MARIADB_PASSWORD=$password" --env "MARIADB_ROOT_PASSWORD=$root_password" \
                --publish "127.0.0.1::$db_port_internal" "$image" >/dev/null
        fi
    fi

    if [[ "$ENGINE" == podman ]]; then
        host_port="$db_bind_port"
    else
        host_port="$("$ENGINE" port "$container" "$db_port_internal/tcp")"
        host_port="${host_port##*:}"
    fi
    if [[ "$dialect" == POSTGRES ]]; then
        wait_for_container "$container" "pg_isready -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -p $host_port"
    else
        wait_for_container "$container" "mariadb-admin ping -h 127.0.0.1 -uroot \"-p\$MARIADB_ROOT_PASSWORD\" -P $host_port --silent"
    fi

    cp "$PAPER_JAR" "$server_dir/$(basename "$PAPER_JAR")"
    cp "$PLUGIN_JAR" "$server_dir/plugins/HomeClaim.jar"
    cp "$COMPANION_JAR" "$server_dir/plugins/$(basename "$COMPANION_JAR")"
    printf 'eula=true\n' > "$server_dir/eula.txt"
    game_port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
    printf 'level-name=world\nserver-ip=127.0.0.1\nserver-port=%s\nonline-mode=false\nmax-players=1\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n' \
        "$game_port" > "$server_dir/server.properties"
    cat > "$server_dir/config/HomeClaim.toml" <<EOF
[homeclaim]
locale = "en"

[homeclaim.storage]
type = "JDBC"
driver = "$driver"
host = "127.0.0.1"
port = $host_port
database = "homeclaim_test"
username = "homeclaim_test"
password = "$password"
encoding = "UTF-8"
maximumPoolSize = 5

[homeclaim.migrations]
enabled = true
allowNonPostgres = true
baselineOnMigrate = true
locations = ["classpath:db/migration/common"]

[homeclaim.backup]
compression = "zstd"
EOF

    (
        cd "$server_dir"
        HOMECLAIM_TEST_DB_CONTAINER_ENGINE="$ENGINE" HOMECLAIM_TEST_DB_CONTAINER="$container" \
            timeout --signal=TERM --kill-after=30s 30m "$JAVA_BIN" -Dcom.mojang.eula.agree=true -Dhomeclaim.worlde2e.phase=outage-crash-prepare \
            -Dhomeclaim.worlde2e.crashOperation="$([[ "$dialect" == POSTGRES ]] && echo SWAP || echo BIOME)" \
            -Dhomeclaim.worlde2e.dbworldwrite.restartPaper="$([[ "$dialect" == POSTGRES ]] && echo true || echo false)" \
            -jar "$(basename "$PAPER_JAR")" nogui
    ) > "$report_dir/server.log" 2>&1 &
    SERVER_PID=$!
    MATRIX_CLEANED=false
    MATRIX_PID="$SERVER_PID"
    MATRIX_CONTAINER="$container"
    MATRIX_VOLUME="$volume"
    cleanup() {
        if [[ "${MATRIX_CLEANED:-false}" == true ]]; then return; fi
        MATRIX_CLEANED=true
        kill "$MATRIX_PID" >/dev/null 2>&1 || true
        wait "$MATRIX_PID" >/dev/null 2>&1 || true
        "$ENGINE" rm --force "$MATRIX_CONTAINER" >/dev/null 2>&1 || true
        "$ENGINE" volume rm "$MATRIX_VOLUME" >/dev/null 2>&1 || true
    }
    trap cleanup EXIT INT TERM

    local exit_code=0
    wait "$SERVER_PID" || exit_code=$?
    [[ $exit_code -eq 23 ]] || { tail -100 "$report_dir/server.log" >&2; return 1; }
    "$ENGINE" start "$container" >/dev/null
    if [[ "$dialect" == POSTGRES ]]; then
        wait_for_container "$container" "pg_isready -U homeclaim_test -d homeclaim_test -p $host_port"
    else
        wait_for_container "$container" "mariadb-admin ping -h 127.0.0.1 -uroot \"-p\$MARIADB_ROOT_PASSWORD\" -P $host_port --silent"
    fi
    ( cd "$server_dir"
      timeout --signal=TERM --kill-after=30s 10m "$JAVA_BIN" -Dcom.mojang.eula.agree=true -Dhomeclaim.worlde2e.phase=outage-crash-verify -jar "$(basename "$PAPER_JAR")" nogui
    ) > "$report_dir/recovery.log" 2>&1 &
    SERVER_PID=$!; MATRIX_PID="$SERVER_PID"
    local recovered_exit=0
    wait "$SERVER_PID" || recovered_exit=$?
    [[ $recovered_exit -eq 0 ]] || { tail -100 "$report_dir/recovery.log" >&2; return 1; }
    cp "$server_dir/plugins/HomeClaimWorldE2E/crash-result.json" "$report_dir/crash-result.json"
    python3 - "$report_dir/crash-result.json" "$PLUGIN_JAR" <<'CHECK'
import hashlib,json,sys
r=json.load(open(sys.argv[1]));assert r['overallStatus']=='PASSED',r
assert r['pluginSha256']==hashlib.sha256(open(sys.argv[2],'rb').read()).hexdigest()
print(r)
CHECK
    cleanup
    trap - EXIT INT TERM
    rm -rf "$server_dir"
}

run_dialect POSTGRES docker.io/library/postgres:17.11 5432 POSTGRES
run_dialect MARIADB docker.io/library/mariadb:11.4.13 3306 MARIADB

run_sqlite_crash() {
    local operation="$1"
    local server_dir="$ROOT_DIR/homeclaim-platform-paper/build/destructive-db-restart/SQLITE-$operation/server"
    local report_dir="$ROOT_DIR/homeclaim-platform-paper/build/reports/destructive-db-restart/SQLITE-$operation"
    rm -rf "$server_dir"; mkdir -p "$server_dir/plugins" "$report_dir"
    cp "$PAPER_JAR" "$server_dir/$(basename "$PAPER_JAR")"
    cp "$PLUGIN_JAR" "$server_dir/plugins/HomeClaim.jar"
    cp "$COMPANION_JAR" "$server_dir/plugins/$(basename "$COMPANION_JAR")"
    printf 'eula=true\n' > "$server_dir/eula.txt"
    local port
    port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
    printf 'server-ip=127.0.0.1\nserver-port=%s\nonline-mode=false\nview-distance=4\nsimulation-distance=4\n' "$port" > "$server_dir/server.properties"
    local phase exit_code
    for phase in prepare verify; do
        exit_code=0
        ( cd "$server_dir"
          timeout --signal=TERM --kill-after=30s 10m "$JAVA_BIN" -Dcom.mojang.eula.agree=true -Dhomeclaim.worlde2e.phase="outage-crash-$phase" -Dhomeclaim.worlde2e.crashOperation="$operation" -jar "$(basename "$PAPER_JAR")" nogui
        ) > "$report_dir/$phase.log" 2>&1 || exit_code=$?
        if [[ "$phase" == prepare ]]; then [[ $exit_code -eq 23 ]]; else [[ $exit_code -eq 0 ]]; fi
    done
    cp "$server_dir/plugins/HomeClaimWorldE2E/crash-result.json" "$report_dir/crash-result.json"
    python3 - "$report_dir/crash-result.json" "$PLUGIN_JAR" <<'CHECK'
import hashlib,json,sys
r=json.load(open(sys.argv[1]));assert r['overallStatus']=='PASSED',r
assert r['pluginSha256']==hashlib.sha256(open(sys.argv[2],'rb').read()).hexdigest()
print(r)
CHECK
    rm -rf "$server_dir"
}
run_sqlite_crash RESET
run_sqlite_crash BIOME
