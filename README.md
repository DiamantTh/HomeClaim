# HomeClaim

HomeClaim ist ein modulares Plot-/Regionen-Framework fuer Minecraft-Server. Der Core ist bewusst schlank (Regionen, Rollen, Policy-/Flag-Engine, Zonen, Events) und unterliegt der GNU AGPL v3. Module koennen darauf aufbauen - erste Referenzen sind eine UX-Schicht und LiftLink (Elevator-/Teleport-Pads).

## Doku-Aufteilung

- Kurzdoku (diese README): schneller Einstieg, Build, wichtigste Links.
- Erweiterte Doku: `docs/README_ADVANCED.md`.
- REST API: `docs/api/README.md` und `docs/api/openapi.yaml`.

## Module
- `homeclaim-core`: Datenmodelle, Service-Schnittstellen, Policy-Grundlagen.
- `homeclaim-ux`: Referenz-Wiring fuer In-Game-/Web-UX, nutzt Core-Services.
- `homeclaim-liftlink`: Bewegungs-Komponenten (Elevator-/Teleport-Pads) auf Basis des Core-Component-Service.
- `homeclaim-platform-paper`: Paper/Folia-Adapter fuer MC 1.21.5+.
- `docs/db/schema.sql`: SQL-Schema fuer Postgres/MariaDB/SQLite-lesbar (Regionen, Zonen, Komponenten, Rollen, Flags/Limits, Audit).

## Quickstart

### Build

```bash
./gradlew :homeclaim-platform-paper:shadowJar
```

### Tests

```bash
./gradlew test
```

### Setup im Spiel

```text
/homeclaim setup
```

- Paper: Welt erstellen; Konvertierung mit FAWE moeglich.
- Folia: Welt erstellen unterstuetzt; Konvertierung deaktiviert.

## Wichtige Links

- Erweiterte Architektur-, Config- und Betriebsdoku: `docs/README_ADVANCED.md`
- API Doku: `docs/api/README.md`
- OpenAPI: `docs/api/openapi.yaml`
- DB Schema: `docs/db/schema.sql`
- Lizenz: `LICENSE`

## Lizenz
HomeClaim Core steht unter der GNU Affero General Public License v3 (siehe `LICENSE`). Module sollten kompatible Lizenzen nutzen.
