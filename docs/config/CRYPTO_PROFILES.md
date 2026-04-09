# Crypto Profiles - Configuration Guide

## Profil-Struktur

```
Normale Stärke              Extra-Starke Variante
┌─────────────────────┐    ┌──────────────────────┐
│ Conservative        │    │ Conservative-Extra   │
│ Standard ← default  │    │ Standard-Extra       │
│ HighSecurity        │    │ HighSecurity-Extra   │
│ Maximum             │    │ Maximum-Extra        │
└─────────────────────┘    └──────────────────────┘
  Normal Overhead            ~2-3x Overhead
  100-250ms pro Hash         200-800ms pro Hash
```

---

## config.yml Setup

```yaml
# config.yml (Einmalig bei Installation)

crypto:
  # Wähle Profil (einzige Required Config!)
  profile: standard      # Options:
                         #   conservative
                         #   standard (OWASP 2025, default)
                         #   high_security
                         #   maximum
                         #   conservative-extra
                         #   standard-extra
                         #   high_security-extra
                         #   maximum-extra
  
  # Optional: Nach Setup nicht beachtet!
  # (Wird bei Startup in DB geladen, dann ignoriert)
  # override:
  #   password_memory: 32768  # Falls du tweaken willst
```

---

## Profile im Detail

### Normal: Conservative
```json
{
  "name": "conservative",
  "description": "Für schwache Hardware (Pi, VPS 1-Core)",
  "password": {
    "algo": "argon2id",
    "memory": 8192,
    "iterations": 1,
    "parallelism": 1
  },
  "token": {
    "algo": "balloon",
    "space_cost": 8192,
    "time_cost": 10,
    "parallelism": 1
  },
  "code": {
    "algo": "argon2id",
    "memory": 8192,
    "iterations": 1,
    "parallelism": 1
  },
  "link": {
    "algo": "balloon",
    "space_cost": 8192,
    "time_cost": 10,
    "parallelism": 1
  },
  "hmac": {
    "algo": "balloon",
    "space_cost": 4096,
    "time_cost": 5,
    "parallelism": 1
  },
  "timing": {
    "password": "~100ms",
    "token": "~80ms",
    "hmac": "~30ms"
  }
}
```

### Extra: Conservative-Extra
```json
{
  "name": "conservative-extra",
  "description": "Conservative + paranoid (für kleine aber sichere Server)",
  "password": {
    "algo": "argon2id",
    "memory": 32768,        // 4x mehr
    "iterations": 2,        // 2x mehr
    "parallelism": 1
  },
  "token": {
    "algo": "balloon",
    "space_cost": 32768,    // 4x mehr
    "time_cost": 20,        // 2x mehr
    "parallelism": 1
  },
  "code": {
    "algo": "argon2id",
    "memory": 32768,
    "iterations": 2,
    "parallelism": 1
  },
  "link": {
    "algo": "balloon",
    "space_cost": 32768,
    "time_cost": 20,
    "parallelism": 1
  },
  "hmac": {
    "algo": "balloon",
    "space_cost": 8192,     // 2x mehr
    "time_cost": 10,        // 2x mehr
    "parallelism": 1
  },
  "timing": {
    "password": "~400ms",   // 4x stärker
    "token": "~300ms",
    "hmac": "~100ms"
  }
}
```

---

### Normal: Standard (Default - OWASP 2025)
```json
{
  "name": "standard",
  "description": "OWASP 2025 Standard - für 4-Core Server",
  "password": {
    "algo": "argon2id",
    "memory": 19456,
    "iterations": 2,
    "parallelism": 1
  },
  "token": {
    "algo": "balloon",
    "space_cost": 16384,
    "time_cost": 20,
    "parallelism": 1
  },
  "code": {
    "algo": "argon2id",
    "memory": 19456,
    "iterations": 2,
    "parallelism": 1
  },
  "link": {
    "algo": "balloon",
    "space_cost": 16384,
    "time_cost": 20,
    "parallelism": 1
  },
  "hmac": {
    "algo": "balloon",
    "space_cost": 8192,
    "time_cost": 10,
    "parallelism": 1
  },
  "timing": {
    "password": "~250ms",
    "token": "~200ms",
    "hmac": "~80ms"
  }
}
```

### Extra: Standard-Extra
```json
{
  "name": "standard-extra",
  "description": "Standard + hardened (für Security-fokussierte Admins)",
  "password": {
    "algo": "argon2id",
    "memory": 65536,        // 3.4x mehr
    "iterations": 3,        // 1.5x mehr
    "parallelism": 2        // Parallelisiert
  },
  "token": {
    "algo": "balloon",
    "space_cost": 65536,    // 4x mehr
    "time_cost": 30,        // 1.5x mehr
    "parallelism": 2
  },
  "code": {
    "algo": "argon2id",
    "memory": 65536,
    "iterations": 3,
    "parallelism": 2
  },
  "link": {
    "algo": "balloon",
    "space_cost": 65536,
    "time_cost": 30,
    "parallelism": 2
  },
  "hmac": {
    "algo": "balloon",
    "space_cost": 16384,    // 2x mehr
    "time_cost": 15,        // 1.5x mehr
    "parallelism": 2
  },
  "timing": {
    "password": "~750ms",
    "token": "~600ms",
    "hmac": "~250ms"
  }
}
```

---

