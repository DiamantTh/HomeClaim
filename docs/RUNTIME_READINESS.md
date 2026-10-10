# HomeClaim Runtime Readiness Inventory

Snapshot: 2026-10-11, after the first-release readiness follow-up in this checkout.

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
| Merge | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot merge [direction]` validates same-Area adjacency, owner, and configured limits, reserves affected regions, and journals a correlated mutation job. It waits for Paper border/corridor painting before persisting the merge group and reporting success. A world failure leaves the group unmerged and marks recovery required. A later DB failure triggers best-effort visual compensation; compensation failure is also recorded as recovery required. No durable block-level undo exists. |
| Unlink | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot unlink [restoreRoads]` journals a correlated operation, persists the unlinked region state, waits for the Paper mutation future, and reports success only after painting. On failure it best-effort restores the merge group and merged visuals. If either compensation fails, the job is marked `RECOVERY_REQUIRED`; a crash while the job is nonterminal is detected at next startup. Partial block changes cannot be automatically reconstructed. Group lookup is Area-scoped. |
| Reset | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot reset` queues a batched Paper job with a durable SQL journal row before mutation. Correlation is retained through completion. The user receives queued and terminal feedback. Progress is checkpointed periodically by completed column count and at completion. Partial mutation, cancellation after mutation, backend failure, or journal-finalization failure is reported as recovery required; there is no automatic resume or persistent undo snapshot. |
| Reset status, progress, cancellation | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot jobs status [world]` shows active work and recovery-required journal records; cancellation is available for active jobs. At startup, queued/running jobs are changed to `RECOVERY_REQUIRED` and logged with region, world, progress, and correlation. The checkpoint is diagnostic, not a safe resume cursor. Operators must inspect the plot and choose a recovery action; a dedicated recovery command is not implemented. |
| Home, visit, teleport | `IMPLEMENTED_BUT_PARTIAL` | `/plot home` and `/plot visit <player>` use the first region returned for an owner and choose a surface position. Paper World/Area resolution now prefers the Paper world UUID and HomeClaim `AreaId -> WorldId` binding; a legacy world-name lookup remains only where no bound domain world exists. Teleport uses `teleportAsync` with feedback. Multiple plots/worlds have no explicit selector; the first result is ambiguous. Protection handles entry decisions at the destination. |
| Plot info and list | `IMPLEMENTED_AND_ACTIVE` | `/plot info` and `/plot list` read region, role, flags, price, rating summary, and owner presentation. Display falls back to the legacy world-name field if no Area/World record is available; info still computes its displayed build summary directly from role state. List does not teleport to a selected region. |
| Merge group filtering | `IMPLEMENTED_AND_ACTIVE` | Active merge and unlink candidate/group selection now uses `AreaId` rather than the deprecated world-name field. World mutation backends still use Paper world names at their platform boundary. |
| Roles and membership | `IMPLEMENTED_BUT_PARTIAL` | `RegionRoles` and the shared `RoleService` are the active domain role source. Role changes are reachable through GUI and token-authenticated REST trust/untrust. Plot command role subcommands are not registered. Ban and entry-deny have separate paths; there is not one complete operation interface across every adapter. `PlotMemberService`/its JDBC model are not wired into Paper runtime. |
| Entry-deny and force-entry | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | Entry policies consume role and entry-deny state. REST has entry-deny CRUD/report/revoke and force-entry routes; `/homeclaim entryforce` is an admin path. REST uses a shared admin token and request-supplied actor UUIDs for some records rather than an authenticated per-user principal. |
| Flags, limits, and profiles | `IMPLEMENTED_BUT_PARTIAL` | `RegionAdminService` is used by active GUI flag edits and REST flag/limit/profile routes; profile application and persistence exist. GUI/REST adapters do not all use an `OperationResult`/actor/correlation lifecycle, and flag/profile/limit audit attribution is incomplete. |
| Plot manager GUI | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `/plot gui` and `/plot menu` open the owner's region list; the manager routes to role, price, flags, components, and zone information through the registered GUI event listener. Command/registration smoke passed. A connected client was unavailable, so opening, navigation, and click-driven mutations were not verified in this pass. |
| GUI price editing | `IMPLEMENTED_BUT_PARTIAL` | The GUI changes a region's price through `RegionService.updateRegion`. The active command path has no buy/sell operation, so a displayed price does not currently create an economy transaction or transfer. |
| Components | `IMPLEMENTED_BUT_PARTIAL` | Component persistence, create/configure/delete UI paths, component-target policy resolution, and interaction protection are present. The manager can be reached from `/plot gui`; placement uses `PadCreatorListener`. Some component mutations still call services directly without a single operation/audit lifecycle. A secondary `BukkitComponentCreationService` GUI entry helper has no active caller. |
| LiftLink | `IMPLEMENTED_AND_ACTIVE` / `IMPLEMENTED_BUT_PARTIAL` | `PaperPlatformBridge` registers `PaperLiftLinkListener`; `ComponentTriggerHandler` and `LiftLinkPlanner` resolve enabled elevator/teleport pads and check component use. Same-world and cross-world teleports are supported, with explicit `homeclaim.elevator.crossworld` permission for cross-world pads. The optional-world restriction hook is currently an always-allow stub beyond that permission. |
| Spawner use and drop protection | `PLACEHOLDER` | `SPAWNER_USE` and `SPAWNER_TAKE_DROPS` action identifiers exist, but no active Paper spawner handler was found. Do not describe these actions as protected behavior. |
| Protection | `IMPLEMENTED_AND_ACTIVE` | The registered Paper bridge wires block, movement, explosion, automation, entity, and vehicle listeners through `PaperProtectionAdapter` and `PolicyGuard`. Area/region runtime state is used; protection events do not query the database per event. Coverage is broad but not a proof of complete vanilla/plugin mutation protection. |
| Audit | `IMPLEMENTED_BUT_PARTIAL` | Bounded async DB audit writing is active. Plot claim/merge/unlink/reset and some role/component/protection results produce audit entries. Reset completion uses its initiating correlation through the callback. Flag/profile/limit administration and several GUI/REST mutations still use less structured or missing attribution. The `/homeclaim audit` command now reads persisted records by region target. |
| REST reads and mutations | `IMPLEMENTED_BUT_PARTIAL` | Ktor routes are active when configured: health/info/metrics, plot and zone reads, entry checks/denies, trust/untrust, flags, limits, profile application/creation, components, player summaries, admin metrics, and licenses. There are no REST claim/merge/unlink/reset routes. Buy/sell return explicit HTTP 501, not success. Shared DB runtime tests cover trust mutation, validation, missing resources, unauthorized requests, audit, and repository reload on SQLite/PostgreSQL/MariaDB. Existing mutations do not all share one structured actor-aware operation contract. A REST bind failure now releases the failed Ktor engine, logs a port-specific message, and leaves core HomeClaim enabled without REST. |
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

