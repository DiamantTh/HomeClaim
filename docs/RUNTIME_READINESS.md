# HomeClaim Runtime Readiness Inventory

Snapshot: 2026-10-11, after the runtime/operations consolidation follow-up in this checkout.

This document records the observed code and runtime wiring. It is not a feature promise. A class, schema, or test alone does not make a user-facing function active. Re-check the current runtime path before relying on this snapshot.

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
| Plot-world setup and initialization | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/homeclaim setup` starts the player wizard; `/homeclaim plot init` and recovery initialize configured plots. New plot worlds use the native generator. Converting an existing world requires optional FAWE. Before conversion, the wizard now waits for all players in that world to confirm their asynchronous evacuation; a rejected or failed teleport aborts conversion and reports the affected player names. Admin setup completion callbacks return to Paper's server thread. |
| Claim | `IMPLEMENTED_AND_ACTIVE` | `/plot claim` resolves the current plot and uses `RegionOperationLimitService`, checks Area ownership/scope, persists owner state, and emits operation audit. Claims have a zero purchase cost in the active command path. There is no REST or GUI claim route. |
| Auto-claim | `IMPLEMENTED_AND_ACTIVE` | `/plot auto` chooses the nearest unclaimed region within the Area resolved at the player's position. It no longer mixes regions from another Area in the same world. |
| Merge | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot merge [direction]` validates same-Area adjacency, owner, and configured limits, reserves affected regions, and journals a correlated mutation job. It waits for Paper border/corridor painting before persisting the merge group and reporting success. A world failure leaves the group unmerged and marks recovery required. A later DB failure triggers best-effort visual compensation; compensation failure is also recorded as recovery required. No durable block-level undo exists. |
| Unlink | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot unlink [restoreRoads]` journals a correlated operation, persists the unlinked region state, waits for the Paper mutation future, and reports success only after painting. On failure it best-effort restores the merge group and merged visuals. If either compensation fails, the job is marked `RECOVERY_REQUIRED`; a crash while the job is nonterminal is detected at next startup. Partial block changes cannot be automatically reconstructed. Group lookup is Area-scoped. |
| Reset | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot reset` queues a batched Paper job with a durable SQL journal row before mutation. Correlation is retained through completion. The user receives queued and terminal feedback. Progress is checkpointed periodically by completed column count and at completion. Partial mutation, cancellation after mutation, backend failure, or journal-finalization failure is reported as recovery required; there is no automatic resume or persistent undo snapshot. |
| Reset status, progress, cancellation | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot jobs status [world]` shows active work and recovery-required journal records; cancellation is available for active jobs. At startup, queued/running jobs are changed to `RECOVERY_REQUIRED` and logged with region, world, progress, and correlation. The checkpoint is diagnostic, not a safe resume cursor. Operators must inspect the plot and choose a recovery action; a dedicated recovery command is not implemented. |
| Home, visit, teleport | `IMPLEMENTED_BUT_PARTIAL` | `/plot home [number|plot-id]` and `/plot visit <player> [number|plot-id]` resolve one plot deterministically. With multiple matches they require an explicit one-based selector or full Region UUID; `/plot list` uses the same UUID ordering and displays both. A single match needs no selector. Paper World/Area resolution prefers the Paper world UUID and HomeClaim `AreaId -> WorldId` binding; a legacy world-name lookup remains only where no bound domain world exists. Teleport uses `teleportAsync`; success feedback is sent only after a successful completion, and rejection/exception is surfaced to the player. Auto-claim likewise waits for a successful teleport before claiming. Command-tree and server-near tests cover parsing, ambiguity, and completion results; no connected player has exercised the teleport in this environment. |
| Plot info and list | `IMPLEMENTED_AND_ACTIVE` | `/plot info` and `/plot list` read region, role, flags, price, rating summary, and owner presentation. Display falls back to the legacy world-name field if no Area/World record is available; info still computes its displayed build summary directly from role state. List does not teleport to a selected region. |
| Merge group filtering | `IMPLEMENTED_AND_ACTIVE` | Active merge and unlink candidate/group selection now uses `AreaId` rather than the deprecated world-name field. World mutation backends still use Paper world names at their platform boundary. |
| Roles and membership | `IMPLEMENTED_BUT_PARTIAL` | `RegionRoles` and the shared `RoleService` are the active domain role source. Role changes are reachable through GUI and token-authenticated REST trust/untrust. Plot command role subcommands are not registered. Ban and entry-deny have separate paths; there is not one complete operation interface across every adapter. `PlotMemberService`/its JDBC model are not wired into Paper runtime. |
| Entry-deny and force-entry | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | Entry policies consume role and entry-deny state. REST has entry-deny CRUD/report/revoke and force-entry routes; `/homeclaim entryforce` is an admin path. Entry-deny creation now dispatches Paper player/world access onto the server thread, resolves the region world by platform UUID where bound, and attempts ejection for owners too; only the explicit entry-bypass capability skips it. Asynchronous ejection failure is logged and reported to the affected player. REST uses a shared admin token and request-supplied actor UUIDs for some records rather than an authenticated per-user principal. |
| Flags, limits, and profiles | `IMPLEMENTED_BUT_PARTIAL` | `RegionAdminService` is used by active GUI flag edits and REST flag/limit/profile routes; profile application and persistence exist. GUI/REST adapters do not all use an `OperationResult`/actor/correlation lifecycle, and flag/profile/limit audit attribution is incomplete. |
| Plot manager GUI | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot gui` and `/plot menu` open the owner's region list; the manager routes to role, price, flags, components, and zone information through the registered GUI event listener. Command/registration smoke passed. A connected client was unavailable, so opening, navigation, and click-driven mutations were not verified in this pass. |
| GUI price editing | `IMPLEMENTED_BUT_PARTIAL` | The GUI changes a region's price through `RegionService.updateRegion`. The active command path has no buy/sell operation, so a displayed price does not currently create an economy transaction or transfer. |
| Components | `IMPLEMENTED_BUT_PARTIAL` | Component persistence, create/configure/delete UI paths, component-target policy resolution, and interaction protection are present. The manager can be reached from `/plot gui`; placement uses `PadCreatorListener`. Some component mutations still call services directly without a single operation/audit lifecycle. A secondary `BukkitComponentCreationService` GUI entry helper has no active caller. |
| LiftLink | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `PaperPlatformBridge` registers `PaperLiftLinkListener`; `ComponentTriggerHandler` and `LiftLinkPlanner` resolve enabled elevator/teleport pads and check component use. Same-world and cross-world teleports are supported, with explicit `homeclaim.elevator.crossworld` permission for cross-world pads. Completion feedback and terminal audit now follow the asynchronous teleport result. The optional-world restriction hook is currently an always-allow stub beyond that permission. |
| Spawner use and drop protection | `PLACEHOLDER` | `SPAWNER_USE` and `SPAWNER_TAKE_DROPS` action identifiers exist, but no active Paper spawner handler was found. Do not describe these actions as protected behavior. |
| Protection | `IMPLEMENTED_AND_ACTIVE` | The registered Paper bridge wires block, movement, explosion, automation, entity, and vehicle listeners through `PaperProtectionAdapter` and `PolicyGuard`. Area/region runtime state is used; protection events do not query the database per event. Coverage is broad but not a proof of complete vanilla/plugin mutation protection. |
| Audit | `IMPLEMENTED_BUT_PARTIAL` | Bounded async DB audit writing is active. Plot claim/merge/unlink/reset and some role/component/protection results produce audit entries. Reset completion uses its initiating correlation through the callback. Flag/profile/limit administration and several GUI/REST mutations still use less structured or missing attribution. The `/homeclaim audit` command now reads persisted records by region target. |
| REST reads and mutations | `IMPLEMENTED_BUT_PARTIAL` | Ktor routes are active when configured: health/info/metrics, plot and zone reads, entry checks/denies, trust/untrust, flags, limits, profile application/creation, components, player summaries, admin metrics, and licenses. There are no REST claim/merge/unlink/reset routes. Buy/sell return explicit HTTP 501, not success. Shared DB runtime tests cover trust mutation, validation, missing resources, unauthorized requests, audit, and repository reload on SQLite/PostgreSQL/MariaDB. Existing mutations do not all share one structured actor-aware operation contract. A REST bind failure now releases the failed Ktor engine, logs a port-specific message, and leaves core HomeClaim enabled without REST. |
| WebUX | `IMPLEMENTED_BUT_PARTIAL` | Existing dashboard/plot views call the live API and expose loading/error/unauthorized/empty states; no success-looking demo fallback was found in those paths. The frontend is read-oriented; a full web management flow is not present. No frontend files were changed in this follow-up. |
| Ratings | `IMPLEMENTED_BUT_PARTIAL` | Rating persistence/repository and plot-info display exist. No active player rating submission route or command was found. |
| Economy and sale | `IMPLEMENTED_BUT_PARTIAL` | Vault/Essentials handlers and a mock fallback exist. The active claim path charges zero; `/plots/{id}/buy` and `/sell` deliberately return 501; no active matching Paper plot command was found. The mock handler logs that it is a test/no-economy fallback and must not be treated as a production economy provider. |
| PlotSquared / WorldGuard import | `IMPLEMENTED_BUT_NOT_WIRED` | `ImportCommand`, reflection adapters, and import service exist, but the current Brigadier tree does not register `/homeclaim import`. The importer writes legacy world-name regions, has in-memory progress/cancellation state, and has no active Area-aware/transactional import workflow. It was left unexposed rather than activating a data migration path with incomplete safety semantics. |
| Ownership transfer | `IMPLEMENTED_BUT_NOT_WIRED` | Low-level region persistence supports owner state, but no active transfer command, GUI action, or REST route was found. It is not currently exposed as a user-facing function. |
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

