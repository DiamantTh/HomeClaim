-- HomeClaim schema (Postgres/MariaDB/SQLite compatible with minor adjustments)

CREATE TABLE regions (
    id UUID PRIMARY KEY,
    world VARCHAR(64) NOT NULL,
    shape VARCHAR(16) NOT NULL,
    min_x INT NOT NULL,
    max_x INT NOT NULL,
    min_y INT NOT NULL,
    max_y INT NOT NULL,
    min_z INT NOT NULL,
    max_z INT NOT NULL,
    owner UUID NOT NULL,
    merge_group_id UUID,
    price DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    metadata JSON DEFAULT '{}'
);

CREATE TABLE region_roles (
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY(region_id, player_id, role)
);

CREATE TABLE region_flags (
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    flag_key VARCHAR(64) NOT NULL,
    flag_value JSON NOT NULL,
    PRIMARY KEY(region_id, flag_key)
);

CREATE TABLE region_limits (
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    limit_key VARCHAR(64) NOT NULL,
    limit_value JSON NOT NULL,
    PRIMARY KEY(region_id, limit_key)
);

CREATE TABLE zones (
    id UUID PRIMARY KEY,
    world VARCHAR(64) NOT NULL,
    shape VARCHAR(16) NOT NULL,
    min_x INT NOT NULL,
    max_x INT NOT NULL,
    min_y INT NOT NULL,
    max_y INT NOT NULL,
    min_z INT NOT NULL,
    max_z INT NOT NULL,
    priority INT NOT NULL,
    locked_flags JSON NOT NULL DEFAULT '[]',
    tags JSON NOT NULL DEFAULT '[]',
    default_flags JSON NOT NULL DEFAULT '{}',
    default_limits JSON NOT NULL DEFAULT '{}',
    allowed_trigger_blocks JSON NOT NULL DEFAULT '[]'
);