- `./gradlew build --no-daemon --rerun-tasks --no-build-cache` completed successfully with `GRADLE_USER_HOME=/tmp/homeclaim-gradle` and isolated PostgreSQL/MariaDB JDBC URLs. The cache fallback was required because the sandbox denied writes to the normal Gradle cache. Results: 149 tests, 0 failures, 0 errors, 0 skipped.
- The PostgreSQL, MariaDB, and SQLite `DatabaseDialectRuntimeTest` cases all ran. They exercised Flyway initialization; world/area/region/zone/component/role/profile/entry-deny persistence; claim/merge persistence; REST trust mutation and error mappings; repository reload; mutation-job interruption state; and audit target queries with target filtering, ordering, limit, empty result, and correlation readback. The isolated PostgreSQL and MariaDB containers were stopped after verification.
- The mutation-job table was added directly to the unreleased canonical `V1__init.sql`; no artificial follow-up migration version was created.
- Final shaded JAR SHA-256: `7473d038cfe027ed8d191df4854b806b81eaa811332c2046775245fb8f72441f`. Inspection confirmed `paper-plugin.yml`, `V1__init.sql`, Kotlin `Intrinsics`, Flyway, Jackson, HikariCP, SQLite/PostgreSQL/MariaDB/MySQL JDBC drivers, and service resources. Packaging MySQL Connector/J does not make MySQL a supported dialect.
- Paper 26.3 build 169 and Purpur 26.3 build 2647 on Java 25 loaded the final shaded JAR on fresh temporary servers using SQLite, initialized the schema, registered the Paper bridge/native mutation hooks, exposed the `/plot` Brigadier tree (including `gui`, `merge`, `unlink`, `reset`, and `jobs`), and shut down cleanly with HomeClaim hooks and Hikari closed. FAWE, Vault, and PlotSquared were absent. Paper also restarted against its existing SQLite database; Flyway reported the schema up to date.
- A Paper REST-port-conflict smoke used the Minecraft server port as the configured REST port. HomeClaim logged that it remains active without REST, completed initialization, retained its command tree, and shut down cleanly. An unavailable-PostgreSQL startup smoke reported connection refused, disabled HomeClaim rather than claiming successful startup, left Paper itself available, and then shut down cleanly.
- The official Prism client could not create an offline profile without a Microsoft account that owns Minecraft. No account credentials were accessed and no licensing bypass was attempted. A temporary Mineflayer 4.39 protocol client was also tried; its bundled data ends at 26.1, while Paper 26.3 advertises protocol 777, and it reports `No data available for version 26.3`. It did not connect. Therefore no player command, GUI click, claim, role/flag change, protection action, home/visit, merge, unlink, reset, or player-driven restart flow was verified.
- No frontend files changed, so npm checks were not run. `git diff --check` passed on the final working tree before commit.