- The earlier database verification ran `./gradlew build --no-daemon --rerun-tasks --no-build-cache` with `GRADLE_USER_HOME=/tmp/homeclaim-gradle` and isolated PostgreSQL/MariaDB JDBC URLs: 149 tests, 0 failures, 0 errors, 0 skipped. That included the PostgreSQL, MariaDB, and SQLite `DatabaseDialectRuntimeTest` cases and covered Flyway initialization; world/area/region/zone/component/role/profile/entry-deny persistence; claim/merge persistence; REST trust mutation and error mappings; repository reload; mutation-job interruption state; and audit target queries with target filtering, ordering, limit, empty result, and correlation readback. The isolated PostgreSQL and MariaDB containers were stopped after verification.
- For this follow-up, `GRADLE_USER_HOME=/tmp/homeclaim-gradle ./gradlew build --no-daemon --rerun-tasks --no-build-cache` completed successfully: 159 tests, 0 failures, 0 errors, 2 skipped. Both skipped cases are the PostgreSQL and MariaDB runtime cases in `DatabaseDialectRuntimeTest`, because `HOMECLAIM_TEST_POSTGRES_JDBC_URL` and `HOMECLAIM_TEST_MARIADB_JDBC_URL` were not set for this run. No database, repository, or migration source was changed in this follow-up, so the earlier live dialect results remain the applicable DB evidence. The temporary cache was used because this sandbox did not permit the normal Gradle cache/network setup; it was an environment fallback, not a project path change.
- The mutation-job table was added directly to the unreleased canonical `V1__init.sql`; no artificial follow-up migration version was created.
- Final shaded JAR SHA-256 for this follow-up: `8a7f77d83fd89d99acfb685a23d278bfaad6aa043d59dabee4b993c2fa836ea0`. Inspection confirmed `paper-plugin.yml`, `V1__init.sql`, Kotlin `Intrinsics`, Flyway, Jackson, HikariCP, SQLite/PostgreSQL/MariaDB/MySQL JDBC drivers, and service resources. Packaging MySQL Connector/J does not make MySQL a supported dialect.
- Paper 26.3 build 169 and Purpur 26.3 build 2647 on Java 25 both loaded this exact shaded JAR on temporary servers using SQLite, initialized or validated the schema, registered the Paper bridge/native mutation hooks, exposed the `/plot` Brigadier tree with the home/visit selectors, and shut down cleanly with HomeClaim hooks and Hikari closed. FAWE, Vault, and PlotSquared were absent. Earlier Paper verification also restarted against its existing SQLite database; Flyway reported the schema up to date.
- A Paper REST-port-conflict smoke used the Minecraft server port as the configured REST port. HomeClaim logged that it remains active without REST, completed initialization, retained its command tree, and shut down cleanly. An unavailable-PostgreSQL startup smoke reported connection refused, disabled HomeClaim rather than claiming successful startup, left Paper itself available, and then shut down cleanly.
- The official Prism client could not create an offline profile without a Microsoft account that owns Minecraft. No account credentials were accessed and no licensing bypass was attempted. A temporary Mineflayer 4.39 protocol client was also tried; its bundled data ends at 26.1, while Paper 26.3 advertises protocol 777, and it reports `No data available for version 26.3`. It did not connect. Therefore no player command, GUI click, claim, role/flag change, protection action, home/visit teleport, merge, unlink, reset, or player-driven restart flow was verified.
- This follow-up added server-near tests for `/plot home` selection, lifecycle command parsing, asynchronous teleport completion, and conversion evacuation aggregation. These verify that no arbitrary owned plot is chosen, selectors reach the command adapter, success is not reported for rejected/failed teleports, and conversion waits for every evacuation; they are not a substitute for connected-player interaction tests.
- No frontend files changed, so npm checks were not run. `git diff --check` passed on the final working tree before commit.

