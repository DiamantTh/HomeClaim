# HomeClaim

HomeClaim ist ein modulares Plot-/Regionen-Framework fuer Minecraft-Server. Der Core ist bewusst schlank (Regionen, Rollen, Policy-/Flag-Engine, Zonen, Events) und unterliegt der GNU AGPL v3. Module koennen darauf aufbauen - erste Referenzen sind eine UX-Schicht und LiftLink (Elevator-/Teleport-Pads).

## Module
- `homeclaim-core`: Datenmodelle, Service-Schnittstellen, Policy-Grundlagen.
- `homeclaim-ux`: Referenz-Wiring für In-Game-/Web-UX, nutzt Core-Services.
- `homeclaim-liftlink`: Bewegungs-Komponenten (Elevator-/Teleport-Pads) auf Basis des Core-Component-Service.
- `homeclaim-platform-paper`: Paper/Folia-Adapter fuer MC 1.21.5+ (kompiliert gegen 1.21.5), der Policy-Checks aus dem Core ueber Listener abbildet und fuer weitere Plattformen erweiterbar bleibt.
- `docs/db/schema.sql`: SQL-Schema fuer Postgres/MariaDB/SQLite-lesbar (Regionen, Zonen, Komponenten, Rollen, Flags/Limits, Audit).

## Konfiguration (Paper)
- Config: `homeclaim-platform-paper/src/main/resources/config.yml` (Beispiele in `config.example.yml`)
- Locale: `homeclaim.locale` (z.B. `en`, `de`) steuert messages.properties Auswahl.
- Storage: Nur `JDBC` verfügbar (SQLite Standard, alternativ PostgreSQL/MySQL/MariaDB)
	- SQLite (Standard): `driver: SQLITE`, `sqliteFile: "plugins/HomeClaim/homeclaim.db"`
	- PostgreSQL/MySQL/MariaDB: `host`, `port`, `database`, `username`, `password`, `driver` (postgres/mariadb/mysql)
- Bootstrap: Plugin nutzt JDBC-Services; PolicyService nutzt Core-Merge-Logik.
- Flag-Profile: `homeclaim.flagProfiles` erlaubt vordefinierte Flag-/Limit-Sets beim Start.
- REST (optional): `homeclaim.rest.enabled=true` startet Ktor-Server (`/profiles`, `/regions/{id}/*`), Token via Header `X-Admin-Token`.
- Migrationen: `homeclaim.migrations.enabled=true` führt Flyway automatisch aus (V1__init.sql kompatibel für alle DBs).
- Commands: `/homeclaim reload` lädt Config neu, `/homeclaim migrate` für manuelle Migrationen.

## Flags (Auszug)
- Baseline: BUILD, BREAK, INTERACT_BLOCK, INTERACT_CONTAINER, REDSTONE, COMPONENT_USE.
- Schutz-Flags: FIRE_SPREAD, EXPLOSION_DAMAGE, PVP, MOB_GRIEF, ENTITY_DAMAGE, VEHICLE_USE.
- Limits (Auszug): COMPONENT_COOLDOWN_MS, ELEVATOR_RANGE_BLOCKS.
- Flag-Profile: koennen per extraContext oder Service eingespielt werden (z.B. Profile fuer “deny-build” o.a. Defaults).
- Optional REST (homeclaim-ux): Ktor-Server mit `/profiles` (GET/POST), `/regions/{id}/applyProfile`, `/regions/{id}/flags`, `/regions/{id}/limits` via RegionAdminService.

## Entwicklungs-Setup
- Java 21 Toolchain (Gradle konfiguriert automatisch; Ziel: MC 1.21.5+)
- Gradle Wrapper 9.2 verwenden: `./gradlew`
- Kotlin 2.2.x, JUnit 5.10.x

### Frontend-Status
- `homeclaim-webux` wird ab jetzt **SvelteKit-first** weiterentwickelt.
- Die bestehende `Pebble`/`Bootstrap`-UX bleibt nur noch als Fallback für Legacy-Seiten bestehen.
- Der neue Frontend-Workspace liegt unter `homeclaim-webux/frontend`.

