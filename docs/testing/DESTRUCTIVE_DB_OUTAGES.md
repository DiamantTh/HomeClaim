# Destructive database outage verification

Run with Java 25 and an operational Docker or Podman runtime:

```sh
./gradlew :homeclaim-platform-paper:destructiveDbOutageE2E --no-daemon
./gradlew :homeclaim-platform-paper:destructiveDbRestartE2E --no-daemon
```

Both tasks run the production Shadow JAR and a separate test-only companion in
an isolated loopback Paper server. PostgreSQL `17.11` and MariaDB `11.4.13` are
pinned. Temporary database volumes survive each kill/start and are removed by
the runner afterward. Credentials are generated per run; personal or production
databases are not used. Normal `test` does not require containers.

## Matrix and assertions

| Scenario | Committed intent before writes | Deterministic failure point | Required safety | Explicit recovery |
| --- | --- | --- | --- | --- |
| RESET | RESETTING | 100 backend block writes | READY original backup | restore-target |
| MOVE | CLEANING_SOURCE | 100 source cleanup writes; target already applied/verified | Both READY originals | restore-both |
| SWAP | APPLYING_SECONDARY | 100 writes on the second side | Both READY originals | restore-both |
| RESTORE | RESTORING | 100 restore block writes | READY requested backup and pre-restore safety | restore-target using pre-restore safety |
| MERGE_PARTIAL | WORLD_MUTATION | First planned world batch applied | READY world/domain bundle | restore-operation-original |
| MERGE_DOMAIN | WORLD_MUTATION | All planned world batches applied, before domain persistence starts | READY world/domain bundle | restore-operation-original |
| UNLINK_PARTIAL | WORLD_MUTATION | First planned world batch applied | READY world/domain bundle | restore-operation-original |
| UNLINK_DOMAIN | WORLD_MUTATION | All planned world batches applied, before domain persistence starts | READY world/domain bundle | restore-operation-original |
| BIOME | APPLYING_BIOME | Four cells applied | Checksummed BiomeSnapshotV1 | restore-biome-original |

Every scenario runs on both external dialects. A separate JDBC connection checks
the committed intent before the relevant destructive section begins; the later
kill barrier checks the same job ID and phase. Relational backup references are
checked as READY. The biome file is decoded and its job-bound checksum checked
before writes. A completion proof must be absent. Container kill is triggered
by the companion at the barrier, not by a timing-based shell sleep.

After the failure the same database process/volume returns while Paper stays
alive. Hikari reconnect is checked with a repository connection. The production background task retries only the already observed failure
journal write after DB return. The test waits for the durable RECOVERY_REQUIRED
row; it does not call the startup classifier in the running process. Locally
observed failure blockers survive diagnostic reads while the DB row still
lags. Retries are asynchronous and guarded against overlapping attempts. This
is failure-status persistence, not mutation resumption.
The unsafe scope must remain blocked, then explicit recovery must restore the
original canonical state and remove the blocker. A subsequent ordinary COPY
checks that the runtime can execute new work.

World checks include BlockData and serialized container inventory contents.
Merge/unlink checks cover the complete planned corridor/perimeter scope plus
MergeGroup IDs and homes. The domain-boundary cases stop before the first
domain write; they do not claim interruption inside a partially committed SQL
transaction. Biome checks compare registry keys at negative X/Z quart
coordinates across four Y layers.

## Hard Paper crashes and restart recovery

The restart runner adds:

- PostgreSQL: partial second-side SWAP plus database kill and hard Paper halt.
- MariaDB: partial BIOME plus database kill and hard Paper halt.
- SQLite: partial RESET and partial BIOME, each with hard Paper halt.

Before the hard halt the test flushes the partial world to disk and records the
job and fixture references. The next Paper process uses the same world and
persistent database. Startup must classify the durable intent as
RECOVERY_REQUIRED, rebuild the blocker, and permit explicit exact recovery.
SQLite files are not renamed, deleted or damaged to emulate a separate DB
process. The deliberate halt returns code 23; the recovery process must exit 0.

## Results and limitations

Reports live below `homeclaim-platform-paper/build/reports/` in
`destructive-db-matrix` and `destructive-db-restart`. JSON reports bind PASS to
the production JAR SHA-256. Missing/unparseable reports, wrong hashes, missing
scenarios, nonzero recovery-process exits and timeouts fail the tasks. Detailed
server logs are retained on failure.

Only observation seams with inert defaults exist in production. DB process
control, hard halts and failure implementations are in the companion source
set or test scripts. There is no production activation flag, route or command.
RESET terminal journal/fallback writes run asynchronously during normal Paper
operation; the region reservation remains held until outcome delivery.

An unavailable DB cannot promise audit persistence. The durable pre-outage
record and the post-return recovery record are distinct evidence. This suite
uses service-level System actors, not network players, for its outage cases;
the separate ProtoBot suite verifies the existing player command/audit paths.
It does not establish player-visible BIOME outage feedback. Snapshot semantics
remain TICKWISE; no general auto-resume or automatic rollback is introduced.