## Technical remaining work

This inventory describes current technical behavior and open work.

### MUST FIX FOR CORE FUNCTIONALITY

- No currently evidenced silent false-success path remains in claim, merge, unlink, reset, or the tested REST trust mutation. A broader scan and behavior tests are still needed whenever those paths change.
- This snapshot contains no confirmed immediate core defect in the reviewed paths. Add items here only when a reproducible correctness, persistence, protection, or authorization defect is confirmed.

### SHOULD FIX SOON

- Reset and merge/unlink recovery is visible and correlated, but interrupted or partial world changes cannot be safely resumed or automatically undone. Operators must inspect affected regions and perform manual repair; there is no dedicated recovery command or durable block snapshot.
- Role, flag, limit, profile, component, price, and REST mutations do not all use one actor-aware `OperationResult`/authorization/audit lifecycle. GUI component/price paths still write through services directly, and the role GUI currently exposes removal but not adding a role.
- The connected-player workflow has not been exercised. The local environment had no compatible Paper 26.3 client; server startup, command registration, command-tree tests, and database tests do not validate live inventory clicks or player-to-player protection behavior.
- Some asynchronous reset/unlink feedback remains English even when the player's locale is German.
- Existing-world conversion requires FAWE. Keep that operation-specific requirement clear in setup help and documentation; ordinary runtime, claims, protection, and native resets work without FAWE.
- World conversion now aborts if any player's evacuation teleport is rejected or fails. There is still no rollback for a failure after FAWE has begun editing the world.
- Home/Visit remain unverified with a connected player. Paper and Purpur startup, the current command tree, SQLite initialization, and clean shutdown were smoke-tested against the rebuilt shaded artifact.

