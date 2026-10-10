# HomeClaim — Architecture and Development Contract

This file is durable guidance for Codex and other coding agents working in this repository. It describes the observed source state separately from architectural requirements and optional future ideas. It is not authorization to implement every direction mentioned here.

For every concrete task, inspect the current implementation and its connected modules first. Source code, tests, migrations, runtime configuration, and recent repository history are stronger evidence of current behavior than older documentation. Keep changes within the requested scope.

## Product intent

HomeClaim is intended to become a modern Minecraft plot, region/area, protection, policy, and integration system. It should use sound ideas from PlotSquared, WorldGuard, and Protect as references, without copying their implementations or assuming that one product's behavior is the right behavior for HomeClaim.

- PlotSquared is a reference for plot workflows such as claims, trust/member/deny, merge/unlink, reset, backups, generators, and roads.
- WorldGuard is a reference for protection coverage, edge cases, cross-boundary mechanics, and cause handling.
- Protect is a reference for modern APIs, typed/namespaced flags, region abstractions, services, and external integrations.

HomeClaim is not intended to be a PlotSquared clone, a general GIS framework, a mandatory wrapper around another protection plugin, or a server-fork patch set.

## Current State — observed in this checkout

This section records the repository state observed after architecture Steps 1–5 in the current checkout. It is not a promise that every feature is production-complete. Re-check source, wiring, tests, and packaging before relying on these details.

