# WebUX und REST Zugriff

## Betriebsmodi

REST und WebUX sind standardmäßig deaktiviert. Beide binden lokal an `127.0.0.1`. Eine Bindung an eine nicht lokale Adresse erfordert `allowRemote = true`. HomeClaim terminiert TLS nicht. Für Remote-Betrieb wird ein HTTPS-Reverse-Proxy vor einem privaten oder lokalen Bind empfohlen. HomeClaim vertraut keinen `Forwarded`- oder `X-Forwarded-*`-Headern.

Für REST sind bei Remote-Bind zusätzlich ein mindestens 32 Zeichen langes Token, die Quell-IP-Liste `allowedHosts` und `allowedHostHeaders` erforderlich. Der unveränderte Maschinenvertrag nutzt `X-Admin-Token`; Vergleiche sind konstantzeitlich. Fehlversuche werden vor der Tokenprüfung pro Quelladresse begrenzt. Die Detailendpunkte einschließlich OpenAPI und Swagger erfordern das Token. CORS ist standardmäßig aus. Wenn aktiviert, muss `allowedOrigins` konkrete HTTP(S)-Origins enthalten; Wildcard-CORS und Credentials sind aus. REST `allowedHosts` sind Quell-IP-Adressen; `allowedHostHeaders` sind Hostnamen aus dem HTTP-Host-Header.

Für WebUX sind Remote-Bind zusätzlich `secureCookies = true`, `allowedHosts` (Hostnamen im `Host`-Header) und `allowedOrigins` (vollständige Browser-Origin, z. B. `https://plots.example.net`) erforderlich. Der Reverse-Proxy muss HTTPS durchsetzen; HomeClaim interpretiert keine Proxy-Header als TLS-Nachweis.

## Ingame-Pairing und Session

Ein Spieler führt `/homeclaim web login` im Spiel aus und gibt den zufälligen Code auf der WebUX-Loginseite ein. Codes haben zwei Minuten TTL, sind einmalig und werden nur gehasht im begrenzten In-Memory-Store gehalten. Die Anmeldung gibt ein neues zufälliges Sessiontoken in einem `HttpOnly`, `SameSite=Lax`, `Path=/`-Cookie aus. Bei Remote-Konfiguration kommt `Secure` hinzu. Sessions laufen nach 12 Stunden ab. Sie sind absichtlich nur im Speicher: Pluginneustart widerruft alle Sessions und Pairingcodes. IP-Adresse und User-Agent werden nicht gespeichert.

Logout widerruft die serverseitige Session und schließt zugehörige WebSockets. Mutierende Browserrequests müssen same-origin sein und den zur Session gelieferten `X-CSRF-Token` mitsenden. Pairing-POSTs und WebSocket-Upgrades prüfen ebenfalls den Origin. Fehlgeschlagene Pairingversuche erhalten höchstens einen anonymisierten Audit-Sample pro Minute; IP, Code und User-Agent werden nicht persistiert. Browser-Admin-Token werden nicht verwendet oder gespeichert.

Authentifizierung gewährt keine HomeClaim-Adminrechte. Es gibt derzeit keine aktive Web-Accountrollen-/Capability-Abbildung und keine mutierenden WebUX-Domainrouten. Adminseiten sind gesperrt; nicht unterstützte WebSocket-Nachrichten werden abgelehnt. REST bleibt ein separat authentifiziertes Maschinen-/Admin-API mit globalem Token; OAuth/API-Scopes und per-user REST-Credentials sind nicht Teil dieses Schritts.

## Grenzen und Betrieb

Die bestehenden Account- und Sessionmodelle/-Repositories sind in diesem Checkout nicht durch Runtime-Migrationen oder Pluginservices angebunden und ihr Schema weicht teilweise von `docs/db/schema.sql` ab. Step 15A aktiviert deshalb nicht diese inaktiven Passwort-/OAuth-Entwürfe und speichert keine persistenten Sessions. Erfolgreicher Login und Logout werden ohne Token/Codes über die vorhandene strukturierte `audit_log`-Pipeline erfasst. Fehlgeschlagene Codes werden nicht einzeln persistiert; ein Account-spezifisches `account_audit_log` ist nicht aktiv.

WebUX setzt `Content-Security-Policy` ohne `unsafe-eval`, `X-Content-Type-Options`, `Referrer-Policy` und `Permissions-Policy`. Die CSP erlaubt `unsafe-inline` nur für Styles, die das vorhandene Frontend noch inline verwendet. WebSocket-Payloads dürfen aktuell nur `ping` enthalten. Verbindungen sind authentifiziert und auf die serverseitige Spieleridentität gebunden.

HTTP-Kompression aus Step 14 bleibt erhalten: statische Ressourcen `br > zstd > raw`, REST-Antworten `zstd > br > raw`; gzip wird nicht verwendet. WebSocket-Kompression bleibt aus.

Beispiel für einen lokalen Betrieb hinter einem HTTPS-Proxy. Host und Origin müssen zur tatsächlich vom Proxy gesendeten Anfrage passen; der Browser-Origin ist die öffentliche HTTPS-Origin:

```toml
[homeclaim.webux]
enabled = true
host = "127.0.0.1"
allowRemote = true
secureCookies = true
allowedHosts = ["plots.example.net"]
allowedOrigins = ["https://plots.example.net"]
```

Die Werte bleiben lokal. Auch bei loopback-Bind signalisiert `allowRemote = true`, dass der Proxy die Oberfläche remote erreichbar macht; dann werden Secure-Cookie und Allowlist-Konfiguration erzwungen. Der Proxy muss TLS terminieren und darf keine beliebigen fremden Header als vertrauenswürdig weiterreichen. Beim lokalen direkten Zugriff bleibt `allowRemote = false`; `allowedHosts`/`allowedOrigins` können leer bleiben, und HomeClaim akzeptiert nur loopback Hostnamen/Origins.