### Normal: HighSecurity
```json
{
  "name": "high_security",
  "description": "Für 8+ Core Server (Enterprise)",
  "password": {
    "algo": "argon2id",
    "memory": 65536,
    "iterations": 3,
    "parallelism": 2
  },
  // ... (wie Standard-Extra)
}
```

### Extra: HighSecurity-Extra
```json
{
  "name": "high_security-extra",
  "description": "HighSecurity + Paranoid",
  "password": {
    "algo": "argon2id",
    "memory": 262144,       // 4x mehr
    "iterations": 4,
    "parallelism": 4        // Alle Cores
  },
  // ... (wie Maximum)
}
```

---

## Startup-Logik

```kotlin
fun initializeCryptoSettings(config: Config) {
    val profileName = config.getString("crypto.profile", "standard")
    
    // 1. Lade Profil
    val profile = CryptoProfiles.getProfile(profileName)
        ?: throw InvalidConfigException("Unknown profile: $profileName")
    
    // 2. Optional: Single overrides mit Validierung
    val settings = profile.toSettings()
    
    config.optSection("crypto.override")?.let { overrides ->
        // Nur Ints validieren (nicht Algo-Namen!)
        overrides.optInt("password_memory")?.let {
            if (it in 8192..262144) settings.passwordMemory = it
            else throw InvalidConfigException("password_memory must be 8192-262144")
        }
        // ... weitere Validierung
    }
    
    // 3. Speichere in DB (Singleton)
    database.upsertCryptoSettings(settings)
    
    logger.info("Crypto initialized with profile: $profileName")
    logger.info("Password hash: ${settings.passwordAlgo} (~${profile.timing.password})")
    
    // 4. config.yml wird danach IGNORIERT
    // (Settings aus DB, nicht Config)
}
```

---

## Setup-Wizard (CLI oder GUI)

```
╔════════════════════════════════════════╗
║   HomeClaim - Crypto Setup Wizard      ║
╚════════════════════════════════════════╝

[1/3] Hardware Profile?
  1) Conservative (Pi, 1-Core)
  2) Standard (4-Core) ← default
  3) HighSecurity (8+ Cores)
  4) Maximum (Enterprise)
> 2

[2/3] Extra Security?
  (Diese Einstellung kostet 2-3x mehr CPU)
  
  1) No (Normal strength)
  2) Yes (Extra-strong) ← für Privacy/Security fokussiert
> 1

[3/3] Bestätigung
  ✓ Profil: standard (OWASP 2025)
  ✓ Stärke: Normal
  ✓ Password Hash: ~250ms
  ✓ Token Hash: ~200ms
  
  → Speichern in DB? [J/n] > j

✓ Crypto Settings initialisiert!
```

---

## Admin-Dashboard (Später änderbar)

```
Current Profile: Standard

Strength: Normal
├─ Password: ~250ms
├─ Tokens: ~200ms
└─ HMAC: ~80ms

[Change Profile]
  ┌─────────────────────────────────────┐
  │ Conservative      │ Standard*       │
  │ HighSecurity      │ Maximum         │
  │ Cons-Extra        │ Stand-Extra     │
  │ HighSec-Extra     │ Max-Extra       │
  └─────────────────────────────────────┘
  
  * = Aktuell
  
[Show Details]
[Custom Override] (mit Validierung)

Note: Änderungen wirken sich nur auf neue Hashes aus
```

---

## Code-Integration

```kotlin
// CryptoProfiles.kt
object CryptoProfiles {
    private val profiles = mapOf(
        "conservative" to CryptoProfile(
            name = "conservative",
            passwordMemory = 8192,
            passwordIterations = 1,
            tokenSpaceCost = 8192,
            tokenTimeCost = 10,
            // ...
        ),
        "standard" to CryptoProfile(/* ... */),
        "conservative-extra" to CryptoProfile(
            name = "conservative-extra",
            passwordMemory = 32768,  // 4x
            passwordIterations = 2,   // 2x
            tokenSpaceCost = 32768,   // 4x
            tokenTimeCost = 20,       // 2x
            // ...
        ),
        // ... weitere Profile
    )
    
    fun getProfile(name: String): CryptoProfile? = profiles[name]
    fun listProfiles(): List<String> = profiles.keys.toList()
    fun isExtraStrength(name: String): Boolean = name.endsWith("-extra")
}

// Usage
val profile = CryptoProfiles.getProfile("standard")
val settings = profile.toSettings()
database.upsertCryptoSettings(settings)
```

---

## Empfehlung nach Szenario

| Szenario | Profil | Grund |
|----------|--------|-------|
| Kleine Community | standard | OWASP 2025 Standard |
| Security-fokussiert | standard-extra | 3x stärker, noch performant |
| Enterprise | high_security-extra | Maximum Sicherheit |
| Paranoid Mode | maximum-extra | Kann alles absorbieren |
| Schwache Hardware | conservative | Noch usable |

---

## Zusammenfassung

✅ **Hybrid-Ansatz mit Profilen:**
- Admin wählt **einmalig** beim Setup
- **8 vordefinierte Profile** (normal + extra-stark)
- **Keine Einzeln-Tweaks nötig** (Profil reicht)
- **Optional: Overrides** mit Validierung
- **Nach Setup: DB ist Source of Truth** (nicht Config)
- **API für Admin-Änderungen** später

Sollen ich das in Code implementieren?