- The repository is a multi-module Gradle/Kotlin project. Included modules are `homeclaim-core`, `homeclaim-api-client`, `homeclaim-api`, `homeclaim-ux`, `homeclaim-webux`, `homeclaim-liftlink`, and `homeclaim-platform-paper`.
- The root build targets Java/JVM 25 with Kotlin 2.3.21 and Gradle 9.1. The Paper module compiles against Paper API `26.3.build.+`; `paper-plugin.yml` declares API 26.3, a `PluginBootstrap`, and the `JavaPlugin` runtime. Commands register Brigadier trees through `LifecycleEvents.COMMANDS`. Service construction and shutdown still run in the plugin runtime lifecycle. There is no active Folia support path or `folia-supported` metadata.
- The Paper module contains listeners, commands, GUI code, lifecycle wiring, a TOML configuration loader, plot-world setup/initialization, database wiring, and REST integration. The repository also has YAML examples and older docs; verify which configuration path is active before changing configuration behavior.
- Plot/region data already exists in `homeclaim-core`: `Region`, `Plot`, `Zone`, `Position`, `Bounds`, roles, flags, limits, components, merge groups, and entry-deny models/services. `WorldId` and `AreaId` are UUID-backed domain identifiers. `HomeClaimWorld` stores an optional Paper UUID binding and display name; `Area` owns a whole-world or finite spatial scope and priority. Regions and zones resolve canonically through `area_id`; legacy world-name fields remain for compatibility. The consolidated pre-release V1 creates `worlds` and `areas`; startup backfills legacy region/zone world names idempotently.
- A zero UUID is currently used as a system actor in the Paper plugin, while the region owner model is a player UUID. Treat that as legacy debt; do not spread fake-player/system ownership patterns into new designs.
- The current policy path includes `PolicyService`, `SimplePolicyService`, `RegionPolicy`, `PolicyGuard`, a typed `ActionRequest`, actor/endpoints/resource references, namespaced `ActionKey` values for every built-in action, flag profiles, mod-actor defaults, and entry-deny checks. The closed `Action` enum remains as the implementation and compatibility mapping; unregistered keys do not gain policy behavior. `PolicyActionRequest` is still used by some Paper event adapters and translated by `PolicyGuard`; `SimplePolicyService` still consumes a legacy `Map<String, Any?>` context, so the typed envelope is not yet a fully typed end-to-end evaluator. Decisions carry `ALLOW`, `DENY`, `INHERIT`, or `NOT_APPLICABLE`, a source, an action request, and a small trace; the Boolean `allowed` is a compatibility projection (`NOT_APPLICABLE` remains allowed to legacy listeners). HomeClaim roles are resolved from `RegionRoles`; `PlotMemberRole` is translated to that vocabulary, but `PlotMemberService` is not wired into Paper runtime. A missing Area is `NOT_APPLICABLE`; a missing Region inside an applicable Area remains a deny.
- Typed operation keys and `AuditEntries.operationResult` support structured operation audit. Paper plot command claim, merge, unlink, and reset entry points now reuse one request/correlation across their synchronous lifecycle records. Role changes from the active GUI and REST paths use the shared role service and emit a completed operation record. Flag/limit/profile administration still uses legacy audit factories without actor/correlation context, and ownership transfer is not exposed through the active plot command.
- A read-only `CapabilityPermissionProvider` now returns `GRANTED`, `DENIED`, or `UNDEFINED`; Paper supplies the live player adapter. Local explicit flag denials precede role and action capabilities, locked flags also constrain owners, and explicit protection/entry/locked-policy bypass capabilities are resolved centrally. `PlotMemberService` remains a separate legacy JDBC model and is not wired into the active platform services; its access helper now uses the same inherited role defaults, with MEMBER treated as permanent.
- `RegionOperationLimitService` centralizes configured claim and merge quotas for the active plot command path. Claim and merge now return typed `OperationResult` statuses for success, denial/applicability, invalid input, missing resources, quota exhaustion, conflicts, and execution failures. The old Boolean/exception adapters remain deprecated for compatibility. `homeclaim.limits.maxPlotsPerPlayer` and `homeclaim.limits.maxMergedRegions` default to `-1` (unlimited). Direct repository/service calls outside this adapter path do not automatically inherit those configured quotas.
- Paper protection is split into block, automation, entity, explosion, movement, and vehicle listeners behind a shared Paper adapter and the central policy guard. This is broad event coverage, not proof that every Minecraft mutation is protected. Inspect each current listener and its tests before changing behavior.
- The main HomeClaim, plot, and zone command trees use Paper's lifecycle command registration. `ImportCommand` and a generic Bukkit executor bridge remain as inactive legacy classes; verify registration before treating either as an active entry point. Active claim and merge mutations route through structured `RegionOperationLimitService` operations, while command-side target selection, presentation, and parts of authorization remain in `PlotCommand`; unlink, reset, transfer, and administration are not yet unified behind the same operation contract.
- Plot-world setup and recovery, reset/mutation planning, a Paper synchronous mutation backend, and job tracking exist. FAWE is optional at runtime; setup conversion specifically requires it. The default `runPaper` task no longer downloads FAWE automatically.
- The pre-release Flyway schema has one common initial migration (`V1__init.sql`) plus dialect-specific MariaDB/PostgreSQL repeatable scripts. These are not published upgrade history. The connection configuration recognizes MySQL and the plugin artifact includes Connector/J, but there is no MySQL-specific migration directory; verify the common baseline and UUID handling before describing MySQL as fully supported. `docs/db/schema.sql` is a broader reference and also contains REST account/authentication tables. Verify dialect behavior before persistence changes.
- `homeclaim-api` is a Ktor REST implementation; `homeclaim-api-client` is a separately published extension/client module. Its `Models.kt` still defines a separate string-valued `WorldId`, distinct from core's UUID-backed identity, and its action/component registries are separate extension points. Treat these as public compatibility contracts; do not silently substitute core types or claim that the API boundary has been fully consolidated. `homeclaim-webux` contains a Svelte frontend and server/template resources. Existing public API and REST behavior must be checked before changing models, routes, authentication, or module boundaries.
- The current source has JDBC repositories, in-memory implementations for some services, typed audit taxonomies, a structured audit lifecycle with correlation and source/target columns, a bounded asynchronous Paper audit writer, a PlotSquared import adapter, and tests across core, Paper setup/mutation/protection, UX, and LiftLink. Audit lifecycle is only partially wired: command claim/merge/unlink/reset and role edits have hooks, while flag/profile operations and asynchronous reset completion do not yet share one end-to-end operation service.
- The current plugin has LuckPerms-related permission nodes/documentation and a native Bukkit permission provider. Do not infer an implemented LuckPerms or PowerRanks API integration from permission nodes or docs alone.

Some files and types are experiments, extension points, or only partly connected to active runtime paths. Confirm callers, registration, configuration activation, and tests before describing a class or module as a working user-facing capability.

Some historical changelog and source-review text records earlier Folia code and Minecraft targets. Those references describe past repository states; the active Paper runtime no longer contains Folia detection or Folia scheduler/mutation adapters. Correct historical documents only when they claim those paths are still active.

## Architectural Direction — requirements for new or modified code

### Platform and dependencies

