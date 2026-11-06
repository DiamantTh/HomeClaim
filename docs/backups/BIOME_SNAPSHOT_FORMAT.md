# BiomeSnapshotV1

`BiomeSnapshotV1` is a separate, operation-scoped safety format for `/plot
biome`. It does not change HCBK v1/v2/v3, add biomes to HCBK capabilities, or
make plot COPY/MOVE/SWAP transfer biomes.

## Paper coordinates and scope

Paper 26.3's `RegionAccessor#getBiome(x,y,z)` and `setBiome(x,y,z, biome)` use
block coordinates but address the stored biome at quart resolution. The Paper
26.3 implementation maps each coordinate with `coordinate >> 2`, equivalent to
`Math.floorDiv(coordinate, 4)` for negative coordinates. One stored entry is a
complete 4×4×4 block cell. These APIs and semantics are documented in the
[Paper 26.3 `RegionAccessor` API](https://jd.papermc.io/paper/26.3/org/bukkit/RegionAccessor.html)
and implemented by Paper's
[`CraftRegionAccessor`](https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/org/bukkit/craftbukkit/CraftRegionAccessor.java).

HomeClaim chooses the conservative full-cell rule: only cells whose 64 blocks
are all inside one selected Region are included. Plot edges that cut through a
quart cell leave that entire cell unchanged. A cell is never treated as a
fractional biome region. The selected Region IDs remain reserved for capture,
apply, verification, and recovery. Before mutation, `/plot biome` also rejects
selected bounds that overlap another Region in the same HomeClaim world. A
merged plot uses all of its selected member Regions; cells split across member
boundaries are omitted unless wholly contained by one member.

## On-disk record

The binary file has magic `HCBS`, format version `1`, capture mode
`CELL_ALIGNED_TICKWISE`, creation time in epoch milliseconds, HomeClaim WorldId,
Paper world UUID, AreaId, snapshot UUID, sorted RegionIds, and a bounded sorted
list of `(quartX, quartY, quartZ, biome registry key)` entries. The final 32
bytes are SHA-256 over all preceding bytes. The job stores the snapshot UUID,
whole-file SHA-256, byte size, Paper UUID, and cell count. Files are written to a
temporary sibling and atomically renamed before a mutation job is persisted;
the job is persisted before the first biome write.

The encoding is uncompressed (`NONE`). It is bounded to 500,000 cells and
64 MiB. Unsupported versions, invalid framing, duplicate cells, malformed or
corrupt checksums, identity mismatch, a changed Region cell scope, and missing
registry keys all fail closed. Recovery resolves every key through Paper's live
biome registry before writing any cell; there is no fallback biome. Only keys
are captured, not Datapack definitions: a same-key redefinition follows the
currently loaded registry.

Capture is tickwise. The snapshot records a pre-image read over multiple ticks,
not an atomic world instant. External player, vanilla, or plugin changes are
not serialized by HomeClaim's Region reservation. Recovery reapplies the
recorded per-cell values and verifies them; it may therefore restore the
tickwise captured state if an external writer changed cells during capture.

## Lifecycle and recovery

1. Resolve/authorize the selected Region or merge members.
2. Compute complete interior quart cells; reject an empty or over-limit scope.
3. Reject overlapping Regions in the same HomeClaim world.
4. Require every complete cell to fit inside Paper's min/max world height.
5. The command reserves all selected Region IDs before loading or capturing.
6. Deduplicate exactly the touched chunks, reject more than 256 chunks, and load
   them through Paper's async API (at most two requests in flight, 1,200-tick
   timeout). Acquire temporary plugin tickets for the entire capture/apply/verify
   interval. Any load failure aborts before capture, job creation or biome-cell writes.
7. Capture every cell's Paper registry key, write and checksum the snapshot.
8. Persist a `BIOME` mutation job containing the snapshot reference.
9. Apply the requested biome in bounded batches and verify every cell.
10. Persist `COMPLETED`; any failure after at least one write becomes
   `RECOVERY_REQUIRED`.

An operator can use `/plot jobs inspect <id>` and then request the confirmed
`restore-biome-original` action. Recovery validates the file, bindings, exact
current Region scope, and every registry key before loading chunks or writing
any cell. Recovery then loads and tickets the entire required chunk set before
its first write. It applies and verifies the pre-image, then marks the job `RECOVERED`. A failed or
interrupted recovery remains blocked and can be retried. There is no automatic
resume or rollback. If the database is unavailable, the pre-mutation durable
job is already present; a running job is reclassified by the existing startup
recovery path.

No `BIOMES` HCBK capability is declared. Entities and scheduled block/fluid
ticks remain outside both formats.

## Chunk leases and cancellation

Chunk coordinates derive from quart-cell block origins using `floorDiv(block,16)`;
vertical cells share one chunk request. The limit is fixed at 256 chunks per
operation; at most two async chunk requests run concurrently. The timeout is
1,200 server ticks, not a wall-clock/TPS guarantee. No synchronous mass load is
introduced. The Paper request uses `gen=true`, so missing chunks can be generated
by normal world loading before capture; biome recovery does not undo terrain
generation. No explicit biome-cell writes precede successful acquisition of the
entire chunk set. Snapshot, apply, verification and recovery execute on the Paper
thread in bounded cell batches; file work remains on the snapshot IO worker.

Overlapping operations of the same plugin reference-count tickets. Releasing
one lease cannot remove another lease's ticket, or a pre-existing ticket held
by another caller of that plugin. Last release, cancellation, timeout and
shutdown release owned tickets. Already submitted Paper chunk loads cannot be
cancelled reliably; late completions acquire no ticket and start no mutation.
Successful loading does not make capture atomic or alter the complete-interior
cell boundary policy. COPY/MOVE/SWAP still do not transfer biomes.

## Registry availability

Datapack biome keys are stored exactly like Vanilla keys. A loaded key can
roundtrip without an enum or numeric ID. Missing keys reject the entire recovery
before its first write; the job remains `RECOVERY_REQUIRED`. Restoring the
Datapack and restarting makes the same safety snapshot usable again. The
three-process test fixture removes custom values from live chunk palettes before
removing its Datapack: it tests HomeClaim recovery availability, not Minecraft's
handling of orphaned custom values in chunk files.

## Retention policy

Biome safety files are retained indefinitely after COMPLETED or RECOVERED.
There is currently **no automatic age/count cleanup** and no expiry that deletes
files referenced by active, interrupted or recovery-required jobs. An orphan
can also remain if file creation succeeds but job creation fails. This favors
recoverability and audit evidence over disk reclamation; operators must account
for disk growth. Files are not removed by normal service shutdown.

Automatic cleanup requires the atomic artifact-reference/lease contract defined
in [Safety-artifact retention](SAFETY_RETENTION.md), covering in-flight
captures/pre-job snapshots, all persisted jobs (including
`RECOVERY_IN_PROGRESS`), recovery/audit retention and DB-unavailable behavior.
A missing/unavailable reference store must fail closed; age alone is never
sufficient. No generic HCBK or operation-bundle retention behavior was changed.
Format V1 and codec NONE remain unchanged: the measured 16-cell custom-key
fixture occupies only 787 bytes including framing/checksums.
