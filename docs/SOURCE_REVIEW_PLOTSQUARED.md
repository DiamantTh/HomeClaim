# HomeClaim: Source-Prüfung und Vergleich mit PlotSquared

Stand: 09.10.2026. Geprüfter lokaler Commit: `90e8825` (14.05.2026).

**Historischer Snapshot:** Diese Prüfung beschreibt den Checkout vor den Architektur-Schritten 1–5. Zahlen, Befunde, Zeilenverweise und Folia-/Migrationsaussagen beziehen sich auf den damaligen Source-Stand und sind keine aktuelle Bestandsbeschreibung. Seitdem wurden World/Area, Policy, Actions, Protection, Plugin-Metadaten, Paper-Commands, Runtime-Packaging und das Initialschema überarbeitet; Folia-Adapter wurden aus dem aktiven Paper-Modul entfernt. Für aktuelle Aufgaben gilt der Source und die Root-`AGENTS.md`.

## Ergebnis

HomeClaim besitzt bereits einen umfangreichen Plot- und Regionenkern. Weltgenerator, Claim/Auto, Merge/Unlink, Reset-Jobs, Zonen, Rollen, Schutzentscheidungen, Entry-Deny, Staff-Force-Entry, Audit, REST und LiftLink sind im Code vorhanden. Dazu kommen ausgearbeitete, aber nur teilweise angebundene Account-, Social- und Webfunktionen.

Die größte Schwäche ist die fehlende Durchgängigkeit zwischen diesen Teilen. Besitz, Rechte, Metadaten, Datenbankschema, Weltänderungen und Rückmeldungen sind noch nicht überall aufeinander abgestimmt. Für einen öffentlichen CityBuild-Betrieb würde ich zuerst diese Verbindungen und verbindliche Grenzen stabilisieren.

Die vorhandenen sieben Module und die Mutation-Schnittstelle sind eine brauchbare Grundlage. Eine Neuimplementierung würde bereits vorhandene Funktionen unnötig ersetzen.

## Umfang und Grenzen der Prüfung

Untersucht wurden die eigenen Kotlin-Implementierungen und Tests aller sieben Module, die Svelte-/TypeScript-Seiten, die ältere Weblogik, SQL-Schemata und Migrationen, Builddateien, Pluginregistrierung, Konfiguration, relevante Dokumentation und jüngere lokale Commits. Bei Funktionen wurde der Weg von Einstieg über Service und Datenhaltung bis zur Durchsetzung geprüft. Generierte Dateien, Laufzeitdaten und mitgelieferte Drittanbieterbibliotheken wurden nicht als eigener Quellcode bewertet.

Die Bestandsaufnahme umfasst 218 Kotlin-/Svelte-/TypeScript-Dateien mit rund 27.700 Zeilen einschließlich Tests. Die Prüftiefe richtet sich nach dem Risiko; daraus folgt keine Garantie, dass jede mögliche Regression gefunden wurde.

| Modul | Dateien dieser Quellsprachen | Zeilen | Tatsächlicher Schwerpunkt |
|---|---:|---:|---|
| homeclaim-core | 94 | 9.687 | Region/Plot/Account-Modelle, Policy, Events, Services, JDBC und Test-Stores |
| homeclaim-platform-paper | 92 | 13.741 | Start/Wiring, Listener, Commands, GUIs, Generator, Jobs, Import, Lifecycle |
| homeclaim-api | 3 | 2.483 | Administrations-REST, DTOs, Metriken, Lizenz-Metadaten |
| homeclaim-api-client | 7 | 563 | Separat veröffentlichte Modelle, Events und Registries |
| homeclaim-webux | 18 | 974 | Svelte-Oberfläche und Ktor-Webmodul; ältere Templates zusätzlich |
| homeclaim-liftlink | 2 | 143 | Pad-Zielsuche und Test |
| homeclaim-ux | 2 | 145 | Kleine delegierende Serviceschicht und Tests |

Validierung:

- `./gradlew test`: erfolgreich, **44 Tests**, keine Fehler oder übersprungenen Tests. Core: 9, LiftLink: 1, Paper: 32, UX: 2. API, API-Client und WebUX besitzen keine Kotlin-Tests.
- `npm run check` im Frontend: **0 Fehler, 0 Warnungen**.
- Der erste Offline-Testversuch scheiterte an fehlenden gecachten Gradle-Plugins. Der anschließende normale Lauf war erfolgreich.
- `./gradlew :homeclaim-platform-paper:shadowJar`: erfolgreich; Gradle bewertet das vorhandene JAR als aktuell (`UP-TO-DATE`). Die Artefaktprüfung findet darin weder SQLite-/PostgreSQL-/MariaDB-/MySQL-Treiber noch Kotlin-Builtins.
- Ein JSON-Smoke-Test ausschließlich gegen dieses Plugin-JAR scheitert schon beim Schreiben einer Core-Datenklasse mit `AssertionError: Built-in class kotlin.Any is not found`.
- Ein direkter JSON-Roundtrip mit den frisch kompilierten Core-Klassen und aufgelösten Jackson-/Kotlin-Abhängigkeiten reproduziert einen Fehler: `PolicyValue.Bool(false)` wird zu `{"allowed":false}`, lässt sich aber nicht als `PolicyValue` einlesen (`InvalidDefinitionException`).
- Kein echter Paper-/Folia-Server, keine echte Datenbank und keine Lastsimulation gestartet. Betriebsfolgen der folgenden Codebefunde müssen durch passende Integrationstests bestätigt werden.

