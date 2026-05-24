# Configuration Reference

This is the full reference for `plugins/Warden/config.yml`. Everything except a few boot-bound values is editable from the dashboard at `/dash/config` and is applied live; the keys listed here are the ones you actually need to touch in the file.

> **LLM endpoint settings** (API key, base URL, model) are stored in the database, not the YAML file. Configure them at `/dash/config` on the **AI** tab. Legacy `llm.*` keys from older installs are migrated automatically on upgrade.

---

## Top-level shape

```yaml
discord:
  bot_token: ""
  client_id: ""
  client_secret: ""
  guild_id: ""
  bootstrap_mod_role_id: ""

web:
  bind_host: "0.0.0.0"
  bind_port: 8788
  public_url: "http://localhost:8788"
  session_secret: ""
  www_dir: "www"
  ssl:
    enabled: false
    port: 8443
    cert_file: "ssl/fullchain.pem"
    key_file: "ssl/privkey.pem"
    redirect_http: true

db:
  file: "data/warden.db"

metrics:
  enabled: true

analytics:
  geoip:
    enabled: false
    license_key: ""
    edition: "GeoLite2-Country"
    refresh_days: 7
    db_dir: "data/geoip"
```

---

## `discord.*`

| Key | Purpose |
|---|---|
| `bot_token` | Bot user token from the Developer Portal. Required. Env override: `WARDEN_DISCORD_BOT_TOKEN`. |
| `client_id` | Application id (numeric). Required for OAuth and slash commands. |
| `client_secret` | OAuth2 client secret. Required. Env override: `WARDEN_DISCORD_CLIENT_SECRET`. |
| `guild_id` | The Discord server id Warden operates on. Required. |
| `bootstrap_mod_role_id` | Optional role id treated as "mod" until you set `mod_role_id` from the dashboard. Useful for the first sign-in if you do not want to rely on owner/ADMINISTRATOR-permission fallthrough. |

See [Discord Application Setup](Discord-Application-Setup.md) for where each value lives in the portal.

---

## `web.*`

| Key | Purpose |
|---|---|
| `bind_host` | What address the embedded Javalin server binds to. `0.0.0.0` listens on all interfaces, `127.0.0.1` is loopback-only. |
| `bind_port` | Plain HTTP port. Default `8788`. |
| `public_url` | The URL Warden puts into DMs, OAuth redirects, and ticket-transcript links. Must match what visitors actually type (no scheme or host mismatch). |
| `session_secret` | Signs the dashboard + onboarding session cookies. Auto-generated as a 32-byte hex on first start. See [Session Secret](Session-Secret.md). Env override: `WARDEN_SESSION_SECRET`. |
| `www_dir` | Directory (relative to `plugins/Warden/`) holding the editable landing template, CSS, and assets. Defaults to `www`. |

### `web.ssl.*`

| Key | Purpose |
|---|---|
| `enabled` | If true, Warden terminates HTTPS natively on `ssl.port`. |
| `port` | HTTPS port. Default `8443`. |
| `cert_file` | PEM-formatted full chain cert file (same format Let's Encrypt produces). Relative to `plugins/Warden/` unless absolute. |
| `key_file` | PEM-formatted private key. |
| `redirect_http` | If true, plain HTTP requests on `bind_port` are 301'd to `public_url`. |

See [HTTPS](HTTPS.md) for a full walkthrough.

---

## `db.*`

| Key | Purpose |
|---|---|
| `file` | Path (relative to `plugins/Warden/`) to the SQLite database. WAL mode is enabled at runtime. Default `data/warden.db`. |

The schema is loaded on startup from `src/main/resources/schema.sql` via `SchemaLoader`. Every statement is idempotent (`CREATE IF NOT EXISTS` / `INSERT OR IGNORE`), so the loader is safe on both fresh and existing databases.

---

## `metrics.*`

| Key | Purpose |
|---|---|
| `enabled` | Anonymous bStats reporting. Default `true`. Only reports generic server stats (Bukkit version, player count). Disable globally by editing `plugins/bStats/config.yml` instead, if you want to opt out of all bStats reporters at once. |

---

## `analytics.geoip.*`

GeoIP is **off by default**. When enabled, Warden tags Minecraft sessions with the player's country using a MaxMind GeoLite2 database.

| Key | Purpose |
|---|---|
| `enabled` | Master switch. Without a licence key, the geo page on `/dash/stats/geo` shows "GeoIP not configured". |
| `license_key` | Free MaxMind licence key. Sign up at [maxmind.com/en/geolite2/signup](https://www.maxmind.com/en/geolite2/signup). Env override: `WARDEN_GEOIP_LICENSE_KEY`. |
| `edition` | Which GeoLite2 edition to download. Default `GeoLite2-Country`. `GeoLite2-City` works too if you want finer granularity. |
| `refresh_days` | Days between automatic refreshes of the local database. Default `7`. |
| `db_dir` | Directory (relative to `plugins/Warden/`) where the GeoIP database is cached. Default `data/geoip`. |

---

## Boot-bound versus live keys

After editing `config.yml`, running `/warden reload` in-game re-reads the file. The command reports which of the changed keys actually need a full restart to take effect.

**Boot-bound (require restart):**

- `discord.bot_token`, `discord.client_id`, `discord.client_secret`, `discord.guild_id`
- `web.bind_host`, `web.bind_port`, `web.public_url`, `web.session_secret`, `web.ssl.*`
- `db.file`

**Live (applied on reload):**

- `discord.bootstrap_mod_role_id`
- `web.www_dir`
- `metrics.enabled`
- `analytics.geoip.*`

Most settings you would actually want to change after the first install (roles, delivery methods, questions, landing copy, AI gateway) are stored in the database and managed from the dashboard, not the YAML file. Those are always live; no reload required.
