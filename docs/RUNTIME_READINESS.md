# HomeClaim Runtime Readiness Inventory

Snapshot: 2026-10-10, after architecture steps 1–5 and the runtime follow-up work in this checkout.

This document records the observed code and runtime wiring. It is not a feature promise or a release declaration. A class, schema, or test alone does not make a user-facing function active. Re-check the current runtime path before relying on this snapshot.

## Status vocabulary

- `IMPLEMENTED_AND_ACTIVE`: registered in the current runtime and has a user or server entry point.
- `IMPLEMENTED_BUT_PARTIAL`: active, with known behavior or recovery gaps.
- `IMPLEMENTED_BUT_NOT_WIRED`: implementation exists but the current runtime has no entry point to it.
- `LEGACY_ONLY`: retained for source/data compatibility, not the canonical active path.
- `PLACEHOLDER`: a model, key, stub, or declared route without working product behavior.
- `DEAD_CODE`: no current callers or registration were found; remove only after checking compatibility.
- `MISSING_BUT_REQUIRED_FOR_EXISTING_FEATURE`: required behavior for an already exposed function is absent or unsafe.

## Runtime and user-function inventory

| Function | Status | Observed runtime behavior and limits |
|---|---|---|
| Paper bootstrap and command registration | `IMPLEMENTED_AND_ACTIVE` | `paper-plugin.yml` names `HomeClaimPaperPluginBootstrap`; Paper `LifecycleEvents.COMMANDS` registers Brigadier trees for `/homeclaim` (`/hc`), `/plot` (`/p`), and `/zone` (`/z`). Runtime services still initialize in the `JavaPlugin` lifecycle. |
| First-start config and database | `IMPLEMENTED_AND_ACTIVE` | `config/HomeClaim.toml` is created from defaults; SQLite is the default. Flyway initializes the common V1 schema. REST and ClientLink default to disabled. Startup fails when required storage initialization cannot safely complete. |
| World UUID binding and Area/Region persistence | `IMPLEMENTED_AND_ACTIVE` | The database is canonical. Paper worlds bind by UUID, with a legacy-name fallback before binding. Area resolution and region/zone persistence use the World/Area model. User-facing Area administration is not exposed as a dedicated command tree. |
| Plot-world setup and initialization | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/homeclaim setup` starts the player wizard; `/homeclaim plot init` and recovery initialize configured plots. New plot worlds use the native generator. Converting an existing world requires optional FAWE. Admin setup completion callbacks now return to Paper's server thread. |
| Claim | `IMPLEMENTED_AND_ACTIVE` | `/plot claim` resolves the current plot and uses `RegionOperationLimitService`, checks Area ownership/scope, persists owner state, and emits operation audit. Claims have a zero purchase cost in the active command path. There is no REST or GUI claim route. |
| Auto-claim | `IMPLEMENTED_AND_ACTIVE` | `/plot auto` chooses the nearest unclaimed region within the Area resolved at the player's position. It no longer mixes regions from another Area in the same world. |
| Merge | `IMPLEMENTED_BUT_PARTIAL` | `/plot merge [direction]` selects adjacent same-owner plots, enforces same-Area/limit validation in the operation service, persists a merge group, and triggers native border/corridor painting. The Paper mutation event is asynchronous/fire-and-forget from the command's point of view; a visual mutation failure is logged but is not returned as a failed command result. |
| Unlink | `IMPLEMENTED_BUT_PARTIAL` | `/plot unlink [restoreRoads]` persists the unlinked state, waits for the Paper mutation future, and reports completion only after world painting. Persistence is rolled back after a mutation failure and a merged-world repaint is requested. Rollback/repaint are best effort; a partial block mutation can remain and needs operator recovery. Group lookup is Area-scoped. |
| Reset | `IMPLEMENTED_BUT_PARTIAL` | `/plot reset` queues a batched Paper job and audits attempted, allowed, and terminal completion with the same request/correlation held by the command callback. The user receives completed/failed/cancelled feedback and a partial-mutation warning. Reset jobs are not resumed after restart; saved job snapshots are diagnostic only. Partial world changes are not automatically restored. |
| Reset status, progress, cancellation | `IMPLEMENTED_BUT_PARTIAL` | Active jobs are visible through `/plot jobs status [world]` and cancellable. Completion records completed columns, but there is no durable checkpoint, resume protocol, or compensation snapshot for partially reset plots. Shutdown cancels active resets while the Paper thread is available. |
| Home, visit, teleport | `IMPLEMENTED_BUT_PARTIAL` | `/plot home` and `/plot visit <player>` use the first region returned for an owner and choose a surface position. Paper World/Area resolution now prefers the Paper world UUID and HomeClaim `AreaId -> WorldId` binding; a legacy world-name lookup remains only where no bound domain world exists. Teleport uses `teleportAsync` with feedback. Multiple plots/worlds have no explicit selector; the first result is ambiguous. Protection handles entry decisions at the destination. |
| Plot info and list | `IMPLEMENTED_AND_ACTIVE` | `/plot info` and `/plot list` read region, role, flags, price, rating summary, and owner presentation. Display falls back to the legacy world-name field if no Area/World record is available; info still computes its displayed build summary directly from role state. List does not teleport to a selected region. |
| Merge group filtering | `IMPLEMENTED_AND_ACTIVE` | Active merge and unlink candidate/group selection now uses `AreaId` rather than the deprecated world-name field. World mutation backends still use Paper world names at their platform boundary. |
| Roles and membership | `IMPLEMENTED_BUT_PARTIAL` | `RegionRoles` and the shared `RoleService` are the active domain role source. Role changes are reachable through GUI and token-authenticated REST trust/untrust. Plot command role subcommands are not registered. Ban and entry-deny have separate paths; there is not one complete operation interface across every adapter. `PlotMemberService`/its JDBC model are not wired into Paper runtime. |
| Entry-deny and force-entry | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | Entry policies consume role and entry-deny state. REST has entry-deny CRUD/report/revoke and force-entry routes; `/homeclaim entryforce` is an admin path. REST uses a shared admin token and request-supplied actor UUIDs for some records rather than an authenticated per-user principal. |
| Flags, limits, and profiles | `IMPLEMENTED_BUT_PARTIAL` | `RegionAdminService` is used by active GUI flag edits and REST flag/limit/profile routes; profile application and persistence exist. GUI/REST adapters do not all use an `OperationResult`/actor/correlation lifecycle, and flag/profile/limit audit attribution is incomplete. |
| Plot manager GUI | `IMPLEMENTED_AND_ACTIVE` | The menus were previously registered but had no runtime entry point. `/plot gui` and `/plot menu` now open the owner's region list; the existing manager routes to role, price, flags, components, and zone information. This route uses the normal GUI event listener. Full click-through was not automated because this smoke environment had no connected player. |
| GUI price editing | `IMPLEMENTED_BUT_PARTIAL` | The GUI changes a region's price through `RegionService.updateRegion`. The active command path has no buy/sell operation, so a displayed price does not currently create an economy transaction or transfer. |
| Components | `IMPLEMENTED_BUT_PARTIAL` | Component persistence, create/configure/delete UI paths, component-target policy resolution, and interaction protection are present. The manager can be reached from `/plot gui`; placement uses `PadCreatorListener`. Some component mutations still call services directly without a single operation/audit lifecycle. A secondary `BukkitComponentCreationService` GUI entry helper has no active caller. |
| LiftLink | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `PaperPlatformBridge` registers `PaperLiftLinkListener`; `ComponentTriggerHandler` and `LiftLinkPlanner` resolve enabled elevator/teleport pads and check component use. Same-world and cross-world teleports are supported, with explicit `homeclaim.elevator.crossworld` permission for cross-world pads. The optional-world restriction hook is currently an always-allow stub beyond that permission. |
| Spawner use and drop protection | `PLACEHOLDER` | `SPAWNER_USE` and `SPAWNER_TAKE_DROPS` action identifiers exist, but no active Paper spawner handler was found. Do not describe these actions as protected behavior. |
| Protection | `IMPLEMENTED_AND_ACTIVE` | The registered Paper bridge wires block, movement, explosion, automation, entity, and vehicle listeners through `PaperProtectionAdapter` and `PolicyGuard`. Area/region runtime state is used; protection events do not query the database per event. Coverage is broad but not a proof of complete vanilla/plugin mutation protection. |
| Audit | `IMPLEMENTED_BUT_PARTIAL` | Bounded async DB audit writing is active. Plot claim/merge/unlink/reset and some role/component/protection results produce audit entries. Reset completion uses its initiating correlation through the callback. Flag/profile/limit administration and several GUI/REST mutations still use less structured or missing attribution. The `/homeclaim audit` command now reads persisted records by region target. |
| REST reads and mutations | `IMPLEMENTED_BUT_PARTIAL` | Ktor routes are active when configured: health/info/metrics, plot and zone reads, entry checks/denies, trust/untrust, flags, limits, profile application/creation, components, player summaries, admin metrics, and licenses. There are no REST claim/merge/unlink/reset routes. Buy/sell return explicit HTTP 501, not success. Existing mutation routes use the shared role/admin services in runtime wiring, but do not all use the shared operation result/audit contract. The final shaded JAR retains Kotlin built-ins required by Jackson; loopback health tests verified a JSON 401 error body without a token and a JSON 200 response with a token. A Shadow-JAR check protects those runtime resources. |
| WebUX | `IMPLEMENTED_BUT_PARTIAL` | Existing dashboard/plot views call the live API and expose loading/error/unauthorized/empty states; no success-looking demo fallback was found in those paths. The frontend is read-oriented; a full web management flow is not present. No frontend files were changed in this follow-up. |
| Ratings | `IMPLEMENTED_BUT_PARTIAL` | Rating persistence/repository and plot-info display exist. No active player rating submission route or command was found. |
| Economy and sale | `IMPLEMENTED_BUT_PARTIAL` | Vault/Essentials handlers and a mock fallback exist. The active claim path charges zero; `/plots/{id}/buy` and `/sell` deliberately return 501; no active matching Paper plot command was found. The mock handler logs that it is a test/no-economy fallback and must not be treated as a production economy provider. |
| PlotSquared / WorldGuard import | `IMPLEMENTED_BUT_NOT_WIRED` | `ImportCommand`, reflection adapters, and import service exist, but the current Brigadier tree does not register `/homeclaim import`. The importer writes legacy world-name regions, has in-memory progress/cancellation state, and has no active Area-aware/transactional import workflow. It was left unexposed rather than activating a data migration path with incomplete safety semantics. |
| Ownership transfer | `IMPLEMENTED_BUT_NOT_WIRED` | Low-level region persistence supports owner state, but no active transfer command, GUI action, or REST route was found. Do not claim a user-facing ownership transfer feature. |
| Optional plugin dependencies | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | FAWE, Vault, and Essentials are soft optional dependencies. Paper and Purpur 26.3 loaded HomeClaim without them. Existing-world conversion is unavailable without FAWE; ordinary protection and native reset remain available. Economy behavior without Vault/Essentials falls back to a mock handler, not a functional payment system. |
| Reload and shutdown | `IMPLEMENTED_BUT_PARTIAL` | Shutdown closes reset jobs, REST, audit writer, and current datasource; GUI/player cleanup runs on Paper's thread. Storage reload unregisters listeners, retires old services/pools, replaces the event dispatcher, and binds new services. In-flight asynchronous domain operations are not tracked/drained as a coordinated reload transaction. |
| MySQL | `PLACEHOLDER` / experimental | Connector/J is packaged, but the product policy excludes MySQL from supported databases pending dedicated schema and runtime verification. MariaDB support does not prove MySQL support. |

## Entry-point map

| Entry point | Active routes |
|---|---|
| Paper lifecycle commands | `/homeclaim` (`reload`, `migrate`, `setup`, `plot init/recover`, `entryforce`, `audit`); `/plot` (`claim`, `auto`, `home`, `info`, `list`, `gui/menu`, `visit`, `merge`, `unlink`, `reset`, `jobs`, `alias`, `desc`); `/zone info`. |
| Inventory GUI | `/plot gui` -> owner region list -> region manager -> role, price, flags, components, zone information. Pad creation uses `PadCreatorListener`. |
| Paper events | Six protection listener groups plus LiftLink; event adapter -> `ActionRequest`/`PolicyGuard` -> cancellation or Paper operation. |
| REST | Configurable Ktor server; token authentication. Mutations are admin-token operations; client bodies are not a per-user authentication mechanism. |
| WebUX | Read paths through the API; no current claim/merge/reset web mutation entry point was found. |
| Import | Command and adapters exist in source but are not registered in the Paper lifecycle tree. |

Known adapter shortcuts remain: `PlotCommand` performs some plot-specific owner/permission validation before calling claim/merge limit operations; GUI component and price mutations have direct service calls; REST administration is not a per-user operation service. None of these should be expanded into new parallel policy logic.

## Runtime checks performed for this snapshot

- A full `./gradlew build --no-daemon` completed successfully using the Gradle cache fallback `GRADLE_USER_HOME=/tmp/homeclaim-gradle` because the sandbox blocked the normal Gradle cache and lock service.
- The generated shaded plugin JAR contains `paper-plugin.yml`, the common Flyway initial schema, Kotlin runtime, Ktor, Jackson, HikariCP, Flyway, and SQLite/PostgreSQL/MariaDB/MySQL JDBC drivers. A packaged connector alone does not establish dialect support.
- Paper 26.3 build 169 and Purpur 26.3 build 2647 on Java 25 loaded the same final shaded plugin JAR (SHA-256 `02c0d255c0ea310edc499e9ba6c282a1302d50b605aca7d8ce24d345ccb0b162`) without FAWE, Vault, or PlotSquared. Each initialized SQLite/Flyway on first boot and on a second boot against the same database, registered the plot Brigadier tree including `/plot gui` and `/plot menu`, and shut down with audit and Hikari resources closed.
- A Paper loopback REST smoke with a temporary token returned 401 with a serialized JSON error body without the token and 200 with a serialized JSON health body with the token. The first smoke exposed a Kotlin/Jackson serialization failure despite HTTP 200 because Shadow excluded Kotlin built-ins; the exclusions were removed, the `shadowJar` task now asserts required resources exist, and the final rebuilt JAR passed both requests without serialization errors.
- The Gradle runtime suite executed SQLite. Its PostgreSQL and MariaDB cases were skipped because no isolated JDBC test URLs were configured in this environment. Local `pg_isready` found no PostgreSQL server; the installed Podman could not initialize its rootless runtime because the sandbox makes `/run/user/1000/libpod` read-only. PostgreSQL/MariaDB had been verified in the preceding database-focused work; this checkout's newly added audit reader roundtrip has only been re-run against SQLite here.
- The server smokes exercised first install, repeated startup against existing SQLite state, command registration, REST HTTP responses, and clean shutdown. They did not connect a real player and therefore did not exercise claim, role, GUI click, teleport, reset, or protection gameplay.

## Release-readiness matrix

### BLOCKER BEFORE FIRST RELEASE

- Reset has no durable resumable checkpoint or automatic recovery after shutdown/crash. A failed reset can leave a partially mutated plot. Before release, define operator-visible recovery and make cancellation/failure state survive restart, or explicitly prevent interrupted jobs from being mistaken for completed resets.
- Merge visual mutation is initiated asynchronously from the database/domain event and does not return a completion result to `/plot merge`. The persisted merge group can succeed while road/border painting fails. The world/domain consistency contract needs a visible, recoverable terminal result.
- There is no live-player end-to-end runtime test for claim -> role/flag change -> protection -> restart -> GUI/teleport. Unit and database tests do not prove this combined gameplay sequence. A first release candidate needs an isolated player-capable smoke harness or equivalent integration test.
- PostgreSQL/MariaDB test URLs were absent in this follow-up environment, so the newly added `JdbcAuditService.findForTarget` query was only exercised on SQLite during this run. Re-run that exact query and audit operation roundtrip on both reference/server dialects before a release candidate.

### SHOULD FIX BEFORE FIRST RELEASE

- `/plot home` and `/plot visit` choose the first owned region without a selector; this becomes ambiguous for owners with multiple plots or worlds.
- Ownership transfer has persistence support but no user-facing operation. Confirm whether transfer is in the first-release feature set and either finish it through the operation/audit path or state clearly that it is not available.
- Role, flag, limit, profile, component, and REST mutations do not all share one actor-aware `OperationResult`/authorization/audit lifecycle. Review direct GUI/REST service paths for consistent authorization, immediate runtime invalidation, and actor attribution.
- The import implementation is unregistered and lacks safe Area-aware, restartable import semantics. Do not advertise it until a dedicated import path is wired and tested.
- `SPAWNER_USE` and `SPAWNER_TAKE_DROPS` have keys but no registered handler. Either implement only if these flags are advertised, or remove/label the inactive keys before describing protection coverage.
- Reset/unlink user feedback is currently English in parts of the async failure path; localize operation completion and recovery instructions consistently.
- Existing-world conversion requires FAWE. Keep that limitation explicit in setup help and installation docs; new-world setup and core protection work without FAWE.

### SAFE POST-RELEASE IMPROVEMENT

- Make REST mutation responses and audit records uniformly expose operation correlation and structured result codes.
- Add a region selector to home/visit and a public API path using stable World/Area IDs.
- Add broader Paper listener integration coverage for multi-place, piston, liquid, hopper, dispenser, explosion, environment, entity, vehicle, and entry boundaries.
- Add player-driven Paper/Purpur tests for GUI click navigation and actual claim/protection behavior.
- Improve reload coordination so in-flight asynchronous database work is drained or rejected before replacing services.
- Consolidate unused secondary GUI/import helpers and legacy adapters after checking addon/public API callers.

### FUTURE FEATURE

- Full economy-backed plot purchase/sale, rating submission, Area administration UI, more zone authoring tools, and spawner-specific behavior are outside this runtime follow-up unless explicitly selected as existing first-release requirements.
- Distributed caching, new external permission/protection bridges, dimension/island systems, and new integration families remain future ideas under the root `AGENTS.md` contract.

### COMPATIBILITY DEBT

- Legacy Bukkit `onCommand` / `onTabComplete` adapters remain on `HomeClaimPaperPlugin`, `PlotCommand`, and `ZoneCommand`; the active command registration is lifecycle Brigadier.
- String-valued world names remain in compatibility properties, selected REST/client DTOs, import data, and platform mutation/config paths. Stable UUIDs and `AreaId` remain canonical domain identity.
- The client API module still has a string-valued `WorldId`, separate action/component registries, and its own public models.
- The all-zero UUID remains the unclaimed/system sentinel in owner-facing legacy models and Paper actor adapters. New domain ownership must not extend this pattern.
- The closed internal `Action` enum and deprecated `PolicyActionRequest` remain compatibility/implementation mappings; the active Paper protection path uses `ActionRequest`.
- Historical docs mention Folia; no Folia runtime adapter, scheduler path, or support metadata is active.

## Current readiness conclusion

This checkout has a coherent startup path and active Paper/Purpur plugin loading. It is **READY FOR NEXT RELEASE-READINESS PASS**, not declared release-ready. Reset recovery, merge mutation completion, player-driven end-to-end gameplay tests, and the current audit-query PostgreSQL/MariaDB rerun remain concrete release gates.