CREATE TABLE components (
    id UUID PRIMARY KEY,
    region_id UUID NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    world VARCHAR(64) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    z INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    policy JSON NOT NULL,
    config JSON NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID,
    target_id UUID,
    category VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE flag_profiles (
    name VARCHAR(64) PRIMARY KEY,
    flags JSON NOT NULL DEFAULT '{}',
    limits JSON NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Indices for fast lookup
CREATE INDEX idx_regions_world_chunk ON regions (world, min_x, max_x, min_z, max_z);
CREATE INDEX idx_components_world_chunk ON components (world, x, z);
CREATE INDEX idx_region_roles_owner ON region_roles (player_id);
CREATE INDEX idx_zones_world_priority ON zones (world, priority DESC);

-- ============================================================================
-- ACCOUNT SYSTEM (API Authentication)
-- ============================================================================

-- ============================================================================
-- CRYPTO SETTINGS (Set once at install, adjustable for performance)
-- ============================================================================
CREATE TABLE crypto_settings (
    id                  INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- Singleton
    
    -- Password Hashing (user-provided secrets)
    password_algo       VARCHAR(20) NOT NULL DEFAULT 'argon2id',
    password_params     JSON NOT NULL DEFAULT '{"memory":65536,"iterations":3,"parallelism":2}',
    /*
      Argon2id Parameters (memory in KiB, inspired by InspIRCd):
      Minimal:              {"memory":32768,"iterations":4,"parallelism":2}  (~850ms)
      Standard (Default):   {"memory":65536,"iterations":3,"parallelism":2}  (~900ms)
      Hardened:             {"memory":98304,"iterations":3,"parallelism":2}  (~1000ms)
      Maximum:              {"memory":131072,"iterations":3,"parallelism":4} (~1100ms)
    */
    
    -- Token Hashing (high-entropy random tokens)
    token_algo          VARCHAR(20) NOT NULL DEFAULT 'blake2b',
    token_params        JSON NOT NULL DEFAULT '{"digest_size":32}',
    /*
      BLAKE2b Parameters (fast, cryptographically secure):
      All profiles use same settings (no memory/time cost needed)
      digest_size: 32 bytes (256 bits) - standard output size
    */
    
    -- TOTP/Recovery Code Hashing
    code_algo           VARCHAR(20) NOT NULL DEFAULT 'argon2id',
    code_params         JSON NOT NULL DEFAULT '{"memory":65536,"iterations":3,"parallelism":2}',
    /*
      Same parameters as password_algo (security for user-visible codes)
    */
    
    -- Magic Link Hashing (short-lived, but user-visible codes)
    link_algo           VARCHAR(20) NOT NULL DEFAULT 'blake2b',
    link_params         JSON NOT NULL DEFAULT '{"digest_size":32}',
    /*
      BLAKE2b for speed (short TTL makes brute-force impractical)
    */
    
    -- HMAC for signatures/integrations
    hmac_algo           VARCHAR(20) NOT NULL DEFAULT 'blake2b',
    hmac_params         JSON NOT NULL DEFAULT '{"digest_size":32}',
    /*
      BLAKE2b-MAC: Faster than HMAC-SHA256, cryptographically strong
    */
    
    -- Secret key for encrypting sensitive data (TOTP secrets, OAuth tokens)
    -- MUST be set during installation, stored encrypted or in env var
    encryption_key_id   VARCHAR(64),              -- Reference to external key (e.g., "env:HOMECLAIM_CRYPTO_KEY")
    
    -- Performance tuning notes
    adjusted_at         TIMESTAMP,
    adjustment_notes    VARCHAR(500),             -- "Increased memory for 8-core server"
    
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert default settings (can be customized during setup)
INSERT INTO crypto_settings (id) VALUES (1);

-- ============================================================================
-- ACCOUNTS
-- ============================================================================

-- Core Accounts
CREATE TABLE accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Identity
    username            VARCHAR(32) UNIQUE NOT NULL,
    email               VARCHAR(254) UNIQUE,
    minecraft_uuid      UUID UNIQUE NOT NULL,
    minecraft_name      VARCHAR(16),
    
    -- Client Type (for UX differentiation)
    client_type         VARCHAR(16) DEFAULT 'java',  -- 'java', 'bedrock', 'floodgate'
    
    -- Password (uses crypto_settings.password_algo)
    password_hash       VARCHAR(256),             -- NULL = Passkey/OAuth only
    password_salt       VARCHAR(64),              -- For algos that need external salt
    password_changed_at TIMESTAMP,
    
    -- MFA
    mfa_enabled         BOOLEAN DEFAULT FALSE,
    mfa_methods         VARCHAR(50)[],            -- ['totp', 'webauthn']
    
    -- Status
    status              VARCHAR(20) DEFAULT 'active',  -- active, suspended, banned, pending
    role                VARCHAR(20) DEFAULT 'user',    -- user, moderator, admin
    
    -- Security (Brute-Force Protection)
    failed_login_count  INTEGER DEFAULT 0,
    failed_login_at     TIMESTAMP,
    locked_until        TIMESTAMP,
    
    -- Timestamps
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at       TIMESTAMP,
    
    -- GDPR Soft Delete
    deleted_at          TIMESTAMP
);

-- TOTP Secrets (Authenticator Apps)
CREATE TABLE account_totp (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID UNIQUE NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    -- Secret (AES-256 encrypted at rest recommended)
    secret_encrypted TEXT NOT NULL,               -- Encrypted TOTP secret
    secret_iv       VARCHAR(32),                  -- IV for decryption
    algorithm       VARCHAR(10) DEFAULT 'SHA1',
    digits          INTEGER DEFAULT 6,
    period          INTEGER DEFAULT 30,
    
    -- Backup Codes (Argon2id hashed, with embedded salt)
    backup_codes    JSON,                         -- [{"hash":"...","used":false}, ...]
    
    verified        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- WebAuthn/Passkey Credentials
CREATE TABLE account_webauthn (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    credential_id   BYTEA UNIQUE NOT NULL,
    public_key      BYTEA NOT NULL,
    sign_count      BIGINT DEFAULT 0,
    
    -- Attestation
    aaguid          BYTEA,                        -- Authenticator GUID
    transports      VARCHAR(20)[],                -- ['usb', 'nfc', 'ble', 'internal']
    
    -- User-friendly
    name            VARCHAR(100),                 -- "YubiKey 5", "iPhone Passkey"
    
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at    TIMESTAMP
);

-- App Passwords (API Keys)
CREATE TABLE account_app_passwords (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    -- Token (SHA256 hash - salt not needed for high-entropy random tokens)
    prefix          VARCHAR(8) NOT NULL,          -- First 8 chars for identification: "hca_xxxx"
    token_hash      VARCHAR(64) NOT NULL,         -- SHA256(full_token)
    
    -- Metadata
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    
    -- Permissions
    scopes          VARCHAR(50)[],                -- ['plots:read', 'plots:write', 'admin:*']
    
    -- Restrictions
    rate_limit      INTEGER,                      -- Requests/minute, NULL = default
    allowed_ips     INET[],                       -- Whitelist, NULL = any
    
    -- Lifecycle
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP,
    last_used_at    TIMESTAMP,
    revoked_at      TIMESTAMP
);

-- Sessions (Browser/API)
CREATE TABLE account_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    -- Token (SHA256 hash)
    token_hash      VARCHAR(64) NOT NULL,
    
    -- Auth Context
    auth_method     VARCHAR(20) NOT NULL,         -- password, webauthn, oauth, app_password, magic_link
    mfa_verified    BOOLEAN DEFAULT FALSE,
    elevation_until TIMESTAMP,                    -- Sudo mode expiry
    
    -- Device Info
    ip_address      INET,
    user_agent      VARCHAR(512),
    device_fingerprint VARCHAR(64),
    
    -- Lifecycle
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP NOT NULL,
    last_active_at  TIMESTAMP,
    revoked_at      TIMESTAMP,
    revoke_reason   VARCHAR(50),                  -- logout, security, admin, expired
    
    -- Geolocation (optional)
    country_code    VARCHAR(2),
    city            VARCHAR(100)
);

-- Magic Links (Email/In-Game Login)
CREATE TABLE account_magic_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Token (Argon2id hash with salt for security)
    token_hash      VARCHAR(256) NOT NULL,
    token_salt      VARCHAR(64) NOT NULL,         -- Required for magic links
    
    -- Target
    account_id      UUID REFERENCES accounts(id) ON DELETE CASCADE,  -- NULL = new user
    minecraft_uuid  UUID NOT NULL,
    
    -- Purpose
    purpose         VARCHAR(20) DEFAULT 'login',  -- login, verify_email, reset_password, link_account
    
    -- Lifecycle
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP NOT NULL,           -- Short TTL: 5-15 minutes
    used_at         TIMESTAMP,
    used_ip         INET
);

-- Recovery Codes (One-time backup)
CREATE TABLE account_recovery_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    -- Code (Argon2id hash - salt embedded)
    code_hash       VARCHAR(256) NOT NULL,
    
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    used_at         TIMESTAMP
);