Der PlotSquared-Vergleich bezieht sich auf öffentlich zugänglichen Upstream-Code und offizielle Dokumentation. Es ist kein vollständiges Audit von PlotSquared. Für OPSucht wurden öffentlich dokumentierte Spielerfunktionen geprüft; ein privater Fork oder interne Zusatzplugins lagen nicht vor.

## Was tatsächlich vorhanden ist

| Bereich | HomeClaim-Stand | Einordnung gegenüber PlotSquared |
|---|---|---|
| Plotwelt und Setup | Eigener Generator, Materialthemen, Ingame-Wizard, Weltgrenze, Paper-Konvertierung mit FAWE | Vorhandenen Ansatz weiterverwenden; Layout- und Konvertierungsfehler schließen |
| Claim und Auto | Angeschlossen; Auto sucht das nächste freie registrierte Grundstück | Grundablauf vorhanden; Limits, Reservierung und belastbare Nebenläufigkeit fehlen im aktiven Weg |
| Home/List/Visit | Angeschlossen; Home und Visit verwenden jeweils den ersten gefundenen Plot | Auswahl mehrerer Plots, Alias-Ziele und individuelle Homes ausbauen |
| Merge und Unlink | Merge-Gruppe, Rand-/Straßenänderungen und Unlink vorhanden | Geometrie, Rechte auf Verbindungskorridoren und Jobabschluss vereinheitlichen |
| Reset/Clear | Paper-Batches, Folia-Chunkplanung, Joblimits, Cancel, Unload-Hooks vorhanden | Bestätigung, Wiederaufnahme, vollständiger Inhalt und Konfliktbehandlung stabilisieren |
| Rollen | RegionRoles und separates PlotMember-Modell vorhanden | Aktive Member-Semantik entspricht noch nicht der P2-Unterscheidung Online-Member/Permanent-Trust |
| Flags/Zonen | 12 katalogisierte Flags, zwei Limits, Profile, Zonen-Priorität und lockedFlags | Weniger Umfang als P2; vorhandene Durchsetzung und Typregeln zuerst korrigieren |
| Entry-Deny/Staff | Persistente UUID-/Alle-/Namensmuster-Regeln, Gründe, Report/Revoke, Move/Teleport-Checks; einmalige Force-Grants | Eigenständiger Ausbau; nicht pauschal als fehlender Deny-Schutz behandeln |
| Economy | Vault-/Essentials-/Mock-Adapter; Kauf-/Claimlogik im RegionService | Fachlogik existiert; REST-Kauf/-Verkauf und sichere Gesamttransaktion unvollständig |
| Alias/Beschreibung | Aktive Commands schreiben Region-Metadaten; zusätzlich PlotRepository | Zwei Speicherwege müssen abgestimmt werden |
| Likes/Bookmarks/Visits | Modelle und JDBC-Implementierungen vorhanden | Noch keine durchgehende aktive Spielerfunktion; erst anbinden und Schema angleichen |
| Bewertungen | RatingRepository, Migration und Anzeige in `/plot info` vorhanden | Kein aktiver Bewertungscommand oder Web-Schreibweg gefunden |
| Plot-GUI | Rollen-/Preis-/Flag-/Komponentenmenüs vorhanden, Manager registriert | Einstieg im aktiven Commandbaum nicht gefunden; Menüs vor Freischaltung reparieren |
| REST | Tokenpflichtige Admin-API optional gestartet; Listen, Detail, Flags, Profile, Entry und Metriken | Brauchbare Admin-Grundlage; einige Schreibantworten sind Platzhalter |
| WebUX | Svelte und ältere Pebble-Oberfläche, WebSocket-Modul vorhanden | Noch keine fertige Spieler-Webverwaltung; Paper startet dieses Modul nicht |
| Accounts/Auth | Passwort-, Session-, TOTP-, Magic-Link- und Credential-Repositories vorhanden | Teilweise Implementierung; nicht an den aktiven Web-/REST-Login angeschlossen |
| Erweiterungen | Core-Services, Events, MutationBackend und Registries vorhanden | Öffentliche Client-API noch nicht mit dem aktiven Kern verbunden |
| Import | PlotSquared-/WorldGuard-Extraktion und Importservice vorhanden, P2-Adapter getestet | Importcommand nicht registriert; Geometrie, Flags und Wiederholbarkeit unvollständig |
| Andere Plattformen | Plattformneutrale Requests und Actor-Modell vorhanden | Fabric/NeoForge sind dokumentierte Zieladapter; kein entsprechendes Modul gefunden |