- Paper is the primary API and architecture contract. Purpur is an important real compatibility/test target.
- Long-term platform direction is Minecraft/Paper 26.2.x / 26.3.x and compatible later releases, Java 25, modern Paper APIs, `paper-plugin.yml`, `PluginBootstrap`, lifecycle APIs, Paper Command API/Brigadier, Adventure, and registry APIs.
- Java 25 / Paper 26.3.x are now the active build target. Do not add new Java 21 / Paper 1.21-specific code paths. Also do not migrate unrelated command/bootstrap architecture merely because a nearby file is touched.
- Prefer Paper API first. Do not add server-brand checks such as `if Purpur / else Leaf / else SparklyPaper` to shared code. Core code must never depend on server-brand detection. Fork-specific capabilities require an explicit task and an optional adapter.
- Avoid NMS while a suitable public API exists. New core APIs should not add dependencies on Bukkit/Paper or external plugin APIs.
- Leaf, Leaves, Gale, Pufferfish-line forks, SparklyPaper, and other Paper-compatible forks may be useful compatibility checks or sources of problem cases. SparklyPaper's world-affine work, hot-path, caching, and bulk-operation lessons can inform ordinary safe design; this does not mean reproducing its server patches or claiming support for its parallel world ticking. Leaves may expose vanilla/redstone compatibility cases; Leaf and Gale may be useful performance/behavior references. First check whether a reported fork issue reproduces on Paper or Purpur.
- Folia is not a support target. Do not add Folia-specific code, scheduler abstractions, runtime detection, compatibility promises, or `folia-supported: true`. The previously active Folia mutation and scheduler paths were removed during architecture Step 5; historical references may remain in changelog/source-review material.
- FAWE remains optional. A native Paper mutation path must remain available for ordinary HomeClaim operation; do not introduce a required FAWE dependency for claims or protection.
- LuckPerms, PowerRanks, WorldGuard, Protect, Nexo, Multiverse, and other plugins are optional integrations, never assumed dependencies. Use their official APIs only when an integration is explicitly requested. No such API belongs in `homeclaim-core`.

### Core and platform boundary

Keep `homeclaim-core` as platform-neutral as the existing design permits. Core must not directly depend on Bukkit `World`, `Player`, `Location`, `Plugin`, Paper events, Bukkit schedulers, or APIs from LuckPerms, PowerRanks, Protect, WorldGuard, Nexo, FAWE, or WorldEdit.

Preferred flow:

```text
Paper or external event -> adapter -> HomeClaim domain request
                         -> core service/decision -> platform execution
```

Inspect actual module dependencies before moving code. Core currently includes JDBC repository implementations and the public client module has registries separate from core registries; do not claim that these boundaries are already fully clean. Avoid module renames or broad moves for aesthetics alone. In particular, do not rename `homeclaim-api` to `homeclaim-rest` without a separate explicit task.

### World, area, region, and identity

Minecraft World and HomeClaim Area are different concepts. A world is a physical coordinate space; an Area says where HomeClaim is responsible. The target hierarchy is:

```text
Minecraft World
  -> zero or more HomeClaim Areas
       -> Regions / Plots
       -> Zones / Policies
```

A simple installation may have one world and one area. The architecture must also allow multiple areas in one world, such as a plot area beside wilderness or a farm area. Where no HomeClaim Area applies, HomeClaim is not responsible. The future policy vocabulary may represent this as `NOT_APPLICABLE`; do not replace this distinction with “known world means the whole world is protected.”

World names are display names and legacy lookup keys, not durable identity. The current domain and database use UUID-backed `WorldId` and `AreaId`, and Paper's stable world UUID (`World#getUID()`) is stored as an optional platform binding. Existing installations bind legacy names during the idempotent startup migration; Paper binds known worlds by UUID first and uses a name match only while no platform UUID is stored. Region and Zone use `AreaId` canonically while legacy world-name fields remain for compatibility.

Keep each world mutation explicitly associated with its world/resource. Avoid hidden cross-world operations. Cross-boundary mechanics must retain both source and target context where relevant (for example pistons, liquids, hoppers, explosions, dispensers, falling blocks, and vehicle movement).

### Persistence and migrations

The database is the canonical persistent store for HomeClaim domain state. Do not store canonical regions, plots, roles, policies, audit, or entitlements in Minecraft world folders or sidecar JSON/YAML files. Configuration files may still hold operator configuration.

