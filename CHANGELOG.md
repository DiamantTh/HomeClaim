# Changelog

## 2026-01-25:23:08:53
- Weitere .po-Locales hinzugefuegt (zh_CN, ja, ko, hi) fuer Asien; I18n plural-handling fuer Slawisch/Französisch/Standard erweitert.

## 2026-01-25:23:04:51
- Gettext-Style I18n: .po-Support (msgid/msgid_plural), Locale aus Config; engl./de + neue .po fuer es/fr/pt_BR/ru.
- Tests gruen.

## 2026-01-25:22:28:17
- Locale konfigurierbar (`homeclaim.locale`), Messages aus ResourceBundles (en/de) geladen; Reload aktualisiert Bundle.
- Config/README entsprechend angepasst.

## 2026-01-25:22:24:52
- Logger-Texte in Resource-Bundle ausgelagert (messages.properties + messages_de.properties); Paper-Plugin nutzt msg()-Helper fuer Log/Command-Ausgaben.

## 2026-01-25:22:18:42
- Dialekt-abhängige Flyway-Locations: Postgres zieht automatisch GIN-Index-Migration (`db/migration/postgres`), MariaDB zieht Extras (`db/migration/mariadb`) mit Key- und Generated-Column-Indizes.
- README entsprechend aktualisiert.

## 2026-01-25:22:15:22
- MariaDB 11.x Extras hinzugefuegt: `docs/db/dialects/mariadb_extra_11.sql` mit Generated Columns/Indizes fuer haeufige Flags/Limits (BUILD/PVP/Explosion/Fire, Cooldown).
- README Dialekt-Extras ergaenzt (MariaDB 10.11, 11.x, Postgres).

## 2026-01-25:21:50:00
- LiftLink (Paper) erster Trigger: Pressure-Plates loesen Elevator-/Teleport-Pads aus, Policy-Check via PolicyGuard, Zielwahl per LiftLinkPlanner bzw. TeleportConfig (PAIR/HUB, Scope Region/MergeGroup).

## 2026-01-25:21:47:03
- Paket-/Namespace auf `systems.diath.homeclaim` umgestellt (alle Module, plugin.yml, Build-Gruppe).

## 2026-01-25:21:30:22
- REST erweitert um Region-Listing/Detail und Components-Listing pro Region (Paper-Bootstrap verkabelt Region/ComponentService).
- Tests gruen.

## 2026-01-25:20:48:16
- Dialekt-Extras dokumentiert: optionale Scripts fuer Postgres (GIN auf JSON) und MariaDB (JSON-Indizes, Beispiel Funktionsindex/Partition-Hinweis) unter docs/db/dialects.
- README ergaenzt um Dialekt-Extras; Schema/Migration bereits auf generische JSON-Spalten.

## 2026-01-25:20:38:02
- MariaDB/MySQL/SQLite breiter unterstuetzt: Schema/Migration auf generische JSON-Spalten umgestellt (kein jsonb), JDBC-Queries bereits cast-frei.
- README-Hinweis fuer Migrationen (MariaDB/Postgres/MySQL/SQLite) aktualisiert.
- Changelog-Sortierung korrigiert.

## 2026-01-25:02:13:00
- JDBC breiter kompatibel: JSON-Casts (`::jsonb`) entfernt, Flags/Limits-Replace loescht jetzt immer vor Insert (auch bei leerer Map), Components/Profiles/Zones nutzen neutrale JSON-Parameter.

## 2026-01-25:02:09:07
- PlatformServices traegt DataSource (fuer Migration-Command). `/homeclaim migrate` fuehrt Flyway-Migrationen manuell aus (JDBC-only).
- README um Commands (reload/migrate) aktualisiert; plugin.yml Permission bleibt `homeclaim.admin.reload`.

## 2026-01-25:02:07:12
- REST-Token kann nun aus Env (`tokenEnv`) oder Datei (`tokenFile`) geladen werden; Configs/README angepasst. tokenEnv/tokenFile werden respektiert, falls `token` leer ist.
- plugin.yml Permission auf `homeclaim.admin.reload` vereinheitlicht.

