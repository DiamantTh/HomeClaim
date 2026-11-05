# Biome runtime evidence

The [format contract](../backups/BIOME_SNAPSHOT_FORMAT.md) remains separate from
HCBK. The runtime uses complete interior quart cells and tickwise capture.

Run from the root checkout with Java 25:

```sh
./gradlew test paperWorldE2E purpurWorldE2E worldE2EParity
./gradlew biomeRegistryRestartE2E
./gradlew paperWorldRestartRecoveryE2E purpurRestartRecoveryE2E
./gradlew networkedPlayerE2E
./gradlew destructiveDbOutageE2E
```

World tasks require 29 named passing scenarios, a valid run ID and the exact
production JAR SHA-256 in their result JSON. They include a real test Datapack,
exact custom registry-key roundtrip, missing-key rejection, initially unloaded
chunk loading, ticket retention despite unload requests, release, load failure,
timeout, cancellation/late completion, overlapping ticket reference counts,
and the two-request concurrency bound. Existing boundary, negative coordinate,
TileState, container, transfer, safety-bundle and HCBK scenarios stay required.

The fixture lives under `src/paperWorldE2E/fixtures/biome-datapack`; pack data
format 121.0 matches the pinned Paper/Purpur Minecraft 26.3 runtime. It is copied
only into temporary server worlds. It is not a production resource.

`biomeRegistryRestartE2E` starts three Paper processes with the same world/DB:

1. Custom key exists. Capture an actual partial BIOME operation and retain the
   durable recovery-required job and original custom-key safety snapshot. Paint
   live cells Vanilla before saving, to separate registry recovery from Minecraft
   orphaned chunk-palette behavior.
2. Remove the Datapack. Assert the key is absent, attempt recovery with the actual
   persisted snapshot, reject before changing any cell, retain recovery blocker.
3. Restore the Datapack. Reuse the same snapshot and restore exact original keys;
   persist RECOVERED and release the blocker.

Machine-readable results and per-process logs are under
`homeclaim-platform-paper/build/reports/biome-registry-restart`. Timeout or missing
or mismatched result files fail the task. No fallback registry value is accepted.
The ordinary world-suite missing-key scenario additionally covers a valid,
checksummed snapshot containing an unavailable key.

Paper and Purpur restart tasks also save `biome-restart-result.json`, bound to
the production artifact SHA. The network suite uses two real offline-mode
ProtoBot players in an isolated loopback server: owner `/plot biome`, admin
inspect/recover/confirm, normal-player denial, exact cells, jobs, audit UUIDs,
and blocker release. The existing command has no confirmation for ordinary
biome changes; explicit recovery does.

World reports include `biome-metrics.json` and `biome-chunk-metrics.json` for
local phase elapsed times (including tick scheduling), cell count and byte size.
These are observations, not CI millisecond thresholds or TPS guarantees.
BiomeSafety codec NONE is unchanged. No automatic retention cleanup was added:
safety evidence is retained indefinitely; account for disk growth.

The Player runner validates and copies a fresh `e2e-result.json` after its audit
assertions. It rejects mismatched run ID, JAR SHA, server type, missing scenarios
or nonzero server exit. A failed run writes FAILED and removes any PASS JSON.
The same Protocol-777 test adapter resends Client Information in PLAY (`0x0e`)
after the initial teleport/load handshake, before sending commands. Its payload
matches the pinned client's CONFIGURATION settings; this avoids Purpur starting
command processing with hidden-chat client options. No runtime brand condition,
client upgrade or production dependency is involved.