Before persistence work, inspect all runtime migrations, dialect-specific SQL, repositories, schema docs, and compatibility behavior. Before the first stable release, follow the pre-release policy below: edit or consolidate the canonical initial schema rather than creating a longer history for unreleased states. After release, add forward migrations and do not recommend deleting and recreating a database for an ordinary upgrade. Preserve existing UUIDs, owners, roles, flags, limits, prices, merge groups, entry-denies, and plot data unless a task explicitly defines a migration or removal. Prefer stable IDs and foreign keys. Use JSON for flexible extension metadata, not for relations that need frequent filtering, joins, or referential integrity.

### Pre-release database policy

HomeClaim has not yet reached its first stable product release. Until a release is actually published, the repository's versioned Flyway files describe development states, not immutable customer upgrade history. Do not add `V6`, `V7`, or later files merely to preserve unreleased intermediate schemas. Put schema work into the canonical initial schema, and consolidate or replace pre-release migrations when that is the simpler consistent result. A development or test database may be recreated after an incompatible pre-release schema change; do not build compatibility code solely to migrate arbitrary local development data. Check the affected repository and every database dialect before changing the schema. Flyway remains useful for repeatable initialization and dialect checks. The pre-release baseline should converge on one clean initial schema plus only necessary dialect-specific repeatable setup. After the first published release, treat published migrations as immutable, add forward upgrade migrations, and test preservation of persistent server data. Do not infer that a release boundary has been crossed from a version string, tag in an unrelated checkout, or migration numbering; confirm that a stable release was actually published.

### Protection, policy, and hot paths

Protection runs on frequent event paths. Never perform a database query per block, movement, hopper, piston, liquid, explosion, redstone, or similar event. Treat the database as canonical persistence and use an appropriate runtime cache/index for hot-path resolution. A future spatial index may use `WorldId + chunkX + chunkZ -> candidate Areas/Regions`; that is a design direction, not a required task for every change. Invalidate runtime state when the corresponding domain data changes.

Do not assume that current listener coverage is complete. For protection changes, inspect the connected Paper events, adapter mapping, core decision, resource lookup, actor attribution, cancellation behavior, audit, and tests. Consider source and target for cross-boundary mechanics. Group or batch bulk events where practical; avoid repeating full policy work for every affected block when a shared evaluation is correct. Avoid `Map<String, Any?>` as a universal new hot-path context; prefer typed actor, resource, action, and source/target contexts when designing new contracts.

The active policy path separates actor capability from local resource policy: explicit resource DENY wins over ordinary permissions, and locked DENY also constrains owners. A Paper capability grant can satisfy an inherited rule, while a named bypass is recorded as the decision source. The active Decision model includes `ALLOW`, `DENY`, `INHERIT`, and `NOT_APPLICABLE`, with a legacy Boolean projection. Numeric claim/merge quotas are evaluated by `RegionOperationLimitService`, separately from policy flags. The broader policy matrix and complete action registry remain out of scope unless explicitly requested.

### Roles, permissions, and actions

HomeClaim roles are domain roles such as Owner, Trusted, Member, Visitor, and Banned. They are not external permission groups. Native Paper permissions and optional external permission providers answer capability questions; they do not replace HomeClaim ownership or role state.

Do not read/write external permissions through `/lp` or PowerRanks commands. Do not mirror effective LuckPerms/PowerRanks permissions into HomeClaim and do not automatically synchronize HomeClaim roles with external groups. Keep any future provider behind an adapter.

Actions should be extensible and namespaced (for example `homeclaim:block_break`, `minecraft:piston_move`, `nexo:furniture_break`) so addons can eventually contribute semantics. The current `ActionKey` maps built-in values to the existing closed `Action` enum; it is not a complete runtime registration system. Existing client/core registries are separate legacy extension points. Do not add a competing registry or implement a complete `ActionRegistry` without an explicit task. Public API changes should prefer typed values, stable keys, clear services, typed events, and explicit result objects over string conventions, universal maps, global Bukkit singletons, static service locators, or oversized classes.

### Audit and external constraints

Audit should preserve causality and HomeClaim-specific context over time: actor, action, world, area, region, role, policy, decision source, external constraints, and a correlation ID where applicable. The current lifecycle vocabulary distinguishes attempted, allowed, denied, completed, failed, and rolled back; `AuditEntry` keeps old category/action/payload fields while adding structured fields. The Paper runtime uses a bounded queue in front of its JDBC sink, reports queue rejection/write failure, and drains before database shutdown with a bounded timeout. Do not write every movement event to persistent audit; consider policy, sampling, and rate limits.