## 2026-01-25:00:44:19
- Reload unterstützt jetzt Storage-Konfig-Wechsel: erkennt Änderung, stoppt REST, deregistriert Listener und baut Services neu (InMemory/JDBC); Hinweis auf fehlende Datenmigration bei InMemory->JDBC.
- Storage-Konfiguration wird gemerkt; Listener werden bei Rebind entfernt (HandlerList.unregisterAll).

## 2026-01-24:23:10:56
- Commands vereinheitlicht: `/homeclaim` mit Alias `/hc`, Subcommand `reload` fuer Config/REST/Profiles; Permission `homeclaim.admin.reload`.
- Flyway-Migrationen eingebunden (Postgres auto, andere Dialekte optional via allowNonPostgres); Migration V1__init.sql als Resource, Config-Block `homeclaim.migrations`.
- README/Configs aktualisiert (Migrationen, Commands); plugin.yml angepasst.

## 2026-01-24:19:27:10
- REST: Health-Endpoint hinzugefuegt (status/version/storage), Token-geschuetzt; Rate-Limit weiter verfuegbar.
- Paper: Reload-Command `/homeclaimreload` (Config/REST/Flag-Profile neu laden; Storage-Wechsel erfordert Neustart); Flag-Profile in-memory koennen ersetzt werden.
- plugin.yml um Command ergaenzt; README um Reload/REST/Deploy-Hinweise erweitert.

## 2026-01-24:19:23:28
- README um REST-Config/Flag-Profile und Deployment-Hinweise ergaenzt (Schema, Config, Fat-Jar).

## 2026-01-24:19:17:51
- REST-Server um optionales Rate-Limit (Requests/Minute pro Token/IP) erweitert; Auth bleibt per Token. Config: homeclaim.rest.rateLimitPerMinute.
- Paper-Bootstrap gibt Rate-Limit/Token im Log aus; REST-Config-Blöcke in config.yml/config.example.yml ergänzt.

## 2026-01-24:19:07:29
- REST-Server kann nun direkt aus dem Paper-Plugin gestartet werden (optional, per config rest.enabled), mit Port/Token (X-Admin-Token) konfigurierbar; Stop auf Plugin-Disable.
- RegionAdminService in PlatformServices verdrahtet; JDBC nutzt FlagLimit-Repo/FlagProfile-Repo, In-Memory nutzt InMemory-Profile.
- config.yml/config.example.yml um REST-Block und Flag-Profile-Beispiele ergaenzt; Paper-Modul haengt homeclaim-ux fuer REST an.

## 2026-01-24:19:01:00
- REST-Server (homeclaim-ux) hinzugefuegt mit Endpunkten fuer Profile-Listing/Upsert, Region-Flag/Limits-Upserts und Profile-Apply; nutzt RegionAdminService.
- RegionAdminService implementiert (Upserts fuer Flags/Limits/Profiles, Apply Profile) inkl. JDBC-Unterstuetzung; FlagProfileService fuer JDBC/InMemory hinzugefuegt.
- config.example.yml ergaenzt um Flag-Profile-Beispiele, Default-config mit leerer flagProfiles-Liste; Bootstrap laedt Profile aus Config.

## 2026-01-24:18:36:08
- Flag-Profile laden aus config (flagProfiles) und werden in In-Memory oder JDBC registriert; Schema um `flag_profiles` erweitert; Beispielprofile in config.example.yml.
- Flag-Katalog erweitert (Fire/Explosion/PvP etc.), Listener-Checks (Fire/Explosion/PvP) mit Reason+Actionbar; Tests gruen.

## 2026-01-24:18:31:16
- Flag-Profile in README erwaehnt; Listener um Fire/Explosion/PvP-Checks mit Reason+Actionbar erweitert.
- Tests gruen.

## 2026-01-24:18:25:06
- Flag-Profile-Service (InMemory) hinzugefuegt; Policy kann Profile aus extraContext/Service mergen.
- Paper-Bootstrap nutzt FlagProfileService; Tests gruen.

