# HomeClaim database support

This page records the database support contract and the runtime checks for the Paper plugin. The pre-release Flyway baseline is still development schema, not a published upgrade history. The backup feature adds a small `V3__plot_backups.sql` metadata table while retaining existing V1/V2 databases; this is additive feature schema, not a claim that MySQL is supported. Avoid artificial version history for internal unreleased edits.

## Support policy

| Database | Status | Intended use |
| --- | --- | --- |
| PostgreSQL | Recommended production database and canonical development/reference database | New server deployments and first verification target for new database features |
| MariaDB | Supported production alternative | Minecraft hosting and installations that already operate MariaDB |
| SQLite | Supported zero-configuration database | Development, tests, and small single-instance servers |
| MySQL | Experimental and not officially supported | The connector and configuration option exist, but HomeClaim has no dedicated, end-to-end verified MySQL schema/runtime path |

Domain behavior should be consistent across supported databases. Physical SQL may differ. PostgreSQL is not limited to the smallest common SQL subset: use a PostgreSQL feature when it supports a real HomeClaim query or invariant and retain a fitting implementation for MariaDB or SQLite.

MySQL must not be described as supported based on Connector/J being packaged, configuration parsing, or a Flyway module being present. That status requires an isolated MySQL verification of fresh schema initialization, restart, domain CRUD, audit/correlation, claim/merge, and relevant REST mutations. MariaDB SQL is not presumed to work unchanged on MySQL.

## Current schema and dialect implementation

The common pre-release schema starts at `homeclaim-platform-paper/src/main/resources/db/migration/common/V1__init.sql`, followed by the plot-home, backup-index, and mutation-job-details migrations (`V2`–`V4`); dialect-specific repeatables are applied where configured. Backup metadata uses UUID IDs and scope, while payload bytes live under the plugin data directory. Domain UUID columns use `UUID`, including World/Area identity and references, region/role/component/zone identifiers, and structured audit UUID fields. Opaque resource references and external actor references remain strings because they are not necessarily HomeClaim UUIDs. This keeps a stable domain identity distinct from world names and arbitrary external resource keys.

JSON-shaped columns currently use the common `JSON` type. The PostgreSQL path uses a typed JDBC JSON parameter; MariaDB and SQLite bind the serialized text through their JSON-compatible column behavior. No JSONB/GIN index is currently installed: the runtime repositories fetch JSON by relational keys and decode it into snapshots rather than using JSON containment filters. Do not add a JSON index until a concrete query uses it.

MariaDB's repeatable script (`R__mariadb_extras.sql`) adds selected generated columns from JSON flag/limit values and indexes them. Those columns are an optimization for relationally keyed values and do not change HomeClaim policy semantics. The runtime verification below uses MariaDB 11.4.13, which is the currently verified release for this checkout; check the exact database version and repeatable requirements before promising a wider version range.

SQLite uses the common baseline and the same repository/domain behavior needed for the single-server use case. It is not a reason to avoid useful PostgreSQL or MariaDB capabilities.

## Persistence and runtime reads

The configured supported relational database is canonical persistence. Protection reads are served from JDBC repository runtime snapshots and spatial indexes after startup loading:

- HomeClaim worlds and Areas, with per-world Area resolvers;
- Regions grouped by Area and chunk candidates, including loaded roles, flags, and limits;
- Zones indexed by Area and chunk/priority;
- Components indexed by region and world/chunk/position;
- Entry-deny rules grouped by region;
- named flag profiles in an immutable runtime snapshot.

Ordinary protection evaluation must not perform SQL reads for those records. PostgreSQL's shared buffers or the OS page cache cannot replace HomeClaim's in-memory hot-path state. Do not add arbitrary TTLs to roles, policies, Areas, denies, or limits. A successful domain write should update or invalidate the relevant snapshot; a direct SQL edit outside HomeClaim requires an explicit reload or server restart. Redis is not part of a single-server cache design.

The Area/Region lookup has an Area/chunk candidate index and still applies exact spatial checks. Use and measure this path before introducing a second spatial index.

## Runtime verification

`DatabaseDialectRuntimeTest` exercises fresh Flyway initialization, backup-index create/ready/list/read, world/Area resolution, profile persistence, region CRUD and spatial lookup, roles, flags, limits, zone defaults, components, entry-deny, claim and merge, audit correlation, a mutating REST trust request, and reconstruction of runtime repository snapshots from persisted records. The PostgreSQL and MariaDB cases are opt-in and require isolated disposable databases. The SQLite case creates and removes a temporary local database.

To run the three dialect cases against throwaway databases, provide these variables to the Paper test task:

```text
HOMECLAIM_TEST_POSTGRES_JDBC_URL
HOMECLAIM_TEST_POSTGRES_USERNAME
HOMECLAIM_TEST_POSTGRES_PASSWORD
HOMECLAIM_TEST_MARIADB_JDBC_URL
HOMECLAIM_TEST_MARIADB_USERNAME
HOMECLAIM_TEST_MARIADB_PASSWORD
```

The external databases must be disposable because Flyway and the test write schema and domain records. Never point these variables at production.

The current local verification for this support-policy change ran the complete runtime exercise on PostgreSQL 17.11, MariaDB 11.4.13, and a fresh SQLite database. It verified successful REST mutation and persisted audit on each, plus persisted-state reload after repository snapshots were recreated. The runtime suite also covers REST `200`, `400`, `401`, `403`, `404`, and a simulated backend `500` response. It did not run MySQL or Paper/Purpur server smoke tests. The current REST routes do not expose claim/merge operations and do not map structured conflict or limit results to `409`/a limit status, so those response cases are not yet available to verify through REST. Record future database version changes and any failures here when runtime support changes.

