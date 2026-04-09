# Crypto Settings - Performance Tuning Guide

## Übersicht

Das System nutzt **Argon2id** und **Balloon** überall für konsistenten Brute-Force-Schutz.

```
crypto_settings
├── password_algo    → Argon2id (Passwörter)
├── code_algo        → Argon2id (TOTP, Recovery)
├── token_algo       → Balloon  (Session/API Tokens)
├── link_algo        → Balloon  (Magic Links)
└── hmac_algo        → Balloon  (Signaturen)
```

---

## Parameter-Profile

### 1️⃣ Conservative (Kleine/schwache Server)

```json
{
  "password_algo": "argon2id",
  "password_params": {
    "memory": 8192,
    "iterations": 1,
    "parallelism": 1
  },
  "token_algo": "balloon",
  "token_params": {
    "space_cost": 8192,
    "time_cost": 10,
    "parallelism": 1
  },
  "code_algo": "argon2id",
  "code_params": {
    "memory": 8192,
    "iterations": 1,
    "parallelism": 1
  },
  "link_algo": "balloon",
  "link_params": {
    "space_cost": 8192,
    "time_cost": 10,
    "parallelism": 1
  },
  "hmac_algo": "balloon",
  "hmac_params": {
    "space_cost": 4096,
    "time_cost": 5,
    "parallelism": 1
  }
}
```

**Hashing-Zeit pro Operation:**
- Password: ~100ms
- Token: ~80ms
- HMAC: ~30ms

**Für:** Alte Hardware, Raspberry Pi, Shared Hosting

---

### 2️⃣ Standard (Empfohlen - Default)

```json
{
  "password_algo": "argon2id",
  "password_params": {
    "memory": 19456,
    "iterations": 2,
    "parallelism": 1
  },
  "token_algo": "balloon",
  "token_params": {
    "space_cost": 16384,
    "time_cost": 20,
    "parallelism": 1
  },
  "code_algo": "argon2id",
  "code_params": {
    "memory": 19456,
    "iterations": 2,
    "parallelism": 1
  },
  "link_algo": "balloon",
  "link_params": {
    "space_cost": 16384,
    "time_cost": 20,
    "parallelism": 1
  },
  "hmac_algo": "balloon",
  "hmac_params": {
    "space_cost": 8192,
    "time_cost": 10,
    "parallelism": 1
  }
}
```

**Hashing-Zeit pro Operation:**
- Password: ~250ms
- Token: ~200ms
- HMAC: ~80ms

**Für:** Moderne 4-Core Server, Standard-Setup (OWASP 2025 Standard)

---

### 3️⃣ High Security (Stärkere Server)

```json
{
  "password_algo": "argon2id",
  "password_params": {
    "memory": 65536,
    "iterations": 3,
    "parallelism": 2
  },
  "token_algo": "balloon",
  "token_params": {
    "space_cost": 65536,
    "time_cost": 30,
    "parallelism": 2
  },
  "code_algo": "argon2id",
  "code_params": {
    "memory": 65536,
    "iterations": 3,
    "parallelism": 2
  },
  "link_algo": "balloon",
  "link_params": {
    "space_cost": 65536,
    "time_cost": 30,
    "parallelism": 2
  },
  "hmac_algo": "balloon",
  "hmac_params": {
    "space_cost": 16384,
    "time_cost": 15,
    "parallelism": 2
  }
}
```

**Hashing-Zeit pro Operation:**
- Password: ~800ms
- Token: ~600ms
- HMAC: ~300ms

**Für:** 8+ Core Server, Unternehmens-Setup, High-Security Requirements

---

### 4️⃣ Maximum Security (Enterprise)

```json
{
  "password_algo": "argon2id",
  "password_params": {
    "memory": 262144,
    "iterations": 4,
    "parallelism": 4
  },
  "token_algo": "balloon",
  "token_params": {
    "space_cost": 262144,
    "time_cost": 40,
    "parallelism": 4
  },
  "code_algo": "argon2id",
  "code_params": {
    "memory": 262144,
    "iterations": 4,
    "parallelism": 4
  },
  "link_algo": "balloon",
  "link_params": {
    "space_cost": 262144,
    "time_cost": 40,
    "parallelism": 4
  },
  "hmac_algo": "balloon",
  "hmac_params": {
    "space_cost": 65536,
    "time_cost": 20,
    "parallelism": 4
  }
}
```