## 2026-01-24:13:39:27
- Flag-Katalog erweitert (FIRE_SPREAD, EXPLOSION_DAMAGE, PVP, MOB_GRIEF, ENTITY_DAMAGE, VEHICLE_USE) und README mit Flag/Limits-Übersicht ergaenzt.

## 2026-01-24:13:23:08
- Policy/Reason erweitert: neue Reason-Codes (ROLE_REQUIRED, REDSTONE_DENY), Details fuer Cooldowns (wait_ms), Flag-Profile via extraContext/Service.
- Paper-Listener zeigt Reason + Detail auch als Actionbar; JDBC-Flag/Limit-Repo erhaelt Upsert-Methoden; Tests angepasst.

## 2026-01-24:06:00:11
- Build-Ausgabe zentralisiert unter `build/libs` mit Fat-Jar `homeclaim-platform-paper-0.1.0-SNAPSHOT.jar`; subproject buildDirs zeigen auf root/build.

## 2026-01-24:05:57:06
- Paper-Modul baut jetzt ein Fat-Jar per `./gradlew build` (`build/libs/homeclaim-platform-paper-0.1.0-SNAPSHOT.jar`), inklusive Core-Dependencies.
- Build haengt an fatJar; Tests gruen.

## 2026-01-24:05:53:43
- Paper-Listener zeigt nun Reason + Detail (z.B. Cooldown Restzeit).
- PolicyService liefert Detail fuer Cooldowns; Flag/Limit-Repo erhaelt Upsert-Methoden.
- Tests weiterhin gruen.

## 2026-01-24:05:50:13
- PolicyService erweitert: Komponenten-Cooldowns auf Basis von Limits, neue Reason COOLDOWN_ACTIVE; Component-Trigger liefert RegionId.
- Papier-Bootstrap warnt bei fehlenden JDBC-Credentials; Tests gruen.

## 2026-01-24:05:44:44
- JDBC-Region-Repo jetzt transaktional mit Replace von Rollen/Flags/Limits; dedizierter Flag/Limit-Repo hinzugefuegt.
- Role-Repo ersetzt vorherige Rolle vor Insert (Upsert-Light).
- Tests weiterhin gruen.

## 2026-01-24:05:40:42
- Konfiguration ausgelagert: Default `config.yml` (IN_MEMORY) plus voll kommentiertes `config.example.yml` mit Templates fuer Postgres/MariaDB/MySQL/SQLite.
- README verweist auf config.example.yml zum Kopieren/Anpassen.

## 2026-01-24:05:38:59
- Encoding-Option fuer JDBC (default UTF-8); JDBC-URL ergaenzt Parameter pro Dialekt.
- Warnung wenn sqliteFile gesetzt aber driver != sqlite; sqliteFile default wenn driver sqlite.

## 2026-01-24:05:36:38
- JDBC-Konfig vereinfacht: Driver-Kuerzel (postgres/mariadb/mysql/sqlite) und optionaler sqliteFile-Pfad fuer SQLite; README entsprechend angepasst.

## 2026-01-24:05:34:32
- driverClassName Beispiele (Postgres/MariaDB/MySQL/SQLite) in config.yml und README dokumentiert.

## 2026-01-24:05:34:04
- Storage-Config auf klassische Felder umgestellt (host, port, database, username, password, driverClassName); jdbcUrl entfernt.
- JDBC-URL wird nun im Paper-Plugin aus den Einzelwerten generiert (postgres/mariadb/mysql aware).
- README und Beispiel-config.yml angepasst; Tests weiterhin gruen.

## 2026-01-24:05:13:12
- Paper-Plugin konfigurierbar gemacht (config.yml): Storage-Typ IN_MEMORY oder JDBC; JDBC nutzt Hikari-DSL.
- Bootstrap waehlt passend In-Memory- oder JDBC-Services und erstellt PolicyService dynamisch.
- README um Konfigurationshinweis ergaenzt; Tests gruen.