HomeClaim must protect its own areas. WorldGuard or Protect may later provide external constraints, but neither is mandatory and regions must not be duplicated into them automatically. An explicit deny may eventually win on intentional overlaps, but define semantics in the requested integration task. CoreProtect may provide optional history/rollback data, never HomeClaim's only audit source. Nexo actions likewise belong in an optional adapter, not core.

### Performance, concurrency, and bulk mutation

- Resolve protection through in-memory runtime state, not SQL or JSON work in hot paths.
- Avoid unnecessary UUID/string conversion, collection copying, global spatial scans, and generic allocation-heavy contexts in hot paths. Optimize measured paths rather than speculative microbenchmarks.
- Keep world ownership explicit for operations even on ordinary Paper/Purpur. This improves correctness and future resilience; it does not imply Folia or SparklyPaper support.
- Treat large resets, explosions, road restoration, and other multi-block changes as logical bulk operations where possible. Preserve clear operation state, cancellation, error handling, and audit behavior when working on existing mutation code.
- Reuse existing planners, mutation backends, and job services after inspecting their actual behavior. Do not build a second parallel mutation framework without a concrete need.

### Tests and compatibility

Core domain behavior should remain testable without a Minecraft server. Depending on the change, cover domain invariants, spatial resolution, migrations and repository behavior, policy decisions, cache invalidation, source/target crossing, and backwards compatibility. Paper-facing changes may need suitable adapter or integration coverage; a successful Gradle build alone is not proof of correct runtime behavior.

For a code change, inspect and run relevant existing tests when the task calls for verification and the environment permits it. Do not create unrelated tests or run broad suites solely because this file exists. Keep compatibility adapters or deprecated paths where they make a staged migration safer; remove them only as part of a deliberate migration.

### Runtime operation boundary

For user-facing mutations, keep adapter responsibilities separate from domain behavior. Paper commands, GUI actions, REST routes, and internal entry points should call the same domain operation for the same business action. Adapters parse input, establish a trusted actor/context, invoke the operation, and render its result. They must not invent their own role, policy, limit, validation, or persistence rules.

An operation should distinguish authorization from execution. A successful authorization is not proof that a world or database mutation completed. Use explicit result states where the operation can be denied, inapplicable, invalid, over limit, conflicting, or failed. Emit completion audit only after the mutation succeeds; record a failed execution separately from a denied request. Keep actor identity, action, resource, source/target, and correlation context when crossing adapter and operation boundaries. World mutation and database persistence are not one ACID transaction: follow the existing planner/job/recovery model, make partial failure visible, and do not silently report success while the two stores disagree.

Re-use the existing region, membership, policy, limit, mutation, audit, and job services after tracing the active runtime wiring. Keep narrow operation services by domain responsibility rather than making a single service own claims, roles, policies, resets, GUI, REST, and world mutation. Low-level repositories may remain available to infrastructure and migration code; user-facing business flows must not bypass their shared authorization, limits, validation, or audit path.

### Platform lifecycle and commands

Paper is the API contract. For explicitly requested platform modernization, prefer Paper's plugin metadata, bootstrap and lifecycle APIs, and lifecycle command registration. Keep early bootstrap work limited to work that is safe before normal server runtime services exist; create and close runtime services in the appropriate lifecycle phases. Avoid keeping duplicate or contradictory plugin metadata after a migration. Commands should parse and validate input at the adapter edge, call domain operations, and map structured operation results to messages. Do not copy a large legacy command dispatcher wholesale into a new command API. Registering a command or declaring a permission does not itself enforce the underlying business authorization.

Do not migrate every command, plugin hook, or module just because one platform lifecycle entry point changes. Preserve required integrations and aliases deliberately, and test registration and behavior on the Paper API target. Purpur compatibility should use the Paper API path and be checked with a real smoke test when the local environment supports it. Do not introduce Folia schedulers, detection, metadata, or support promises; remove existing Folia-only paths when the requested work includes their cleanup, while keeping ordinary Paper mutation behavior correct.

### Packaging and runtime dependencies

