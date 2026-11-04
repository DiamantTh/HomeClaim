# HCBK snapshot contract

HCBK is a bounded, versioned file format for HomeClaim's supported plot block
state. A file is not an atomic world snapshot and does not represent every
Minecraft world subsystem.

## Versions

- **v1** reads legacy `NONE` payloads.
- **v2** reads legacy `NONE` and `ZSTD` payloads and stores the uncompressed
  payload length.
- **v3** is used for new backups. It preserves the v2 block payload and adds
  bounded capture metadata to the manifest. The v1 and v2 readers remain
  available; old files are not rewritten.

The manifest has strict framing: trailing fields are rejected. Do not append
metadata to v2. Future incompatible manifest changes require a new format
version. Capture-mode and capability identifiers are stable wire IDs, not enum
ordinals.

## v3 capture metadata

New files record:

- `captureMode = TICKWISE`;
- capture start and completion timestamps (epoch milliseconds);
- the exact number of block positions read, which must equal the declared
  bounds volume;
- typed capabilities: `BLOCK_DATA`, and `CONTAINER_INVENTORY` when at least one
  captured block implements Paper's `Container` interface.

`TICKWISE` means the world was read in bounded batches over multiple server
ticks. It does not claim that all blocks represent one instant. External player,
vanilla, or plugin changes may produce a mixed-time snapshot. Legacy v1/v2 files
have no persisted capture metadata and are shown as `legacy-unrecorded`.

## Captured content

The supported block payload contains each position, its serialized Bukkit
`BlockData`, and, for a supported `Container`, its inventory slots as serialized
Bukkit `ItemStack` bytes. The implementation treats Paper `Container`
implementations generically; E2E coverage includes chest, barrel, furnace, blast
furnace, smoker, hopper, dropper, dispenser, shulker box, brewing stand, and
crafter.

Other `TileState` types fail closed before a destructive operation can begin.
The tested supported container matrix is chest, barrel, furnace, blast furnace,
smoker, hopper, dropper, dispenser, shulker box, brewing stand, and crafter.
Sign and banner are explicitly exercised and fail closed. Hanging sign,
player head, beacon, jukebox, and lectern now have individual Paper and Purpur
world fixtures that verify fail-closed capture and type/position diagnostics.
Other non-container `TileState` types follow the same generic guard. For
ordinary non-`TileState` blocks, tile metadata is not applicable and
`BlockData` is the captured state.

| Paper `TileState` kind | HCBK status | Evidence / reason |
| --- | --- | --- |
| Chest, barrel, furnace, blast furnace, smoker, hopper, dropper, dispenser, shulker box, brewing stand, crafter | `SUPPORTED` | Generic `Container` inventory serialization; real Paper roundtrip coverage in the world E2E. ItemStack bytes include Bukkit-serialized item metadata, subject to Minecraft-version compatibility. |
| Sign and banner | `FAIL_CLOSED` | Real Paper fixture verifies backup rejection before destructive mutation. |
| Hanging sign, player head, beacon, jukebox, lectern, other non-container TileState | `FAIL_CLOSED` | Generic non-Container TileState guard; named types now have real Paper/Purpur fixtures. |
| Ordinary block without TileState | `NOT_APPLICABLE` | Material and serialized `BlockData` are captured. |

Entities, players, biomes, scheduled block/fluid ticks, and unsupported tile
metadata are not captured in HCBK. Plot biome changes use the independent
versioned `BiomeSnapshotV1` safety contract described in
[`BIOME_SNAPSHOT_FORMAT.md`](BIOME_SNAPSHOT_FORMAT.md); this does not add a
`BIOMES` capability to HCBK or make COPY/MOVE/SWAP transfer biomes. Item
serialization is not a promise of cross-version restoration for every future
Minecraft item schema.

## Integrity and resource bounds

SHA-256 covers the exact stored payload byte range: raw bytes for `NONE`, or
compressed Zstandard frame bytes for `ZSTD`. It does not cover manifest fields;
the reader separately validates manifest/index identity, bounds, codec, declared
lengths, capture count, and capability claims against the decoded payload.
Zstandard frame checks do not replace SHA-256.

File, manifest, payload, block-data, inventory-item, slot-count, and decoded
batch sizes are bounded by the format reader. Decode and payload validation run
off the Paper thread; world capture, apply, and verify are scheduled in bounded
batches on the Paper server thread. Capture has one append batch in flight, so
the producer cannot build an unbounded queue in memory.

HCBK v3 remains strictly framed and does not contain biome payloads. Paper
biome cells use the separate `BiomeSnapshotV1` format and its cell-aligned
`/plot biome` contract; see [`BIOME_SNAPSHOT_FORMAT.md`](BIOME_SNAPSHOT_FORMAT.md).
Unknown registry keys fail closed rather than restoring as a default biome.

Entities and pending scheduled ticks likewise remain outside HCBK. The public
entity API does not by itself define safe identity/duplicate handling,
passenger graphs, entity inventories/references, or player exclusion for a
restorable plot snapshot. No stable public API was found that snapshots and
restores the complete scheduled block/fluid tick queue. Neither is encoded.