## 2026-01-24:05:07:40
- JDBC-Region-Repo laedt/speichert nun Rollen, Flags und Limits; Region-Hydration ergaenzt.
- JDBC-Role-Repo hinzugefuegt; Component-Repo bereinigt.
- Jackson/Hikari-Dependencies eingebunden; Tests laufen gruen.

## 2026-01-24:04:35:30
- JDBC-Repository-Skelette fuer Regionen, Komponenten und Zonen hinzugefuegt (UUID/JSONB, Chunk-Querying), plus Hikari-DSL fuer DataSource-Setup.
- Jackson-Kotlin-Mapper und HikariCP als Dependencies ergaenzt.
- SQL-Schema-Doku weitergefuehrt (Postgres/MariaDB/SQLite-lesbar).

## 2026-01-24:04:18:33
- Paper-Plugin bootstrapped jetzt per Default In-Memory-Services (Region/Component/Zone Stores) und SimplePolicyService inkl. Rollenaufloesung aus Region-Rollen.
- README um Hinweis auf SQL-Schema (`docs/db/schema.sql`) ergaenzt.

## 2026-01-24:04:14:08
- DecisionReason-Codes ergaenzt, SimplePolicyService liefert differenzierte Gruende (ALLOWED/NO_REGION/FLAG_DENY/ROLE_BANNED).
- Policy-Tests erweitert (Locked-Flags, Reason-Checks), UX-Test angepasst.

## 2026-01-24:04:12:14
- Policy-Katalog und simple PolicyService-Implementierung hinzugefuegt (Zonen/Regionen-Merge, Rollenaufloesung-Hook, Basis-Flags fuer Build/Break/Interact/Component).
- In-Memory-Stores fuer Regionen, Komponenten und Zonen mit Chunk-Indizes erstellt; RegionService/ComponentService erweitert.
- DB-Schema (Postgres/MariaDB/SQLite-lesbar) in `docs/db/schema.sql` abgelegt mit Tabellen fuer Regionen, Rollen, Flags/Limits, Zonen, Komponenten, Audit.
- Tests fuer PolicyService ergaenzt; bestehende Tests angepasst.

## 2026-01-24:03:50:07
- README/Doku auf Zielversion MC 1.21.5+ klargestellt (Paper/Folia-Adapter kompiliert gegen 1.21.5; Ziel >= 1.21.5).

## 2026-01-24:03:47:15
- Toolchain auf Java 21 angehoben und Kotlin JVM-Target angepasst.
- Paper-Adapter auf MC 1.21.5 (compileOnly `paper-api:1.21.5-R0.1-SNAPSHOT`) umgestellt; plugin.yml api-version 1.21.
- README aktualisiert (Java 21, Paper-Zielversion 1.21.5+).

## 2026-01-24:03:46:00
- Plattformneutrale Adapter-Schicht im Core hinzugefuegt (Event-Kontexte, PolicyGuard, ComponentTriggerHandler) fuer wiederverwendbare Listener-Logik.
- Paper/Folia-Modul `homeclaim-platform-paper` angelegt mit Bridge, Listenern und plugin.yml, um Policy-Checks gegen Paper-Events abzubilden.
- Maven-Repository auf repo.papermc.io umgestellt und Paper-API als compileOnly (1.20.4-R0.1-SNAPSHOT, Java 17) eingebunden.
- README um das neue Plattform-Modul ergaenzt; Settings um das Modul erweitert.

## 2026-01-24:03:40:02
- Initial Projekt-Setup mit Gradle 9.2.1 (Wrapper), Kotlin/JVM 17, zentralen Dependenzen und Testkonfiguration.
- Mehrmodul-Struktur angelegt: `homeclaim-core`, `homeclaim-ux`, `homeclaim-liftlink`.
- Core-Domainmodelle und Service-Interfaces skizziert (Regionen, Zonen, Komponenten, Policies, Rollen).
- Erste Referenzlogik und Tests hinzugefuegt (RegionRoles-Resolver, LiftLink-Elevator-Suche, UX-Delegation).
- AGPL-Lizenz und README mit Projekt- und Build-Hinweisen ergaenzt.
