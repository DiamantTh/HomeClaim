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
biome registry before writing any cell; there is no fallback biome.

Capture is tickwise. The snapshot records a pre-image read over multiple ticks,
not an atomic world instant. External player, vanilla, or plugin changes are
not serialized by HomeClaim's Region reservation. Recovery reapplies the
recorded per-cell values and verifies them; it may therefore restore the
tickwise captured state if an external writer changed cells during capture.

## Lifecycle and recovery

1. Resolve/authorize the selected Region or merge members.
2. Compute complete interior quart cells; reject an empty or over-limit scope.
3. Reject overlapping Regions in the same HomeClaim world.
4. Require every complete cell to fit inside Paper's min/max world height, and require every affected chunk to be loaded; reject before capture or mutation otherwise.
5. Require the affected chunks to remain loaded for capture, apply and verify.
6. The command reserves all selected Region IDs.
7. Capture every cell's Paper registry key, write and checksum the snapshot.
8. Persist a `BIOME` mutation job containing the snapshot reference.
9. Apply the requested biome in bounded batches and verify every cell.
10. Persist `COMPLETED`; any failure after at least one write becomes
   `RECOVERY_REQUIRED`.

An operator can use `/plot jobs inspect <id>` and then request the confirmed
`restore-biome-original` action. Recovery validates the file, bindings, exact
current Region scope, loaded chunks, and every registry key before the first
write. It applies and verifies the pre-image, then marks the job `RECOVERED`. A failed or
interrupted recovery remains blocked and can be retried. There is no automatic
resume or rollback. If the database is unavailable, the pre-mutation durable
job is already present; a running job is reclassified by the existing startup
recovery path.

No `BIOMES` HCBK capability is declared. Entities and scheduled block/fluid
ticks remain outside both formats.