**Hashing-Zeit pro Operation:**
- Password: ~2000ms
- Token: ~1500ms
- HMAC: ~800ms

**Für:** Paranoid-Mode, kritische Infrastruktur

---

## Anpassung zur Laufzeit

```sql
-- Update all parameters (Admin-Panel oder via SQL)
UPDATE crypto_settings 
SET 
  password_params = '{"memory":65536,"iterations":3,"parallelism":2}',
  adjusted_at = NOW(),
  adjustment_notes = 'Server upgrade: 4 → 8 Cores'
WHERE id = 1;
```

---

## Performance-Rechner

```
Brute-Force Kosten für 10 Mio Passwörter:
┌──────────────┬───────────┬──────────────┐
│ Profil       │ Zeit/Hash │ Gesamt-Zeit  │
├──────────────┼───────────┼──────────────┤
│ Conservative │ 100ms     │ ~1.000 Tage  │
│ Standard     │ 250ms     │ ~2.900 Tage  │
│ High Sec     │ 800ms     │ ~9.200 Tage  │
│ Maximum      │ 2000ms    │ ~23.000 Tage │
└──────────────┴───────────┴──────────────┘

Ohne Hashing (SHA256):
                     ~0.001ms → ~115 Minuten für 10M
```

---

## Empfehlung nach Hardware

| Hardware | Profil | Begründung |
|----------|--------|-----------|
| Raspberry Pi 4 | Conservative | 1GB RAM, 4x1.5GHz |
| VPS 2-Core | Conservative | Limited CPU/Memory |
| VPS 4-Core 8GB | **Standard** | Balanced (OWASP 2025) |
| Dedicated 8-Core 16GB | High Security | Good headroom |
| Enterprise 16+ Cores | Maximum | Paranoid-ready |

---

## Best Practices

### ✅ DO

- **Setup einmal wählen**, dann nicht mehr ändern
- **Für neue Features**: Settings hochfahren wenn Hardware upgradet
- **Monitoring**: Durchschnittliche Hashing-Zeit tracken
- **Alerts**: Wenn Login-Zeit plötzlich > 1 Sekunde

### ❌ DON'T

- Settings zu oft ändern (verursacht Verwirrung)
- Memory zu hoch setzen (OOM-Kills)
- Parallelism über CPU-Count setzen
- Time/Space Cost auf 0 setzen

---

## Monitoring

```sql
-- Abfrage: Durchschnittliche Hashing-Zeit
SELECT 
  event,
  COUNT(*) as count,
  AVG(EXTRACT(EPOCH FROM (details->'duration')::interval)) as avg_duration_ms
FROM account_audit
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY event
ORDER BY avg_duration_ms DESC;
```

**Acceptable:**
- `auth.login_success`: < 500ms
- `token.create`: < 300ms
- `auth.mfa_success`: < 100ms

**Action Required:**
- Passwort-Hash > 1000ms → Params zu hoch?
- Passwort-Hash < 50ms → Zu niedrig?

---

## Migration zwischen Profilen

⚠️ **Wichtig:** Settings ändern wirkt sich nur auf **neue** Hashes aus!

```
Existing Hash (old params) → Bleibt unverändert
New Hash (new params)      → Nutzt neue Settings
```

**Scenario: Von Conservative zu Standard upgraden**

```sql
-- 1. Update settings
UPDATE crypto_settings 
SET password_params = '{"memory":19456,"iterations":2,"parallelism":1}'
WHERE id = 1;

-- 2. Bei nächstem Login wird der alte Hash mit Standard neu gehashed
-- (Automatisch im Auth-Code: Wenn Hash zu alt ist, neu hashen)

-- 3. Optional: Force-rehash für inaktive Accounts
UPDATE accounts 
SET password_changed_at = NOW() - INTERVAL '30 days'
WHERE last_login_at < NOW() - INTERVAL '30 days'
  AND password_hash IS NOT NULL;
```

---

## Checkliste für Installation

- [ ] Hardware evaluieren (CPU Cores, RAM)
- [ ] Passendes Profil wählen
- [ ] `crypto_settings` in DB laden
- [ ] Environment Variable für `encryption_key_id` setzen
- [ ] Test-Login durchführen, Zeit messen
- [ ] Monitoring einrichten
- [ ] Dokumentation für Admins aktualisieren