Validate the actual distributable plugin artifact, not only Gradle compile or test classpaths. Inspect the packaged metadata, Flyway resources, JDBC drivers for every database advertised as supported, Kotlin runtime, Jackson, connection pool, server/runtime libraries, and `META-INF` service resources. Decide intentionally which libraries are shaded and which are supplied by Paper; check relocation and duplicate-resource behavior. A dependency that appears only in a test or development configuration is not available to a production plugin. FAWE is optional: the native Paper path must support ordinary HomeClaim operation when FAWE is absent.

For database work, verify the runtime configuration and SQL against SQLite, PostgreSQL, and MariaDB/MySQL paths that the product actually claims to support. Do not report an unrun external database as tested. Use isolated local temporary databases or containers if available; never use a production database for verification. Keep migration resource locations aligned with runtime discovery and update schema documentation when it is part of the change.

### Web and REST behavior

REST status and response bodies must reflect the actual operation result. A route must not return success after a no-op, rejected, or failed mutation. Client-supplied bypass or authorization claims are untrusted; determine authorization on the server. Preserve existing public contracts when practical and map denied, invalid, missing, conflicting, limited, and execution-failure results distinctly.

WebUX must make loading, success, empty, unauthorized, and error states distinguishable. Do not silently replace a failed live API response with convincing fake/demo records in a normal connected product view. Keep demo data behind an explicit demo or preview mode if such a mode is intentionally retained. When a frontend is changed, inspect the actual `package.json` scripts and run the applicable npm checks with the checked-in lockfile.

## Planned / Expected Direction — only when explicitly tasked

These directions should inform designs for related, explicitly requested work, but do not authorize implementing them by themselves.

- Continue replacing legacy world-name and Boolean projections in public APIs with stable World/Area IDs and rich decisions, preserving compatibility where required.
- Extend the central role and policy path to any remaining command, GUI, REST, and legacy PlotMember workflows rather than recreating their decision rules in adapters.
- Expand the operation boundary beyond the active plot command path so every future claim/merge entry point shares authorization, limits, mutation, and audit; direct repository calls remain low-level and do not enforce user-facing quotas.
- Improve decision context types and policy inheritance incrementally; do not create a complete policy matrix or generic authorization framework without a concrete task.
- Extend protection mapping to cause-aware actions with source, target, world, resource, and correlation context. Cover cross-boundary and automation cases deliberately.
- Improve spatial runtime indexing and bulk evaluation only where actual load and current data structures justify it.
- Keep domain persistence relational and migration-based, with compatibility paths for existing installations.
- Clarify and stabilize the public addon API and bridge its extension points to the services actually used by the runtime.
- Continue modern Paper lifecycle and command migration only where concrete legacy runtime paths remain; the main plugin metadata and command trees already use Paper bootstrap/lifecycle registration, while the runtime entry point still extends `JavaPlugin`.

## Possible Future Ideas — Not Tasks

These items are not implementation tasks. Do not implement them unless a separate user/task request explicitly asks for them. They are listed only so current design choices do not needlessly block a possible later extension.

- Optional Dimension / Island / Space module and bounded player spaces or worlds.
- Slot and entitlement system, with limit sources such as defaults, permissions, permanent grants, temporary bonuses, economy, quests, votes, admin actions, or APIs.
- CoreProtect bridge and other external audit sinks.
- Protect or WorldGuard constraint providers.
- LuckPerms or PowerRanks permission provider adapters.
- Nexo action adapter and addon action registration.
- Fork-specific optional capability adapters.
- Richer bulk mutation and recovery system.
- Decision traces and an administrative `/hc explain` workflow.
- PlotSquared migration/import tooling beyond the existing importer.
- Optional generator and road modules.
- Richer public API for addons and external services.
- Paper/Purpur CI matrix and optional Leaf/Leaves/Gale compatibility CI.
- Additional external audit sinks.

Do not implement any item from this section unless the current user/task explicitly requests it.

## Explicit Non-Goals

- Do not make HomeClaim a PlotSquared clone or blindly copy another plugin's behavior.
- Do not build a general GIS engine, a new multiworld platform, a general-purpose permissions product, clans, a WorldGuard clone, full event sourcing, a distributed cluster database, or a universal dependency-injection framework without a concrete approved need.
- Do not add fork-brand detection throughout the codebase or promise support for every Paper fork.
- Do not make Folia a support target or preserve Folia complexity indefinitely just because it exists today.
- Do not require FAWE, LuckPerms, PowerRanks, WorldGuard, Protect, Nexo, CoreProtect, or Multiverse for ordinary operation.
- Do not rewrite the database, API, commands, web UI, protection, permissions, and audit together as a big-bang modernization.
- Do not infer that a feature is absent because README/docs omit it, or complete because a class/model/schema mentions it. Trace runtime wiring and tests.

