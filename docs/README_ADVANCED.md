# HomeClaim Advanced Guide

Diese Seite enthaelt die erweiterte technische Dokumentation.
Die Kurzfassung fuer Betreiber und Entwickler ist in der Root-README.

## Architektur (Core-first)

HomeClaim trennt Fachlogik von Plattform-Adaptercode:

- `homeclaim-core`: Modelle, Policy, Rollen, Events, abstrahierte Mutation-Backends.
- `homeclaim-platform-paper`: Paper/Folia-Adapter (Listener, Commands, Lifecycle).
- Spaetere Zieladapter: Fabric-first, NeoForge als Dummy-Start.

Aktueller Entkopplungsstand:

- `PolicyActionRequest` als plattformneutrales Policy-Mapping.
- `PolicyActorContext` + `ModPolicyDefaults` fuer modded-sichere Auswertung.
- `WorldMutationBackend`-SPI fuer FAWE-/non-FAWE-/Fabric-native Backends.

## Module

- `homeclaim-core`: Datenmodelle, Service-Schnittstellen, Policy-Grundlagen.
- `homeclaim-ux`: Referenz-Wiring fuer In-Game-/Web-UX.
- `homeclaim-liftlink`: Elevator-/Teleport-Pads.
- `homeclaim-platform-paper`: Paper/Folia Adapter fuer MC 1.21.5+.
- `homeclaim-api`: REST-Schicht.
- `homeclaim-webux`: SvelteKit-first Frontend.

## Konfiguration (Paper)

- Config: `homeclaim-platform-paper/src/main/resources/config.yml` (Beispiel: `config.example.yml`)
- Locale: `homeclaim.locale` (`en`, `de`, ...)
- Storage: JDBC (SQLite default, alternativ PostgreSQL/MySQL/MariaDB)
- Migrationen: Flyway via `homeclaim.migrations.*`
- REST: optional via `homeclaim.rest.enabled`

## Flags und Limits

- Baseline-Flags: BUILD, BREAK, INTERACT_BLOCK, INTERACT_CONTAINER, REDSTONE, COMPONENT_USE
- Schutz-Flags: FIRE_SPREAD, EXPLOSION_DAMAGE, PVP, MOB_GRIEF, ENTITY_DAMAGE, VEHICLE_USE
- Limits: COMPONENT_COOLDOWN_MS, ELEVATOR_RANGE_BLOCKS

## Modded-Sicherheit

Wichtige Core-Defaults fuer Nicht-Player-Akteure:

- Fake-Player, Automation, Server-Tasks und Entities sind standardmaessig restriktiv.
- Region-Metadata kann gezielt freigeben:
  - `mod.allow_fake_players`
  - `mod.allow_automation`
  - `mod.allowed_actor_ids`

## Build und Run

Plugin bauen:

```bash
./gradlew :homeclaim-platform-paper:shadowJar
```

Komplett bauen:

```bash
./gradlew build
```

Tests:

```bash
./gradlew test
```

## Plot-Welt Setup

Ingame Setup:

```text
/homeclaim setup
```

- Paper: Welt erstellen + Konvertierung mit FAWE moeglich.
- Folia: Welt erstellen unterstuetzt, Konvertierung deaktiviert.
- Setup-Grenzen im Wizard: Plotgroesse 16 bis 512, Strassenbreite 1 bis 128 und nicht groesser als die Plotgroesse, Hoehe 1 bis 319.

Manuelle Recovery bei einem unterbrochenen Erststart:

```text
/homeclaim plot init <welt>
```

- fuehrt die Plot-Initialisierung erneut aus
- ist idempotent ausgelegt
- fehlende Plot-Regionen werden beim Plugin-Start ebenfalls automatisch erkannt und nachgezogen

## Datenbank und Dialekte

- Generisches Schema: `docs/db/schema.sql`
- Dialekt-Erweiterungen:
  - `docs/db/dialects/postgres_extra.sql`
  - `docs/db/dialects/mariadb_extra.sql`
  - `docs/db/dialects/mariadb_extra_11.sql`

## REST API

Siehe detaillierte API-Doku in:

- `docs/api/README.md`
- OpenAPI: `docs/api/openapi.yaml`

## Lizenz

Core: GNU AGPL v3 (siehe `LICENSE`).