Step 11's verification ran the backup-index repository and fresh V3 migration on SQLite through `DatabaseDialectRuntimeTest`, then upgraded existing local Paper and Purpur SQLite databases from Flyway V2 to V3 using the distributable plugin JAR. PostgreSQL and MariaDB opt-in URLs were not configured for this step, so the V3 migration has not been reverified on those dialects here. The earlier dialect run above predates the backup table; it is not evidence for the V3 migration on PostgreSQL or MariaDB.

Step 14A adds no database migration. The existing backup index already records codec, stored payload bytes, format version, and SHA-256. HCBK v2 keeps its raw/uncompressed payload length in the file manifest; old HCBK v1/NONE rows and payloads remain readable. Restore-safety BackupIds are stored in the existing mutation-job `details` field from V4. SQLite tests verify this existing persistence shape; PostgreSQL and MariaDB are reported only when the isolated dialect URLs are actually available.

## Step 16B reproducible runtime matrix

Run `./scripts/database-integration-test.sh` with a working local Docker or Podman runtime and Java 25. It starts disposable, random-credential containers bound only to loopback, using pinned images `docker.io/library/postgres:17.11` and `docker.io/library/mariadb:11.4.13`, runs `:homeclaim-platform-paper:databaseIntegrationTest`, and removes the containers on exit. The script checks that the container engine responds before creating services; it does not assume that Podman is usable merely because its executable exists. It needs `openssl` for throwaway passwords.

The dedicated Gradle task requires both external JDBC URLs and fails clearly if either is absent. Ordinary `test` still runs SQLite and keeps PostgreSQL/MariaDB as conditional skips. CI systems with service containers can invoke the same task by setting the six `HOMECLAIM_TEST_POSTGRES_*` and `HOMECLAIM_TEST_MARIADB_*` variables above. No CI configuration existed in this checkout, so no provider-specific workflow was added. The external URL path remains available for isolated databases supplied by a CI provider or a developer; do not use production credentials or URLs.

The 2026-10-20 runtime run passed all three dialect cases against fresh schemas: PostgreSQL 17.11, MariaDB 11.4.13, and temporary SQLite. The exercise covered V1–V4 and the PostgreSQL/MariaDB repeatables; world/Area UUID bindings and resolution; region, role, flag, limit, zone, component, entry-deny, claim, and merge persistence; backup metadata for NONE and ZSTD; interrupted mutation-job classification and recovery resolution; audit correlation; REST trust success plus malformed/unauthorized/forbidden/not-found/backend-failure responses; and runtime snapshot reconstruction. MariaDB assertions also verified the generated BUILD column and index. It closed the original Hikari pool, opened a new pool, reran Flyway, and read back backup, job, audit, region, world/Area, component, and profile state. This verifies persistence across application pool/service reconstruction, not a database-process restart. MySQL was not run and remains experimental.

### Real database process restart

`./scripts/database-process-restart-test.sh` is a separate, opt-in integration run for persistence across an actual PostgreSQL and MariaDB process stop/start. It pins PostgreSQL 17.11 and MariaDB 11.4.13, creates loopback-only containers with named data volumes, writes deterministic per-run world/Area/region, HCBK v2/ZSTD metadata, `RECOVERY_REQUIRED` job and correlated audit records, closes the test JVM/Hikari pools, stops both existing database containers, then starts those same containers with the same volumes. A second Gradle process verifies the saved records and runtime lookup after Flyway validation. Containers and their test-only volumes are removed on exit.

The script requires a working Docker or Podman engine, Java 25, `openssl`, and the pinned images. Alternatively, when providing isolated databases yourself, run `databaseProcessRestartPrepare`, stop and restart those same DB processes without deleting their data directories, then run `databaseProcessRestartVerify`; both phases require the six external dialect variables above plus `HOMECLAIM_PROCESS_RESTART_RUN_ID` with the same value in both invocations. The two Gradle tasks fail if any required database URL is missing. This test does not contact or modify production databases.

The Step 17B run passed both phases on PostgreSQL 17.11 and MariaDB 11.4.13. Each dialect's prepare and verify cases passed (4 test executions total) after stopping and starting the same containers with their named volumes preserved. The post-restart phase reran Flyway, rebuilt repositories, and verified the world/Area/region spatial binding, READY HCBK v2 ZSTD metadata/checksum, RECOVERY_REQUIRED job details and region reference, and correlated audit row/actor/status/Area. The script removed the containers and volumes afterward. SQLite continues to be exercised by the ordinary runtime suite; this process-restart script is specifically for the two production server dialects.

### Live database outage and pool reconnect

`./scripts/database-interruption-test.sh` exercises an abrupt database outage while a test Hikari pool built with HomeClaim's `SqlDsl.hikari` remains alive. It starts pinned PostgreSQL 17.11 and MariaDB 11.4.13 containers with named volumes, makes a baseline write, sends `SIGKILL` to each existing database process, asserts a write fails, starts the same container again, and verifies that the same pool reconnects, the failed write did not appear, and a post-reconnect write succeeds. The test is isolated and never uses production DB settings. It tests the pool factory and JDBC behavior in a test JVM; it does not claim to run an entire Paper plugin during the outage.

Run with Java 25 and a working Docker or Podman engine:

```sh
./scripts/database-interruption-test.sh
```

The Gradle task `:homeclaim-platform-paper:databaseInterruptionTest` is strict and requires the isolated database JDBC settings, container engine, and container names. SQLite remains covered by the normal runtime suite; this abrupt-process test targets PostgreSQL and MariaDB.