## Do Not Assume

- one server == one world;
- one world == one HomeClaim Area;
- every loaded world is managed by HomeClaim;
- Multiverse, FAWE, LuckPerms, PowerRanks, WorldGuard, Protect, Nexo, or CoreProtect is installed;
- Folia is supported;
- a world name is an immutable identity;
- every owner is a player;
- every action has exactly one target;
- every protection action comes from a player;
- every decision is Boolean;
- every limit is a permission;
- every Paper fork behaves internally exactly like Paper;
- database access is acceptable in event hot paths;
- an existing model, API route, command, UI, or migration is fully wired just because its type/file exists;
- older docs describe the current source accurately.

## When Modifying Code

Before changing code:

1. Inspect the current implementation and all connected modules/adapters.
2. Inspect relevant tests and the behavior they actually establish.
3. Inspect migrations, repositories, and dialects when persistence is involved.
4. Inspect public APIs and callers when signatures or models may change.
5. Identify whether affected code is current architecture, incomplete wiring, experimental behavior, or legacy debt.
6. Identify existing workflows and compatibility that the change could affect.
7. Keep the requested scope; do not implement future ideas merely because this file mentions them.

After changing code:

1. Run relevant tests and a build when requested or appropriate to the task and environment.
2. List changed files and summarize behavioral changes.
3. Explain migrations and backward compatibility where relevant.
4. Mention remaining technical debt and ideas intentionally deferred.

Do not delete or rewrite working functionality without checking its callers, tests, persisted data, and migration path. Prefer incremental, modular, reversible changes.

## Development, Build, Sandbox, and Toolchain Contract

Use the toolchain that belongs to the project area being changed. HomeClaim's backend and Paper plugin use Java 25, Kotlin/JVM, the Gradle Wrapper, Paper API 26.2.x/26.3.x, and Gradle-managed Maven repositories. Related backend modules use Ktor, JDBC, Flyway, HikariCP, Jackson, and JUnit. The web frontend uses Node.js, npm, Svelte, TypeScript, and Vite. `homeclaim-webux/frontend/package-lock.json` establishes npm as its package manager: do not switch to pnpm or Yarn unless a separate task explicitly requests that change. Do not add, update, or replace dependencies just to make a local tool invocation easier.

Normal build and dependency caches are part of the expected development environment. Gradle normally uses `~/.gradle`, npm normally uses `~/.npm`, and Java/Kotlin tools use their configured JDK and Gradle caches. Gradle resolves Maven artifacts through the repositories declared by the project. Do not assume these locations or ordinary dependency repositories are blocked before checking the actual environment. Apply the same rule to Maven, Composer, pip/PyPI, or another package tool only in a project area that actually uses it.

If the current sandbox or host really prevents a tool from writing its normal cache or reaching its configured repository, identify that environment failure and use a suitable temporary cache when possible. For example, set `GRADLE_USER_HOME` to a writable temporary directory for that invocation. Keep source, configuration, schemas, build scripts, durable test data, and all other canonical repository files at their project paths. A temporary cache is an environment workaround; `/tmp` is not a project architecture or storage location. State clearly in the final report when such a fallback was needed.

Verify backend changes with `./gradlew build` and the relevant Gradle test tasks. For frontend changes, first read that frontend's `package.json` and run the scripts that it actually declares, normally `npm ci`, `npm run check`, and `npm run build` where those scripts exist. Do not invent script names, and do not run frontend installation or builds for a backend-only change. Respect the checked-in lockfile, Gradle Wrapper, version catalog, and existing build definitions. Do not upgrade toolchains or dependencies unless that is part of the requested work.

Report environment failures separately from code failures. Distinguish `SOURCE / COMPILE FAILURE`, `TEST FAILURE`, `DEPENDENCY RESOLUTION FAILURE`, and `SANDBOX / PERMISSION FAILURE`. A cache permission error is not a HomeClaim compile error. Call verification complete only when the required project toolchains actually ran; describe any blocked command and its observed output instead of saying that all checks passed.

## Git, Commit, and Existing-Branch Push Workflow