**SvelteKit entwickeln/builden:**
```bash
cd homeclaim-webux/frontend
npm install
npm run dev
# oder für das Plugin-Bundle:
npm run build
```

Nach einem Build wird die App unter `http://<host>:8081/app` ausgeliefert.

### Build & Deployment

**Plugin bauen:**
```bash
# Shadow JAR erstellen
./gradlew :homeclaim-platform-paper:shadowJar

# Output: build/out/HomeClaim.jar (ca. 39 MB)
```

**Kompletter Build mit Tests:**
```bash
./gradlew build
```

**Clean Build:**
```bash
./gradlew clean build
```

### Plot-Welt Setup (Ingame)

Der bisherige CLI-Weg für den World-Setup ist entfernt. Die Einrichtung läuft jetzt über Minecraft direkt:

```text
/homeclaim setup
```

#### Verhalten nach Plattform
- **Paper**: neue Plot-Welt erstellen; Konvertierung bestehender Welten mit **FAWE** möglich
- **Folia**: neue Plot-Welt erstellen **unterstützt**; Konvertierung bestehender Welten bleibt **deaktiviert**

Der Wizard nutzt jetzt **plattform-optimierte Standardwerte**:
- **Paper**: ausgewogene Defaults für normale Plot-Server
- **Folia**: kleinere Standard-Welt und reduzierte Plot-Anzahl pro Seite für weniger Last bei Erstellung und Initialisierung

Der Wizard speichert die Plot-Konfiguration, setzt bei Bedarf den Generator-Eintrag und erstellt die Welt passend zur Plattform.
Beim Start lädt Paper/Folia die Welt dann über den Generator `HomeClaim`,
und HomeClaim liest die Plot-Parameter aus `plugins/HomeClaim/plot-worlds/<worldName>.toml`.

**Plugin installieren:**
```bash
# JAR kopieren
cp build/out/HomeClaim.jar /pfad/zum/server/plugins/

# Server starten
cd /pfad/zum/server
java -Xmx2G -jar paper.jar nogui
```

**Erste Schritte nach Installation:**
1. Server starten → `config.yml` wird automatisch erstellt (SQLite Standard)
2. Plot-Welt im Spiel mit `/homeclaim setup` erstellen
3. Plot claimen: `/plot claim` (funktioniert beim Stehen & Fliegen)
4. Plots bleiben nach Server-Neustart erhalten (SQLite-Persistenz)

### Deployment-Hinweise
- **SQLite (Standard):** Keine manuelle Schema-Einrichtung nötig, Flyway migriert automatisch
- **PostgreSQL/MySQL/MariaDB:** Flyway migriert automatisch beim ersten Start
- **Manuelles Schema:** `docs/db/schema.sql` falls Flyway deaktiviert ist
- Config: `homeclaim.storage` auf DB-Typ setzen, `homeclaim.flagProfiles` für Defaults
- Test: Flags/Profiles testen, Listener-Feedback prüfen (Build/Break/Redstone/PvP)

### Dialekt-Extras (optional, manuell)
- Postgres: `docs/db/dialects/postgres_extra.sql` (GIN-Indices auf JSON) einspielen.
- MariaDB 10.11+: `docs/db/dialects/mariadb_extra.sql` (Key-Indizes, optionale JSON-Funktionsindizes; keine Partition).
- MariaDB 11.x+: `docs/db/dialects/mariadb_extra_11.sql` (Generated Columns/Indizes fuer BUILD/PVP/Explosion/Fire, Cooldown-Limit).
- MySQL/SQLite: generisches Schema nutzen; JSON1/Generated-Columns nur bei Bedarf anpassen.

Flyway-Extras (auto per Dialekt):
- Postgres: `classpath:db/migration/postgres` (GIN-Indizes) wird automatisch eingebunden.
- MariaDB: `classpath:db/migration/mariadb` (Key-Indizes + Generated Columns) wird automatisch eingebunden.

## Lizenz
HomeClaim Core steht unter der GNU Affero General Public License v3 (siehe `LICENSE`). Module sollten kompatible Lizenzen nutzen.