## Release-readiness matrix

### BLOCKER BEFORE FIRST RELEASE

- The connected-player end-to-end workflow remains unverified. No client compatible with the tested Paper 26.3 protocol could connect in this environment. Server startup, command registration, and unit/database tests do not prove the real claim -> roles/flags -> protection -> GUI -> home/visit -> restart flow.

### SHOULD FIX BEFORE FIRST RELEASE

- `/plot home` and `/plot visit` choose the first owned region without a selector; this becomes ambiguous for owners with multiple plots or worlds.
- Reset preserves progress/correlation and detects interrupted or partial work, but cannot automatically resume or undo. Recovery currently requires operator inspection and a separate manual action.
- Merge/unlink compensation is best effort. A crash or partial world mutation is shown as `RECOVERY_REQUIRED`; there is no durable block snapshot or dedicated repair command.
- Role, flag, limit, profile, component, and REST mutations do not all share one actor-aware `OperationResult`/authorization/audit lifecycle. Review direct GUI/REST service paths for consistent authorization, immediate runtime invalidation, and actor attribution.
- The import implementation is unregistered and lacks safe Area-aware, restartable import semantics. Do not advertise it until a dedicated import path is wired and tested.
- `SPAWNER_USE` and `SPAWNER_TAKE_DROPS` remain identifiers without active handlers; they are not first-release protection support.
- Ownership transfer has no active user entry point and is explicitly excluded from the first release.
- Reset/unlink user feedback is currently English in parts of the async failure path; localize operation completion and recovery instructions consistently.
- Existing-world conversion requires FAWE. Keep that limitation explicit in setup help and installation docs; new-world setup and core protection work without FAWE.

### SAFE POST-RELEASE IMPROVEMENT

- Make REST mutation responses and audit records uniformly expose operation correlation and structured result codes.
- Add a region selector to home/visit and a public API path using stable World/Area IDs.
- Add broader Paper listener integration coverage for multi-place, piston, liquid, hopper, dispenser, explosion, environment, entity, vehicle, and entry boundaries.
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

## First-release scope decision

This table classifies the intended first-release surface; it does not declare that release ready. The connected-player workflow remains a blocker.

| Function or integration | First-release scope | Current evidence / boundary |
|---|---|---|
| Claim, auto-claim, merge, unlink, reset | `REQUIRED / SUPPORTED` | Active Paper operations; merge waits for world mutation; mutation jobs record restart recovery state. Real player behavior still needs E2E verification. |
| Home, visit, roles, entry-deny, flags, limits, profiles, protection | `REQUIRED / SUPPORTED` | Active command, GUI, REST, or event paths; no player workflow was completed in this pass. Home/visit remains ambiguous for multiple plots. |
| PostgreSQL, MariaDB, SQLite | `REQUIRED / SUPPORTED` | PostgreSQL reference; MariaDB production alternative; SQLite local/single-server option. All three runtime test cases passed. |
| GUI | `REQUIRED / SUPPORTED` | Registered and command-accessible; inventory click/navigation behavior remains part of the blocked player E2E gate. |
| Components and LiftLink | `EXPERIMENTAL` | Existing active paths and persistence exist, but player-driven operations were not verified here. |
| REST | `EXPERIMENTAL` | Existing routes and DB runtime coverage exist. It has no claim/merge/unlink/reset routes, and its token-authenticated admin mutations are not per-user authentication. Bind failure disables REST while core remains active. |
| WebUX | `EXPERIMENTAL` | Read-oriented live API views; no full plot-management workflow. |
| Economy and ratings | `FUTURE / NOT EXPOSED` | Claim cost is zero in the active command path; buy/sell return HTTP 501; mock economy is test-only; no rating submission entry point was found. |
| Ownership transfer, import, spawner actions | `NOT EXPOSED YET` | No active transfer/import entry point or spawner handler. Existing low-level code and action identifiers are not support claims. |
| MySQL | `EXPERIMENTAL / UNSUPPORTED` | Connector is packaged, but no end-to-end dialect verification has been done. |

## Current readiness conclusion

This checkout is **FIRST-RELEASE-BLOCKED**. The connected-player end-to-end workflow remains unverified because no compatible Paper 26.3 client was available. The merge false-success path is fixed and covered by asynchronous success/failure, persistence-failure, compensation, and correlation tests. Reset and merge/unlink interruption state is durably reported as `RECOVERY_REQUIRED`, but there is no automatic resume or persistent undo. PostgreSQL and MariaDB audit target queries passed; the full build had no skipped tests.