HomeClaim coding-agent work is performed directly in the existing local checkout and on the branch that is already checked out. Git history is part of the development workflow. Normal local Git use is expected, including `git status`, `git status -sb`, `git diff`, `git diff --check`, `git diff --cached`, `git add`, targeted `git restore`, `git log`, `git show`, `git branch --show-current`, `git rev-parse`, `git grep`, `git ls-files`, `git remote -v`, `git fetch`, `git commit`, and `git push` when the task authorizes it.

Do not create a new local or remote work branch for an agent task. Do not use `git switch -c`, `git checkout -b`, `git branch <new-name>`, or push a newly invented branch as a default workflow. Keep work on the existing checked-out branch. Do not assume that a remote or upstream is configured; inspect it.

Commits are allowed and are useful for coherent, reviewable changes. Group related source, schema, documentation, and test changes into meaningful units; do not make one commit per file or mix unrelated cleanup into a feature commit. Before every commit, inspect `git status`, the unstaged diff, and the staged diff. Stage only intended work. If the checkout already contains uncommitted changes from earlier steps, inspect and preserve all of them, then group them by meaningful boundaries where practical. Do not discard or accidentally stage pre-existing work as though it were disposable.

Avoid destructive Git operations that could lose work. Do not run `git reset --hard`, `git clean -fd`, `git clean -fdx`, `git checkout .`, or broad `git restore .` without a necessary, understood reason and a verified safe tree. Do not rewrite history, amend existing commits, rebase or squash existing work, filter history, or force-push merely to make the branch look cleaner. Never use `git push --force`, `-f`, or `--force-with-lease` unless a separate explicit instruction authorizes the specific operation.

When a task explicitly authorizes pushing, and useful commits have passed the relevant verification, push those commits to the existing configured upstream of the current branch. First inspect the branch, `git status -sb`, `git remote -v`, and `git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}'`. Fetch before the final push when a remote is configured, then inspect divergence. Prefer ordinary `git push` to the existing upstream; never create a branch name for the push. If a push is rejected, do not overwrite remote history. Fetch and inspect the incoming commits and divergence. Integrate only a clearly safe case without rewriting history; document or pause on a nontrivial conflict rather than guessing or losing others' work.

A normal local coding task does not imply a pull request, issue, review request, release, or tag. Perform those hosting actions only when a separate task explicitly asks for them and a suitable tool exists. Never claim a remote operation succeeded unless the command actually completed.

Use a concise commit subject that names the change. A large architecture task may use several commits when its parts are genuinely independent; the examples in task instructions are suggestions, not mandatory commit counts. It is also acceptable to leave changes uncommitted when a clean logical commit cannot be made. Before reporting completion, run `git status` and `git diff --check`; after commits, inspect a suitable `git log --oneline` range. After an authorized push, inspect `git status -sb` again and report current branch, remote, upstream, commit hashes and subjects, push target and result, and whether local and remote are synchronized. If no push was authorized or technically possible, state why without implying the remote changed.

## Verification Contract

For backend or Paper code, use the repository wrapper and run `./gradlew build --no-daemon` plus focused Gradle test tasks relevant to the changed behavior. Do not claim a complete build or test result unless those commands actually ran. For frontend changes, read `homeclaim-webux/frontend/package.json`, respect `package-lock.json`, and run `npm ci`, `npm run check`, and `npm run build` where those exact scripts exist. Do not run npm checks for backend-only changes.

For runtime-sensitive Paper changes, use an isolated Paper 26.3.x smoke instance when available and record whether startup, plugin enablement, command/listener registration, database setup, and shutdown were exercised. Purpur is an important compatibility target; test it when a compatible local artifact/runtime can be obtained without compromising the checkout. Verify the built plugin JAR for metadata, runtime dependencies, migration resources, and service files when packaging changes. Test SQLite and any advertised external dialects only with isolated local instances or containers; distinguish tested dialects from those blocked by missing local infrastructure.

Report `SOURCE / COMPILE FAILURE`, `TEST FAILURE`, `DEPENDENCY RESOLUTION FAILURE`, `SANDBOX / PERMISSION FAILURE`, and `RUNTIME / SMOKE FAILURE` separately. A sandbox cache permission or network restriction is not a source failure. Identify any temporary cache fallback and why it was required. A complete verification claim requires every required project toolchain to have run successfully; otherwise list the exact commands, results, and limitations.
