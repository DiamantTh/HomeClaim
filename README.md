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

**Welt-Generierung aus Repo-Root:**
```bash
./generate-world.py --name MyWorld
```

**Explizit mit Standardwerten vorbelegen:**
```bash
./generate-world.py --name MyWorld --use-defaults
```

Hinweis: Das Script ist bewusst CLI-only und steuert keinen Ingame-Setup-Wizard.
Es erzeugt ein Welt-Bundle mit `bukkit-world.yml`, `<worldName>.toml` und `worldgen-options.json`.
Zusätzlich wird automatisch `copy-to-server/` mit einer kopierbaren Zielstruktur erzeugt:
- `plugins/HomeClaim/plot-worlds/<worldName>.toml`
- `plugins/HomeClaim/config.yml`, `config.example.yml`, `sensor-config.toml`
- `plugins/HomeClaim/scripts/generate-world.py`
- `config/HomeClaim.toml`
- `bukkit-world.yml` als Merge-Snippet

Mit `--use-defaults` wird zusätzlich `plugins/HomeClaim/config.yml` bereits mit den Plot-Standardwerten für die erzeugte Welt vorbelegt.

Standard-Ausgabe: `./generate-world.py-data/<worldName>/...`

Ohne explizite Pfade (`--data`/`--worlddata`) ist das Script nur in zwei Kontexten erlaubt:
- Server-Root (`server.properties` + `bukkit.yml` vorhanden)
- `plugins/HomeClaim/scripts`

Werden Server-Einstellungen erkannt (z. B. `level-name` oder vorhandene HomeClaim-Welt in `bukkit.yml`),
fragt das Script interaktiv, ob diese übernommen werden sollen. Für non-interactive Nutzung: `--yes`.

Außerhalb dieser Orte bitte explizit setzen:
```bash
./generate-world.py --name MyWorld --data /pfad/zum/plugins/HomeClaim --worlddata /pfad/zu/output
```

**CLI-Import (server-einsatzbereit):**
```bash
# 1) Bundle erzeugen
./generate-world.py --name MyWorld --output-dir ./out

# 2) vorbereitete Struktur in den Server-Ordner kopieren
cp -r ./out/MyWorld/copy-to-server/* /pfad/zum/server/

# 3) bukkit-world.yml in /pfad/zum/server/bukkit.yml mergen
#    (Eintrag: worlds.MyWorld.generator: HomeClaim)

# 4) Server neu starten
```

Automatisch (inkl. Merge) geht es mit:
```bash
python plugins/HomeClaim/scripts/generate-world.py --name MyWorld --apply
```
Dann schreibt das Script die TOML direkt nach `plugins/HomeClaim/plot-worlds/` und merged
`bukkit.yml` auf `worlds.<name>.generator: HomeClaim` (inkl. `bukkit.yml.bak.<timestamp>` Backup).

Preview ohne Schreibzugriff:
```bash
python plugins/HomeClaim/scripts/generate-world.py --name MyWorld --apply-dry-run
```

Ausführliche Logs:
```bash
python plugins/HomeClaim/scripts/generate-world.py --name MyWorld --apply-dry-run --verbose
```

Beim Start lädt Paper/Folia dann die Welt über den Generator `HomeClaim`,
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
2. Plot-Welt erstellen: entweder per CLI-Bundle-Import (oben) oder `/homeclaim setup` im Spiel
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