-- OAuth Connections (Optional)
CREATE TABLE account_oauth (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    provider        VARCHAR(20) NOT NULL,         -- microsoft, discord, github
    provider_user_id VARCHAR(255) NOT NULL,
    
    -- Tokens (AES-256 encrypted)
    access_token_encrypted  TEXT,
    refresh_token_encrypted TEXT,
    token_iv        VARCHAR(32),
    token_expires_at TIMESTAMP,
    
    -- Profile Cache
    profile_name    VARCHAR(100),
    profile_email   VARCHAR(254),
    profile_avatar  TEXT,
    
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP,
    
    UNIQUE(provider, provider_user_id),
    UNIQUE(account_id, provider)
);

-- Trusted Devices (Skip MFA)
CREATE TABLE account_trusted_devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    
    -- Device identifier (SHA256 of fingerprint)
    device_hash     VARCHAR(64) NOT NULL,
    
    name            VARCHAR(100),
    
    trusted_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP,                    -- 30-90 days
    last_seen_at    TIMESTAMP,
    
    UNIQUE(account_id, device_hash)
);

-- Security Audit Log
CREATE TABLE account_audit (
    id              BIGSERIAL PRIMARY KEY,
    account_id      UUID REFERENCES accounts(id) ON DELETE SET NULL,
    
    event           VARCHAR(50) NOT NULL,
    /*
      Events:
      - auth.login_success, auth.login_failed, auth.logout
      - auth.mfa_success, auth.mfa_failed
      - password.change, password.reset_request, password.reset_complete
      - mfa.totp_enable, mfa.totp_disable
      - mfa.webauthn_register, mfa.webauthn_remove
      - token.app_password_create, token.app_password_revoke
      - session.create, session.revoke, session.revoke_all
      - account.create, account.lock, account.unlock, account.delete
      - security.suspicious_login, security.brute_force_block
    */
    
    severity        VARCHAR(10) DEFAULT 'info',   -- info, warning, critical
    
    ip_address      INET,
    user_agent      VARCHAR(512),
    
    details         JSON,
    
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Account Indices
CREATE INDEX idx_accounts_minecraft ON accounts(minecraft_uuid);
CREATE INDEX idx_accounts_email ON accounts(email) WHERE email IS NOT NULL;
CREATE INDEX idx_accounts_status ON accounts(status) WHERE deleted_at IS NULL;

CREATE INDEX idx_sessions_token ON account_sessions(token_hash) WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_account ON account_sessions(account_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_sessions_expires ON account_sessions(expires_at) WHERE revoked_at IS NULL;

CREATE INDEX idx_app_passwords_prefix ON account_app_passwords(prefix) WHERE revoked_at IS NULL;
CREATE INDEX idx_app_passwords_account ON account_app_passwords(account_id) WHERE revoked_at IS NULL;

CREATE INDEX idx_magic_links_token ON account_magic_links(token_hash) WHERE used_at IS NULL;
CREATE INDEX idx_magic_links_expires ON account_magic_links(expires_at) WHERE used_at IS NULL;

CREATE INDEX idx_webauthn_account ON account_webauthn(account_id);
CREATE INDEX idx_webauthn_credential ON account_webauthn(credential_id);

CREATE INDEX idx_oauth_account ON account_oauth(account_id);
CREATE INDEX idx_oauth_provider ON account_oauth(provider, provider_user_id);

CREATE INDEX idx_audit_account ON account_audit(account_id, created_at DESC);
CREATE INDEX idx_audit_event ON account_audit(event, created_at DESC);
CREATE INDEX idx_audit_severity ON account_audit(severity, created_at DESC) WHERE severity IN ('warning', 'critical');

-- ============================================================================
-- PLOT MANAGEMENT (Extended Metadata & Web Features)
-- ============================================================================

/*
    Erweitert regions mit Plot-spezifischen Metadaten:
    - In-Game: Alias, Titel, interne Beschreibung, Notizen
    - Web: Öffentliche Beschreibung, Kategorien, Tags, Sichtbarkeit
    - Admin: Featured-Status, Statistiken
*/
CREATE TABLE plots (
    id                      UUID PRIMARY KEY REFERENCES regions(id) ON DELETE CASCADE,
    
    -- In-Game Verwaltung (Owner-Features)
    alias                   VARCHAR(64),              -- Kurzname für /plot alias (z.B. "spawn", "arena")
    title                   VARCHAR(128),             -- Öffentlicher Titel (Web + In-Game)
    description             TEXT,                     -- Öffentliche Beschreibung (für Webseite/Besucher)
    
    internal_description    TEXT,                     -- Interne Dokumentation (nur Owner/Admins sichtbar)
    notes                   TEXT,                     -- Private Notizen des Owners
    
    -- Web-Features (Community/Discovery)
    category                VARCHAR(32),              -- adventure, pvp, citybuild, redstone, creative, etc.
    tags                    JSON DEFAULT '[]',        -- ['medieval', 'castle', 'wip', 'german']
    visibility              VARCHAR(16) DEFAULT 'public',  -- public, unlisted, private
    
    -- Admin Features (Server-Management)
    featured                BOOLEAN DEFAULT false,    -- Admin kann Plot hervorheben
    featured_until          TIMESTAMP,                -- Featured-Status läuft ab
    featured_reason         VARCHAR(256),             -- Warum featured? (z.B. "Build des Monats")
    
    -- Statistik (Denormalisiert für Performance)
    views_count             INT DEFAULT 0,            -- Webseite Aufrufe
    visits_count            INT DEFAULT 0,            -- In-Game Besuche (/plot visit)
    likes_count             INT DEFAULT 0,            -- Cached von plot_likes
    bookmarks_count         INT DEFAULT 0,            -- Cached von plot_bookmarks
    
    -- Zeitstempel
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP NOT NULL DEFAULT now(),
    last_activity_at        TIMESTAMP,                 -- Letzte Aktivität (Build, Visit, etc.)
    
    -- Broadcasting & Sichtbarkeit
    broadcasting_enabled    BOOLEAN DEFAULT true,      -- Admin/Mod/Supporter können broadcasten
    
    -- Stats-Sichtbarkeit (Privatsphäre)
    stats_visibility        VARCHAR(20) DEFAULT 'public',  -- public, friends_only, whitelist, private
    /*
      public: Jeder sieht alle Stats
      whitelist: Nur Owner + Whitelist-User sehen Stats
      private: Nur Owner sieht Stats
    */
    stats_visibility_list   JSON DEFAULT '[]'          -- Whitelist User UUIDs: ["uuid1", "uuid2", ...]
);

/*
    Screenshots für Plots (Web-Galerie)
*/
CREATE TABLE plot_screenshots (
    id                  UUID PRIMARY KEY,
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    
    url                 VARCHAR(512) NOT NULL,    -- S3/CDN URL oder lokaler Pfad
    thumbnail_url       VARCHAR(512),             -- Optional: Thumbnail für Performance
    
    caption             TEXT,                     -- Beschreibung des Screenshots
    sort_order          INT DEFAULT 0,            -- Manuelle Reihenfolge
    
    is_primary          BOOLEAN DEFAULT false,    -- Hauptbild für Vorschau
    
    uploaded_by         UUID NOT NULL,            -- User der hochgeladen hat
    uploaded_at         TIMESTAMP NOT NULL DEFAULT now()
);

/*
    Social Features: Likes für Plots
    Zeitbasiert für Custom-Stats (letzte 24h, 7 Tage, etc.)
*/
CREATE TABLE plot_likes (
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL,            -- Minecraft UUID oder account_id
    
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    
    PRIMARY KEY(plot_id, user_id)
);

/*
    Denormalisierte Like-Statistiken für schnelle Abfragen
*/
CREATE TABLE plot_likes_stats (
    plot_id             UUID PRIMARY KEY REFERENCES plots(id) ON DELETE CASCADE,
    likes_total         INT DEFAULT 0,            -- Alle Likes ever
    likes_24h           INT DEFAULT 0,            -- Letzte 24 Stunden
    likes_7d            INT DEFAULT 0,            -- Letzte 7 Tage
    likes_30d           INT DEFAULT 0,            -- Letzte 30 Tage
    updated_at          TIMESTAMP DEFAULT now()
);

/*
    Social Features: Bookmarks (Interessante Plots markieren)
*/
CREATE TABLE plot_bookmarks (
    id                  UUID PRIMARY KEY,
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL,            -- Minecraft UUID oder account_id
    
    note                TEXT,                     -- Persönliche Notiz zum Bookmark
    tags                JSON DEFAULT '[]',        -- Eigene Tags ['inspiration', 'to-visit', 'favorite']
    
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    
    UNIQUE(plot_id, user_id)
);

/*
    Plot-Besuchs-Historie (für Statistiken)
    Zeitbasiert für Peak-Analysen und Trends
*/
CREATE TABLE plot_visits (
    id                  BIGSERIAL PRIMARY KEY,
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    visitor_id          UUID NOT NULL,            -- Minecraft UUID
    
    visit_source        VARCHAR(16),              -- 'web', 'ingame', 'teleport'
    duration_seconds    INT,                      -- Wie lange geblieben? (optional)
    
    visited_at          TIMESTAMP NOT NULL DEFAULT now()
);

/*
    Denormalisierte Visit-Statistiken für schnelle Abfragen
*/
CREATE TABLE plot_visits_stats (
    plot_id             UUID PRIMARY KEY REFERENCES plots(id) ON DELETE CASCADE,
    visits_total        INT DEFAULT 0,            -- Alle Besuche ever
    visits_30min        INT DEFAULT 0,            -- Letzte 30 Minuten (Peak-Indicator)
    visits_24h          INT DEFAULT 0,            -- Letzte 24 Stunden
    visits_7d           INT DEFAULT 0,            -- Letzte 7 Tage (für Ranking)
    last_visit_at       TIMESTAMP,                -- Letzter Besuch
    updated_at          TIMESTAMP DEFAULT now()
);

-- Plot Indices
CREATE INDEX idx_plots_alias ON plots(alias) WHERE alias IS NOT NULL;
CREATE INDEX idx_plots_visibility ON plots(visibility) WHERE visibility = 'public';
CREATE INDEX idx_plots_category ON plots(category) WHERE category IS NOT NULL;
CREATE INDEX idx_plots_featured ON plots(featured, featured_until) WHERE featured = true;
CREATE INDEX idx_plots_activity ON plots(last_activity_at DESC);

CREATE INDEX idx_screenshots_plot ON plot_screenshots(plot_id, sort_order);
CREATE INDEX idx_screenshots_primary ON plot_screenshots(plot_id) WHERE is_primary = true;

CREATE INDEX idx_plot_likes_plot ON plot_likes(plot_id);
CREATE INDEX idx_plot_likes_user ON plot_likes(user_id);

CREATE INDEX idx_plot_bookmarks_plot ON plot_bookmarks(plot_id);
CREATE INDEX idx_plot_bookmarks_user ON plot_bookmarks(user_id);

CREATE INDEX idx_plot_visits_plot ON plot_visits(plot_id, visited_at DESC);
CREATE INDEX idx_plot_visits_visitor ON plot_visits(visitor_id, visited_at DESC);
CREATE INDEX idx_plot_visits_recent ON plot_visits(plot_id, visited_at DESC) WHERE visited_at > NOW() - INTERVAL '7 days';

CREATE INDEX idx_plot_likes_recent ON plot_likes(plot_id, created_at DESC) WHERE created_at > NOW() - INTERVAL '30 days';

-- ============================================================================
-- PLOT MEMBER MANAGEMENT (Owner, Trust, Deny, Kick)
-- ============================================================================

/*
    Plot-Mitglieder & Vertrauens-Verwaltung
    
    Rollen:
    - owner: Vollzugriff (nur 1 pro Plot)
    - trusted_online: Trust wenn Owner ONLINE/auf Plot (temporär, Session-basiert)
    - trusted_offline: Trust wenn Owner OFFLINE (dauerhaft)
    - member: Bauen erlaubt
    - guest: Nur Besuchen/Warpen
    - denied: Gebannt (inkl. Wildcard-Patterns)
    
    Beispiele:
    - /plot trust Spieler123 → trusted_offline (permanent)
    - /plot trusted Spieler456 → trusted_online (nur diese Session)
    - /plot deny Spieler789 → denied
    - /plot deny *spam* → denied (Wildcard-Pattern)
    - /plot kick Spieler111 → Sofort rauswerfen + temp. block
*/
CREATE TABLE plot_members (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    player_id           UUID NOT NULL,            -- Minecraft UUID
    player_name         VARCHAR(16),              -- Cached name für schnelle Lookups
    
    role                VARCHAR(20) NOT NULL,     -- owner, trusted, member, guest, denied
    
    -- Unterschied zwischen Trust vs. Member Add
    permission_mode     VARCHAR(20) NOT NULL DEFAULT 'permanent',  -- 'permanent', 'owner_online'
    /*
      permanent: Gilt immer (wie 'trusted')
      owner_online: Nur wenn Owner gerade online ist (wie 'member add')
      
      Permanente Speicherung in DB, aber zur Runtime wird geprüft:
      - permission_mode='owner_online' AND owner.online=false → Zugriff verweigert
    */
    
    -- Für trust_online: Wer hat getrustet + wann?
    trusted_by          UUID,                     -- Owner UUID (wer hat es gegeben)
    trusted_at          TIMESTAMP,                -- Wann getrustet
    
    -- Für Bans: Grund + von wem
    deny_reason         VARCHAR(256),             -- Warum gebannt?
    denied_by           UUID,                     -- Owner UUID (wer hat gebannt)
    denied_at           TIMESTAMP,                -- Wann gebannt
    
    -- Ablauf für trust_online
    expires_at          TIMESTAMP,                -- Läuft ab nach Session/Timeout
    
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);

/*
    Temporary Kicks (schnelle Rauswürfe, z.B. /plot kick)
    Automatisch gelöscht nach 30 Minuten oder bei Neustart
*/
CREATE TABLE plot_kicks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    player_id           UUID NOT NULL,            -- Minecraft UUID
    
    kicked_by           UUID NOT NULL,            -- Owner UUID
    kicked_at           TIMESTAMP NOT NULL DEFAULT now(),
    expires_at          TIMESTAMP NOT NULL DEFAULT (now() + INTERVAL '30 minutes'),
    
    reason              VARCHAR(256)
);

-- Plot Member Indices
CREATE INDEX idx_plot_members_plot ON plot_members(plot_id);
CREATE INDEX idx_plot_members_player ON plot_members(player_id);
CREATE INDEX idx_plot_members_role ON plot_members(plot_id, role);
CREATE INDEX idx_plot_members_denied ON plot_members(plot_id, player_name) WHERE role = 'denied';
CREATE INDEX idx_plot_members_trusted_online ON plot_members(plot_id, expires_at) WHERE role = 'trusted_online' AND expires_at > now();

CREATE INDEX idx_plot_kicks_plot ON plot_kicks(plot_id);
CREATE INDEX idx_plot_kicks_expires ON plot_kicks(expires_at) WHERE expires_at > now();

-- ============================================================================
-- PLOT STATS VISIBILITY (Per-Stat Privacy Control)
-- ============================================================================

/*
    Individual Stat-Sichtbarkeit
    Owner kann entscheiden, welche Stats öffentlich sind
    
    Beispiele:
    - likes_public: true   → Likes sind öffentlich sichtbar
    - visits_public: false → Besuche sind privat (nur Owner)
    - views_public: true   → Web-Views sind öffentlich
*/
CREATE TABLE plot_stats_visibility (
    plot_id             UUID PRIMARY KEY REFERENCES plots(id) ON DELETE CASCADE,
    
    -- Individual Stats
    likes_public        BOOLEAN DEFAULT true,     -- Likes sichtbar?
    visits_public       BOOLEAN DEFAULT true,     -- Besuche sichtbar?
    views_public        BOOLEAN DEFAULT true,     -- Web-Views sichtbar?
    bookmarks_public    BOOLEAN DEFAULT false,    -- Bookmarks sichtbar? (meist privat)
    
    -- Whitelist für spezifische Stats
    stats_whitelist     JSON DEFAULT '[]',        -- User UUIDs die alle Stats sehen dürfen
    /*
      ["owner-uuid", "trusted-friend-uuid", ...]
      Diese User sehen auch private Stats (z.B. visits wenn visits_public=false)
    */
    
    updated_at          TIMESTAMP DEFAULT now()
);

-- ============================================================================
-- BROADCASTING & MODERATION
-- ============================================================================

/*
    Broadcasting Settings & Logs
    Nur Admin/Mod/Supporter können Nachrichten broadcasten
    (Falls Owner diese Rolle hat)
*/
CREATE TABLE plot_broadcasts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plot_id             UUID NOT NULL REFERENCES plots(id) ON DELETE CASCADE,
    
    broadcaster_id      UUID NOT NULL,            -- Admin/Mod/Supporter UUID
    broadcaster_role    VARCHAR(20) NOT NULL,     -- admin, mod, supporter
    
    message             VARCHAR(256) NOT NULL,    -- Broadcast-Nachricht
    broadcast_at        TIMESTAMP NOT NULL DEFAULT now()
);

-- Stats Visibility Indices
CREATE INDEX idx_stats_visibility_whitelist ON plot_stats_visibility USING GIN (stats_whitelist);
CREATE INDEX idx_broadcasts_plot ON plot_broadcasts(plot_id, broadcast_at DESC);