### TECHNICAL DEBT

- Legacy Bukkit `onCommand` / `onTabComplete` adapters remain on `HomeClaimPaperPlugin`, `PlotCommand`, and `ZoneCommand`; active registration is lifecycle Brigadier.
- String-valued world names remain in compatibility properties, selected REST/client DTOs, import data, and platform mutation/config paths. Stable UUIDs and `AreaId` remain canonical domain identity.
- The client API module still has a string-valued `WorldId`, separate action/component registries, and its own public models.
- The all-zero UUID remains the unclaimed/system sentinel in owner-facing legacy models and Paper actor adapters. New domain ownership must not extend this pattern.
- The closed internal `Action` enum and deprecated `PolicyActionRequest` remain compatibility/implementation mappings; active Paper protection uses `ActionRequest`.
- Home/visit selection is deterministic for the selected owner's plots, but selection uses per-owner ordering or a full Region UUID rather than a user-friendly alias.
- Historical documents mention Folia; no Folia runtime adapter, scheduler path, or support metadata is active.

### FUTURE FEATURE

- PlotSquared/WorldGuard import remains unregistered and is not Area-aware or restartable. Existing importer code is not a safe active migration workflow.
- Ownership transfer has persistence support but no user-facing command, GUI action, or REST route.
- `SPAWNER_USE` and `SPAWNER_TAKE_DROPS` action keys have no active handler.
- Full economy-backed purchase/sale, rating submission, Area administration UI, and richer zone authoring are not currently exposed as complete user workflows.
- Distributed caching, new external permission/protection bridges, dimension/island systems, and other integration families remain future ideas under the root `AGENTS.md` contract.

### OPTIONAL / EXPERIMENTAL

- REST is configurable and active for its existing routes, but its mutations use a shared admin token rather than per-player authentication and it has no claim/merge/unlink/reset routes.
- WebUX is read-oriented and depends on the live API; it does not provide a complete plot-management workflow.
- LiftLink is active for existing pads and cross-world permission checks, but its optional-world restriction hook is currently an always-allow stub.
- Economy adapters include a mock fallback and do not constitute a real payment flow. Claiming is currently free; buy/sell return HTTP 501.
- Ratings can be stored and displayed, but no active submission entry point was found.
- MySQL remains experimental and unsupported pending dedicated schema and runtime verification.

## Runtime boundary notes

- The official Prism client could not create an offline profile without a Microsoft account that owns Minecraft. No account credentials were accessed and no licensing bypass was attempted. The locally available Mineflayer 4.39 protocol data stops at 26.1, while Paper 26.3 advertises protocol 777. No compatible local client executable or client JAR was found during this follow-up.
- A connected player therefore has not exercised the end-to-end workflow. The new selector tests are server-near command/domain tests, not live-client tests. GUI clicks, role/flag changes in an inventory, real protection actions, and player-driven teleport remain unverified in a live client session.
- PostgreSQL, MariaDB, and SQLite runtime evidence above remains from the previous database verification. No database source, migration, or repository code changed in this follow-up, so those dialect tests were not repeated.
- This follow-up used `/tmp/homeclaim-gradle` only as the Gradle cache fallback because the default sandbox prevented Gradle from determining a usable wildcard IP before test execution. Source files stayed in canonical repository paths.
