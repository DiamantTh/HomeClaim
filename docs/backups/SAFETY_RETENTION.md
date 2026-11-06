# Safety-artifact retention

HomeClaim distinguishes user-visible HCBK backups from operation-scoped safety
artifacts. The latter include reset/restore/transfer pre-images, merge/unlink
world-and-domain bundles, and `BiomeSnapshotV1` files.

## Current policy

All safety artifacts are retained indefinitely. There is deliberately no
automatic delete command, startup sweep, age/count policy, or dry-run which
labels a file as safely deletable. User-visible HCBK retention is unchanged.

This is a fail-closed policy, not an accidental omission. A snapshot can be
created before its owning mutation job has been persisted; later references are
stored across the backup index, free-form durable mutation-job details and
safety-bundle manifests. The current format has no single transaction or shared
lease that atomically covers reference creation, a reference scan, and a file
delete. A scan-then-delete implementation could therefore erase a valid
pre-image between snapshot creation and job-reference persistence.

In particular, an artifact referenced by `RECOVERY_REQUIRED` or
`RECOVERY_IN_PROGRESS` must never be deleted automatically, regardless of its
age. The same conservative rule applies while the database is unavailable,
reference data is malformed, or an in-flight capture exists.

## Requirements before cleanup can be introduced

Any future cleanup implementation must first establish all of the following:

1. a typed, complete, persistent artifact-reference index for backup IDs,
   bundle IDs and biome snapshot IDs;
2. an atomic/leased protocol spanning pre-image creation, job linkage,
   reference scanning and deletion;
3. canonical-root and symlink-safe path validation, with only known artifact
   types eligible;
4. an asynchronous bounded worker after runtime readiness, never a synchronous
   startup directory sweep;
5. a conservative policy that preserves active and recovery jobs, uses a grace
   period for failed pre-job artifacts, and produces an auditable dry-run;
6. SQLite, PostgreSQL and MariaDB race/failure tests, including a new reference
   appearing while a cleanup run is deciding.

Until that contract exists, disk growth is an operational cost accepted to
preserve explicit recovery and audit evidence.