PlotSquared bietet unter anderem detaillierte Befehle und Permissions, Claim-/Merge-Grenzen, Plotwechsel und weitere Verwaltungsabläufe. Die offiziellen Befehle sind eine sinnvolle Referenz für bekannte Spielerbedienung. [Befehlsdokumentation](https://intellectualsites.gitbook.io/plotsquared/features/commands)

P2 unterscheidet Member mit Baurecht bei anwesendem Besitzer von dauerhaft berechtigten Trusted-Spielern. [Mitgliedschaftsstufen](https://intellectualsites.gitbook.io/plotsquared/features/plot-membership-tiers)

Für Flagwerte, Entity-Caps, Wetter/Zeit und erlaubte Materialien ist das P2-System wesentlich breiter. Rand, Wand, Biom und Home besitzen eigene Einstellungswege und Permissions. [Plot-Flags](https://intellectualsites.gitbook.io/plotsquared/features/plot-flags), [Plot-Komponenten](https://intellectualsites.gitbook.io/plotsquared/customization/plot-components)

## Priorität A: vor öffentlichem Betrieb beheben

### A1. Sauberer Datenbankstart ist nicht durchgängig vorbereitet

Die Default-Konfiguration wählt SQLite, deaktiviert aber dessen Migrationen über `allowNonPostgres=false`. In den Gradle-Abhängigkeiten sind keine expliziten JDBC-Treiber deklariert; im vorhandenen Plugin-JAR fehlen alle vier Treiber. Für PostgreSQL/MariaDB bestehen außerdem mehrere Migrationen mit Version V2. Zusätzlich enthält die gemeinsame V1 PostgreSQL-spezifische Ausdrücke wie `now()` und `BIGSERIAL`; `JdbcAuditService` verwendet `?::json`.

**Folge:** Die beworbene Datenbankauswahl ist kein verlässlich fertig eingerichteter Betriebsweg. Grüne Unit-Tests belegen hier keinen erfolgreichen Erststart.

**Ausbau:** Unterstützte Treiber ausliefern, eindeutige Migrationsversionen und echte Dialektpfade definieren; zuerst einen sauberen SQLite- und PostgreSQL-Erststart samt Update prüfen. DB-Fehler dürfen das Plugin nicht scheinbar gesund betreiben lassen.

Belege: [HomeClaim.toml:4](../homeclaim-platform-paper/src/main/resources/HomeClaim.toml#L4), [HomeClaimPaperPlugin.kt:735,855](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/HomeClaimPaperPlugin.kt#L735), [homeclaim-platform-paper/build.gradle.kts:16](../homeclaim-platform-paper/build.gradle.kts#L16), `db/migration/common/V1__init.sql`, `db/migration/common/V2__add_region_price.sql`, `db/migration/postgres/R__postgres_extras.sql`, `db/migration/mariadb/R__mariadb_extras.sql`, [JdbcAuditService.kt:15](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/JdbcAuditService.kt#L15).

### A2. Merge-Geometrie und Regionssuche widersprechen sich

`mergeRegions()` ändert nur `merge_group_id`. `getRegionAt()`/`getRegionAt2D()` suchen weiterhin ausschließlich innerhalb der ursprünglichen Rechtecke. Der Mutationsplan baut die Straße dazwischen baulich um, registriert den Korridor aber nicht als geschützten Teil der Merge-Gruppe.

**Folge:** Auf einer entfernten Straße kann weiterhin `NO_REGION` entstehen. Normales Bauen wird dort abgewiesen; andere Schutz- und Entry-Abläufe erhalten keine passende Region. Ein großer umschließender Quader wäre bei L-förmigen Gruppen ebenfalls falsch, weil er fremde Flächen einschließen könnte.

**Ausbau:** Bestehende Merge-Gruppe um eine exakte gemeinsame Flächenauflösung einschließlich Korridoren erweitern. Einheitliche Besitzer-/Rechte-/Flagregeln für diese Fläche festlegen.

Zusätzlich: Der Command verwendet eine feste Nachbarschaftslücke von 32, die Mutationsplanung dagegen `roadWidth + 2`. Erlaubte breite Straßen können dadurch nicht wie erwartet gemerged werden. Straßenbreite 1 kann bei den inklusiven Plotgrenzen zu Überschneidungen führen. Generator, Initializer, Suche und Merge brauchen dieselbe Layoutberechnung.

Belege: [JdbcRegionRepository.kt:44,66,124](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/JdbcRegionRepository.kt#L44), [PlotBorderPlanner.kt:73](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/PlotBorderPlanner.kt#L73), [PlotCommand.kt:675,777](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/command/PlotCommand.kt#L675), [PlotWorldInitializer.kt:129](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/PlotWorldInitializer.kt#L129).

### A3. Folia-Reset kann Teilflächen auslassen

Der ChunkPlanner teilt einen Chunk in mehrere Batches. `FoliaPlotResetService` verwendet für alle Teilbatches desselben Chunks dieselbe ID aus Plot und Chunkkoordinaten. Solange der erste Batch aktiv ist, verweigert die Registry einen weiteren mit derselben ID. Der äußere Reset meldet bereits Erfolg, wenn mindestens ein Batch angenommen wurde.

**Ausbau:** Eindeutige Teilbatch-IDs, begrenzte Einplanung, gemeinsames Abschluss-/Fehlerergebnis und wiederholbare fehlgeschlagene Teilaufträge. Batchgröße ist derzeit auf Folia kein zeitliches Budget, weil alle Batches unmittelbar eingereicht werden.

Belege: [PlotChunkPlanner.kt:34](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/PlotChunkPlanner.kt#L34), [FoliaPlotResetService.kt:89](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/FoliaPlotResetService.kt#L89), [FoliaRegionScheduledWorldMutationBackend.kt:39](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/FoliaRegionScheduledWorldMutationBackend.kt#L39), [PlotJobRegistry.kt:62](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/PlotJobRegistry.kt#L62).

### A4. Besitzänderungen und Geldbewegungen sind nicht ausreichend abgesichert

Der Command prüft vor Claim, ob ein Plot frei ist; `claimRegion()` prüft dies selbst nicht. Es gibt keinen bedingten SQL-Update auf den bisherigen Besitzer. Kauf und Claim bewegen Geld vor `updateRegion()`; ein anschließend abgebrochener oder fehlgeschlagener Update kann Geld und Besitz auseinanderlaufen lassen. Beim Transfer gibt es keine Kompensation bei fehlgeschlagener Einzahlung.

Beim Kauf werden außerdem alte Rollen unverändert übernommen. `mergeRegions()` dispatcht zwar ein Pre-Event, berücksichtigt dessen Cancel-/Deny-Ergebnis aber nicht. Welt, Nachbarschaft und gemeinsamer Besitzer werden im Service nicht als Invarianten geprüft. Das Audit läuft teilweise nach dem Commit über eine weitere Verbindung; ein Auditfehler kann trotz gespeicherter Änderung den Post-Event verhindern. Unlink aktualisiert die Mitglieder einzeln; ein Fehler kann die Gruppe teilweise auflösen.

**Ausbau:** In den vorhandenen Services atomare Besitzbedingungen, aktuelle Actor-Prüfung, gruppenweise Updates und einen kompensierbaren Economy-Ablauf etablieren. Alte Trusts bei Verkauf nach ausdrücklich festgelegter Regel entfernen oder übertragen.

Belege: [JdbcRegionRepository.kt:124,478,510](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/JdbcRegionRepository.kt#L124), [EconHandler.kt:51](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/economy/EconHandler.kt#L51), [PlotCommand.kt:411](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/command/PlotCommand.kt#L411).

### A5. Schutz deckt wichtige Minecraft-Wege noch nicht ab

Der aktive Listener deckt Platzieren, Abbauen, Block-/Containerinteraktion, einige Entity-/Vehicle-/Redstone-Aktionen sowie Move/Teleport ab. Für plotübergreifende Kolben, Flüssigkeiten, Hoppertransfers, Bucket-Aktionen, Blockexplosionen und mehrere Entity-/Hanging-Interaktionen wurde kein entsprechender aktiver Handler gefunden. `onExplosion()` entscheidet am Explosionszentrum statt für jeden betroffenen Block. `onEntityDamage()` überspringt alle DamageByEntity-Ereignisse und prüft anschließend nur Spieler; ein allgemeines Tier-/Entity-Schadensflag ist damit nicht vollständig umgesetzt.

Ein FAWE-Konvertierungsweg ist vorhanden. Ein HomeClaim-WorldEdit-/FAWE-Regionsschutz für Spieler wurde nicht gefunden. Das ist eine andere Aufgabe als das schnelle Setzen von Blöcken beim Setup.

**Ausbau:** Schutzmatrix aus Quelle, Ziel, Welt und Aktion definieren, auf die bestehende Policy mappen und mit echten grenzübergreifenden Situationen testen. Andere Welten ausdrücklich vom Plot-Schutz unterscheiden: aktuell wird auch dort für Build/Break ohne Region `NO_REGION` verweigert.

Belege: [PaperPolicyListener.kt:191,305,375](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/PaperPolicyListener.kt#L191), [PaperPlatformBridge.kt:20](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/PaperPlatformBridge.kt#L20), `PolicyGuard.kt`, [SimplePolicyService.kt:33](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/policy/SimplePolicyService.kt#L33). P2 behandelt zahlreiche dieser Wege explizit. [Upstream-Blocklistener](https://raw.githubusercontent.com/IntellectualSites/PlotSquared/main/Bukkit/src/main/java/com/plotsquared/bukkit/listener/BlockEventListener.java)

### A6. Erfolgreicher Build liefert noch kein funktionierendes Laufzeitartefakt

Der Shadow-Build schließt `**/*.kotlin_builtins` ausdrücklich aus. Diese Ressourcen braucht Kotlin-Reflection, die wiederum vom Jackson-Kotlin-Modul verwendet wird. Ein direkter JSON-Smoke-Test ausschließlich gegen das aktuelle Plugin-JAR scheitert dadurch mit `AssertionError: Built-in class kotlin.Any is not found`. Mit den vollständigen aufgelösten Abhängigkeiten gelingt das Schreiben; anschließend tritt der separate PolicyValue-Deserialisierungsfehler auf.

**Ausbau:** Nötige Kotlin-/Service-/Treiberressourcen im JAR erhalten. Zusätzlich zum normalen Testclasspath das tatsächliche Shadow-JAR mit einer serialisierbaren Core-Datenklasse und einem sauberen Pluginstart prüfen. JAR-Verkleinerung darf benötigte Laufzeitressourcen nicht entfernen.

Belege: [Shadow-Konfiguration](../homeclaim-platform-paper/build.gradle.kts#L56), [Serialization.kt](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/Serialization.kt#L9), ausgeführter Artefakt-Smoke-Test.

## Priorität B: Regeln und bestehende Funktionen durchgängig machen

| Befund | Konkrete Wirkung | Sinnvoller Ausbau / Fundstellen |
|---|---|---|
| Claim-/Merge-Grenzen werden im aktiven Weg nicht erzwungen | Default-Permissions erlauben Claim/Merge; weder Plotzahl noch Merge-Größe werden begrenzt | Grenzen je Spieler/Rang/Welt und Sonderzone im gemeinsamen Service; [PlotCommand.kt:116,383](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/command/PlotCommand.kt#L116), [Permissions.kt:29](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/util/Permissions.kt#L29), `plugin.yml` |
| Zwei Rechtequellen | Paper-Policy nutzt RegionRoles; PlotMemberService bleibt im Bootstrap null. Member darf standardmäßig auch bei offline befindlichem Besitzer bauen | Vorhandenes Online-Member-Verhalten in den maßgeblichen Rechteweg integrieren; [HomeClaimPaperPlugin.kt:747,773](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/HomeClaimPaperPlugin.kt#L747), [SimplePolicyService.kt:125](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/policy/SimplePolicyService.kt#L125), [JdbcPlotMemberRepository.kt:57](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/JdbcPlotMemberRepository.kt#L57) |
| Besitzer umgeht sämtliche Boolean-Flags | Auch PVP=false oder ein gesperrtes Zonenflag wird für OWNER bei der Aktionsentscheidung erlaubt | Besitzrechte von Umwelt-/Serververboten unterscheiden; lockedFlags darf nicht nur die Speicherung beeinflussen; [SimplePolicyService.kt:134](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/policy/SimplePolicyService.kt#L134) |
| Zonen-Priorität wird überschrieben | Zonen werden absteigend sortiert; nachfolgende niedrigere Priorität überschreibt Werte. Locks verhindern nur Region-Overrides | Prioritätsvertrag definieren, Hoch-/Niedrig-Zone und Owner gemeinsam prüfen; [RegionPolicy.kt:21](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/policy/RegionPolicy.kt#L21) |
| REST bestätigt nicht ausgeführte Änderungen | buy, sell, trust, untrust liefern Erfolg ohne die entsprechende Mutation | Vorhandene Fachservices anbinden oder eindeutig „nicht verfügbar“ zurückgeben; [PlotRestServer.kt:394,425,587,610](../homeclaim-api/src/main/kotlin/systems/diath/homeclaim/api/PlotRestServer.kt#L394) |
| REST ist nur Administration | Ein globaler Admin-Token erlaubt sämtliche Aktionen; Actor-/Staff-UUIDs kommen teilweise aus dem Request. Account-Session oder Spielerrechte werden nicht geprüft | Für Serverintegration tragbar; für eine Spieleroberfläche separate authentifizierte Identität, Ressourcenprüfung und begrenzte Aktionen; `AuthMiddleware:1591`, Entry-Routen ab `:481` |
| Weboberfläche ist nicht mit dem aktiven Backend verbunden | Svelte/Alpine fragen `/api/plots` ohne Admin-Header ab; aktiver REST liefert `/api/v1/plots`, Pagination und andere Felder. Svelte fällt bei Fehlern auf Demoplots zurück | Bestehende UI durch einen echten, benutzergebundenen Backendweg verbinden; Demo-Modus explizit kennzeichnen; [frontend/src/lib/api.ts:90](../homeclaim-webux/frontend/src/lib/api.ts#L90), [HomeClaimWebUxServer.kt:39](../homeclaim-webux/src/main/kotlin/systems/diath/homeclaim/webux/HomeClaimWebUxServer.kt#L39) |
| Account-/Social-Schema driftet | Laufzeitmigrationen erzeugen diese Tabellen nicht. Das große Doku-Schema besitzt abweichende Namen/Spalten, z.B. minecraft_uuid vs player_id, account_magic_links vs magic_links, plots ohne region_id/owner | Modelle und Repositories erhalten, maßgebliches Schema festlegen und migrationsfähig angleichen; [docs/db/schema.sql:189,325,465](../docs/db/schema.sql#L189), betreffende Jdbc-Repositories |
| JSON-Rücklesen ist riskant | Sealed PolicyValue/ComponentConfig besitzen keinen expliziten Typvertrag. Profile werden als Map<String,Any?> geladen und Map.toString() anschließend als JSON gelesen | Stabile typisierte JSON-Serialisierung und JDBC-Roundtrips prüfen; [Serialization.kt:9](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/Serialization.kt#L9), [PolicyKeys.kt:74](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/model/PolicyKeys.kt#L74), [ComponentConfig.kt:5](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/model/ComponentConfig.kt#L5), [JdbcFlagProfileService.kt:21](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/JdbcFlagProfileService.kt#L21). PolicyValue-Roundtrip reproduziert; ComponentConfig- und JDBC-Roundtrips zusätzlich erforderlich |
| Reset ist sofort destruktiv | `/plot reset` benötigt keine zusätzliche Bestätigung. Kein Restore-Ablauf gefunden; Backend meldet trotzdem supportsUndo=true | Kurze Bestätigung und Wiederherstellung/Backup als konkrete Fähigkeiten; [PlotCommand.kt:468](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/command/PlotCommand.kt#L468), [PaperSynchronousWorldMutationBackend.kt:22](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/PaperSynchronousWorldMutationBackend.kt#L22), [WorldMutationBackend.kt:60](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/mutation/WorldMutationBackend.kt#L60) |
| Reset und Merge sind nicht gegeneinander gesperrt | Reset sperrt seinen Jobkey; Merge/Unlink besitzen andere Keys. Reset einer Einzelregion lässt Merge-Korridore außen vor | Plot-/Gruppenoperationen serialisieren; Block-/Entity-/Komponenten-/Metadatenverhalten festlegen; beide ResetServices, MutationServices |
| Jobpersistenz ist keine Wiederaufnahme | Serialisierbare Registry vorhanden, aber kein Startaufruf von fromPersistedFormat. Reset-Fortschritt/Operationen werden nicht gesichert. Reflection auf internal getJobRegistry ist fragil | Bestehende Registry mit explizitem Zugriff und dauerhaftem Operationsfortschritt verbinden; [HomeClaimPaperPlugin.kt:380](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/HomeClaimPaperPlugin.kt#L380), [PlotJobRegistry.kt:260,277](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/PlotJobRegistry.kt#L260) |
| Abgelaufener Job verliert Sperre, nicht zwingend Ausführung | cleanupExpired entfernt Eintrag; isCancellationRequested liefert für fehlenden Eintrag false. Ein alter Handle kann einen neu erworbenen gleichen Key entfernen | Besitzkennung pro Jobhandle, Ablauf als tatsächlicher Abbruch, Sperre bis bestätigtem Ende; [PlotJobRegistry.kt:102,224,247](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/mutation/PlotJobRegistry.kt#L102) |
| Erfolgsstatus/Metriken sind teilweise unzutreffend | async submit ist kein erfolgreicher Abschluss; Fehler/Queue-Metriken sind auf 0 gesetzt. Uptime ist im DTO Sekunden, Prometheus teilt nochmals durch 1000 | Abschlusszustände und Einheiten verbindlich machen; [PaperServerMetricsService.kt:48,156](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/metrics/PaperServerMetricsService.kt#L48), [PlotRestServer.kt:224](../homeclaim-api/src/main/kotlin/systems/diath/homeclaim/api/PlotRestServer.kt#L224) |
| Reload bei Storagewechsel hängt alte Teile ab | Mutation-Hooks bleiben als registriert markiert, alter Dispatcher/Backend bleiben erhalten; neue Services erhalten keinen neuen Mutationlistener. Alte DB wird nicht geschlossen, Shutdown-/Health-Wiring nicht neu aufgebaut | Storagewechsel über Neustart oder einen vollständig abgeschlossenen Servicewechsel mit Datenmigration; [HomeClaimPaperPlugin.kt:481,324,417](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/HomeClaimPaperPlugin.kt#L481) |
| ClientLink beim Erststart fehlt | setupClientLinkChannels läuft vor this.services=services; Listener-Erstellung beendet sich bei null | Initialisierungsreihenfolge korrigieren; [HomeClaimPaperPlugin.kt:121,144](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/HomeClaimPaperPlugin.kt#L121) |
| Plot-Menüs noch nicht freischaltungsreif | Mehrere Menüs haben 4 Reihen, verwenden aber Slots 45/49/53. Drag-Handler fehlt; unteres Spielerinventar wird über slot statt rawSlot verarbeitet. Rechteprüfungen fehlen in mehreren Editoren | Bestehende Menüs reparieren, pro Klick aktuelle Berechtigung prüfen, sicheren `/plot menu`-Einstieg anschließen; [ComponentManagerMenu.kt:21](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/ComponentManagerMenu.kt#L21), [PadCreatorMenu.kt:27](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/PadCreatorMenu.kt#L27), [ZoneInfoMenu.kt:16](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/ZoneInfoMenu.kt#L16), [GuiManager.kt:99](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/GuiManager.kt#L99) |
| Snapshots können Änderungen zurücksetzen | Editoren kopieren komplette alte Region-/Component-Objekte zurück. ComponentEditor setzt beim Speichern z.B. Range/Mode/Scope auf Defaults | Aktuellen Zustand laden und nur das bearbeitete Feld ändern; [RegionPriceEditorMenu.kt:62](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/RegionPriceEditorMenu.kt#L62), [ComponentEditorMenu.kt:140](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/ComponentEditorMenu.kt#L140) |
| ComponentPolicy ist nicht durchgesetzt | access/cooldownMs gespeichert, aktive Trigger prüfen nur Regionsflag/-limit. Ein Pad kann fremder Position zugeordnet werden | Komponentenrechte, Position-in-Region und Actor zentral prüfen; [Component.kt:21](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/model/Component.kt#L21), [ComponentTriggerHandler.kt:33](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/platform/ComponentTriggerHandler.kt#L33), [PadCreatorListener.kt:29](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/PadCreatorListener.kt#L29), [PadCreatorMenu.kt:190](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/PadCreatorMenu.kt#L190) |
| LiftLink prüft Benutzung doppelt | PolicyListener-Trigger und LiftLinkListener evaluieren COMPONENT_USE; erster Check kann Cooldown setzen, zweiter sofort verweigern. LiftListener ignoriert bereits gecancelte Events nicht | Ein maßgeblicher Benutzungsvorgang; [PaperPolicyListener.kt:267](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/PaperPolicyListener.kt#L267), [PaperLiftLinkListener.kt:44,106](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/PaperLiftLinkListener.kt#L44) |
| LiftLink-Scope/Verknüpfung teilweise unvollständig | MERGE_GROUP startet mit Komponenten ausschließlich der Ausgangsregion; Zielsuche erweitert die Menge nicht. GUI erzeugt unabhängige Link-UUIDs; gemeinsames Pairing fehlt | Vorhandenen Planner/Config ausbauen, nicht neu schreiben; [PaperLiftLinkListener.kt:278](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/PaperLiftLinkListener.kt#L278), [PadCreatorMenu.kt:174](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/gui/PadCreatorMenu.kt#L174) |
| Auth ist teilweise experimentell | WebUX akzeptiert beliebige Bearer-Header als TestUser; WebAuthn-Registrierung prüft Antwort nicht vollständig; Login/OAuth bleiben Platzhalter. Magic-Link-Login prüft weder Zweck noch Accountstatus. Tokens werden im Demo-Mailweg geloggt | Erst fertigstellen und prüfen, dann anbinden; [HomeClaimWebUxServer.kt:266](../homeclaim-webux/src/main/kotlin/systems/diath/homeclaim/webux/HomeClaimWebUxServer.kt#L266), [DefaultAuthService.kt:117,296,342,384](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/auth/DefaultAuthService.kt#L117) |
| Öffentliche Erweiterungs-API läuft neben dem Kern | Client/Core haben eigene Typen und getrennte Registries; aktive Modelle nutzen feste Enums. Kein Provider-/Event-Bridge-Wiring gefunden | Eine exportierte Service-/Event-Schnittstelle und Adapter für vorhandene Clienttypen; `homeclaim-api-client`, Core-Registries, `Component.kt`, `Decision.kt` |
| Eventfehler verschwinden | Reflection-Dispatcher verschluckt Handlerfehler vollständig; Audit verwendet oft Besitzer statt tatsächlich ausführendem Actor | Fehler melden, Mutation-/Auditstatus trennen, Actor im gemeinsamen Aufrufkontext; [EventDispatcher.kt:40](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/event/EventDispatcher.kt#L40), [JdbcRegionRepository.kt:267,318](../homeclaim-core/src/main/kotlin/systems/diath/homeclaim/core/store/JdbcRegionRepository.kt#L267) |
| Import ist unvollständig angeschlossen | ImportCommand vorhanden, im Bootstrap/Commandbaum nicht registriert. P2 übernimmt größte/erste Teilregion, minY wird auf 0 gesetzt; kein Merge-Modell. Wiederholung erzeugt neue UUIDs, cancel wird in der Schleife nicht geprüft | Idempotenter Import mit Vorschau, Konflikt-/Flagbericht, vollständiger Geometrie und wirksamer Abbruchprüfung; [ImportCommand.kt:25](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/command/ImportCommand.kt#L25), [PlotImportService.kt:71,205,249,382](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/migration/PlotImportService.kt#L71) |
| Konvertierung hat falsche Flächenausdehnung | Wizard halbiert plotsPerSide; BulkBlockSetter halbiert radiusInPlots nochmals. AIR wird übersprungen; vorhandene Gebäude werden nicht vollständig entfernt. Plotinitialisierung fehlt unmittelbar nach Konvertierung | Fläche aus gemeinsamem Layout ableiten, gewünschten Löschumfang bestätigen, Regionen sofort initialisieren; [PlotWorldSetupWizard.kt:229](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/PlotWorldSetupWizard.kt#L229), [BulkBlockSetter.kt:89,128](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/BulkBlockSetter.kt#L89) |
| Konfiguration enthält wirkungslose/abweichende Optionen | YAML-Validator nicht mit aktivem TOML verbunden; mutationBatchColumnsPerTask und ELEVATOR_RANGE_BLOCKS werden nicht durchgesetzt. Folia-Empfehlungen werden durch Session.toConfig nicht vollständig übernommen | Ein maßgeblicher Konfigurationsvertrag, dokumentierte Wirkung und Capability-Anzeige; `ConfigValidator.kt`, [PlotWorldConfig.kt:64](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/PlotWorldConfig.kt#L64), [PlotWorldSetupWizard.kt:491](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/plot/PlotWorldSetupWizard.kt#L491) |
| Kleine Bedienungsfehler häufen sich | Bedrock-Namen mit Floodgate-Präfix scheitern an Java-Namensregex; `/plot merge` ohne Richtung bedeutet ALL; teure Commands bekommen wegen `plot.claim` vs `claim` nur normales Rate-Limit | P2-nahe Defaults, UUID-/Bedrock-Auflösung, kanonische Commandnamen; Info-/List-Permissions sind im plugin.yml nicht deklariert und müssen in das Freigabekonzept aufgenommen werden; [InputValidator.kt:97](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/util/InputValidator.kt#L97), [PlotCommand.kt:64,703](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/command/PlotCommand.kt#L64), [CommandRateLimiter.kt:31,66](../homeclaim-platform-paper/src/main/kotlin/systems/diath/homeclaim/platform/paper/util/CommandRateLimiter.kt#L31) |

Zusätzliche Dokuabweichungen: API-Beispiel `pvp` erzeugt einen anderen Key als der aktive Katalog `PVP`. Beispiel `WILDCARD` ist kein EntryDenyTargetType; aktuell gelten PLAYER, EVERYONE, NAME_PATTERN. Das gezeigte Muster `bot-*` wird vom JDBC-Validator wegen `-` abgewiesen. Die Crypto-Doku beschreibt andere Profile, Algorithmen und Settingswege als der tatsächliche Auth-Code. Die Hauptkonfiguration liegt aktiv in `./config/HomeClaim.toml`, obwohl Teile der Doku YAML nennen.

## Was sich von OPSucht sinnvoll übernehmen lässt

Öffentlich dokumentiert sind vier anfängliche Plot-Slots, gezielte Erweiterungen, ein Einstellungsmenü, getrenntes Member/Trust, Likes und besondere Spawnplot-Regeln. Spawnplots dürfen dort weder gemerged noch verkauft werden; Merge ist dort nicht rückgängig zu machen. Das beschreibt die Spielerregeln, nicht den internen Aufbau eines Forks. [OPSucht Plot-System](https://wiki.opsucht.net/welten/citybuild/plot-system/)

Der Immobilienmarkt bietet einen eigenen Handelsablauf; bestehende Grundstücksrechte werden beim Verkauf entfernt. [OPSucht Immobilienmarkt](https://wiki.opsucht.net/opsucht-features/wirtschaft/immobilienmarkt/)

Für HomeClaim leite ich daraus diese Produktentscheidungen ab:

1. **Ein fertiges CityBuild-Profil:** ausgewählte Plotgröße, Slotlimit, maximale Merge-Gruppe, erlaubte Spielerflags, Entity-/Redstone-Budgets und feste Spawnregeln. Die vorhandenen Zonen/Profile weiterverwenden und um Operationsregeln erweitern.
2. **Ein Plot-Menü als Hauptbedienung:** Infos, Spielerrechte, Einstellungen, Home, Gestaltung, Likes, Verkauf. Bestehende Menüs anbinden; nur funktionierende Aktionen anzeigen.
3. **Sonderregeln serverseitig:** Spawn/Reserviert/Verkauf nicht bloß durch Randfarbe oder Metadata markieren. Claim, Merge, Reset und Verkauf müssen diese Regeln im gemeinsamen Service beachten.
4. **Gestaltung über Presets:** Rand-/Wandmaterialien aus einer kontrollierten Auswahl anbieten. Die vorhandenen VisualStates und Material-Metadaten nutzen; danach Biom/Zeit/Wetter gezielt ergänzen.
5. **Sicherer Verkauf:** Besitzerwechsel, Bezahlung und Rechtewechsel als einen nachvollziehbaren Ablauf behandeln. Ein vollständiger Auktionsmarkt ist ein späterer Ausbau, keine Voraussetzung für die Reparatur des vorhandenen Kaufs.
6. **Unlink bewusst steuern:** HomeClaim besitzt Unlink bereits. Pro Betriebsprofil erlauben, begrenzen oder nach Bestätigung ausführen; eine OPSucht-Regel ist kein Grund, diese Funktion allgemein zu entfernen.

P2 selbst begrenzt Claim und Merge im Commandweg und berücksichtigt dazu Permissions, Events und Economy. Das ist eine nützliche Verhaltensreferenz, kein Beleg für Fehlerfreiheit des Upstreams. [Claim-Code](https://raw.githubusercontent.com/IntellectualSites/PlotSquared/main/Core/src/main/java/com/plotsquared/core/command/Claim.java), [Merge-Code](https://raw.githubusercontent.com/IntellectualSites/PlotSquared/main/Core/src/main/java/com/plotsquared/core/command/Merge.java)

## Wie der vorhandene Aufbau einfacher wird

Der Core sollte die gemeinsame Wahrheit für Region, Besitzer und Rechte bleiben. Das zusätzliche Plot-Modell kann Metadaten und Social-Funktionen liefern; doppelte Besitzer-, Alias- und Rolleninformationen müssen über definierte Zuordnung und Migration abgestimmt werden.

Commands, GUIs und REST sollten dieselben actorbezogenen Operationen der bestehenden Services aufrufen. Diese Operationen prüfen aktuellen Besitz, Rechte, Limits, Sonderzonen und Jobkonflikte. Repositories übernehmen persistente Änderungen; Weltänderungen laufen weiter über das vorhandene WorldMutationBackend. Jede Operation braucht einen nachvollziehbaren Zustand: angenommen, läuft, erfolgreich, fehlgeschlagen oder abgebrochen.

Erweiterbarkeit braucht dazu konkrete Verträge: validierte Flagtypen, tatsächlich erreichbare Events, begrenzte Komponenten-Capabilities und ausdrücklich erlaubte Überschreibungen. Die vorhandenen Registries können dafür angepasst werden. Unbekannte oder nicht angebundene Aktionen dürfen keinen normalen Erfolg vortäuschen.

Web/Auth/Social und Lizenz-Metadaten sind eigenständige Bereiche. Sie sollten durch klar ausgewiesene Aktivierung und Fähigkeiten von einem fertigen Plotbetrieb unterscheidbar sein. Vorhandene Implementierungen erhalten; keine experimentelle Oberfläche als fertigen Produktionsweg ausgeben.

## Empfohlene Reihenfolge und Abnahmekriterien

| Schritt | Arbeit am vorhandenen Bestand | Woran der Schritt fertig ist |
|---|---|---|
| 1 | Paketierung, Treiber, Dialekte, Migrationen, JSON, Erststart | Leere und bestehende unterstützte DB starten; Rollen/Flags/Komponenten überstehen Neustart |
| 2 | Gemeinsame Geometrie und vollständiger Schutz | Merge-Korridore sind korrekt zugeordnet; Kolben/Flüssigkeiten/Hopper/Explosionen überschreiten keine Berechtigungsgrenze |
| 3 | Actor, Besitzänderung, Member/Trust, Limits, Sonderzonen | Alle Einstiege erzwingen dieselben Regeln; zwei gleichzeitige Claims erzeugen keinen Besitz-/Geldkonflikt |
| 4 | Jobs, Folia-Batches, Recovery, Resetbestätigung | Reset verändert jede geplante Fläche genau nach Vertrag; Fehler, Cancel und Neustart haben sichtbaren, sicheren Ausgang |
| 5 | Bestehende Menüs und Spielercommands | Menü ist erreichbar und inventarsicher; Member/Trust/Deny, Plotauswahl, Home und Alias funktionieren durchgängig |
| 6 | Economy/Import/Web/Client-API anbinden | Kein Platzhaltererfolg; Imports sind wiederholbar; Webidentität und Spielerrechte werden serverseitig geprüft |
| 7 | Gewünschte P2-/CityBuild-Erweiterungen | Biom/Zeit/Wetter, Gestaltungs-Presets, weitere Caps und Handel passen zum ausdrücklich gewählten Betriebsprofil |

Neue Tests sollten die tatsächlichen Lücken abdecken: JDBC-Roundtrips und Migrationen, paralleler Claim/Kauf, Zonenpriorität mit Owner, geschützter Merge-Korridor, mehrere Folia-Teilbatches pro Chunk, API-Mutation samt Rücklesen, GUI-Inventargrenzen und Storagewechsel. Die 44 vorhandenen Tests sind eine gute Basis für lokale Helfer und Regressionen; sie ersetzen diese Integrationstests nicht.

**Meine Empfehlung:** Den bestehenden Plotbetrieb zuerst durchgängig begrenzen und stabilisieren. Danach die bereits vorhandenen Oberflächen und Erweiterungen anbinden und ein einfaches CityBuild-Profil anbieten. So wird HomeClaim für Spieler einfacher, während der ausgebaute Kern erhalten bleibt.
