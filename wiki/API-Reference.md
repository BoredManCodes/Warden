# API Reference

Warden exposes a JSON HTTP API under `/api/v1`. Every endpoint except the index is protected by a bearer token minted on [Dashboard: API Keys](Dashboard-API-Keys.md).

---

## Quick start

1. Open [`/dash/api-keys`](Dashboard-API-Keys.md), tick the scopes you need, and mint a key. Copy the plaintext.
2. Call any endpoint with `Authorization: Bearer <token>`:

   ```bash
   curl -H "Authorization: Bearer WRDN-xxxx.xxxx" \
        "https://your-server.example/api/v1/members?limit=25"
   ```
3. For an interactive console, open `/api/docs`, click **Authorize**, paste your token. Every endpoint becomes try-it-out callable from the browser.

---

## Discovery endpoints

These three are always reachable and **do not require a token**:

| Path | Returns |
| --- | --- |
| `GET /api/v1` | Module + scope index. Useful for capability detection. |
| `GET /api/v1/openapi.json` | The live OpenAPI 3.1 document. CORS-open. |
| `GET /api/docs` | Swagger UI, served from the plugin (CDN-backed Swagger JS). |

The OpenAPI document is built at request time from the same scope enum the runtime enforces, so it always matches the deployed jar.

---

## Authentication

Pass the token in the standard bearer header:

```
Authorization: Bearer WRDN-xxxx.xxxx
```

Responses:

- `200` - success
- `401` - missing, malformed, unknown, or revoked token
- `403` - token is valid but missing the scope this endpoint requires
- `404` - resource not found (e.g. unknown member id, deleted application)

Errors are returned as:

```json
{ "error": "forbidden", "message": "API key is missing required scope: read:members" }
```

---

## Endpoints by module

Scopes shown in parentheses. **Bold** = write action.

### Health

- `GET /api/v1/health` (`read:health`) - plugin status, Discord ready, SSL active, server time

### Members (`read:members`)

- `GET /api/v1/members?limit=` - paginated member list
- `GET /api/v1/members/{discordId}` - single member

### Audit (`read:audit`)

- `GET /api/v1/audit?actor=&action=&target=&limit=` - filterable audit log

### Onboarding (`read:onboarding` / `write:onboarding`)

- `GET /api/v1/onboarding/pending` - applications awaiting review
- `GET /api/v1/onboarding/applications?discord_id=` - application history for a user
- `GET /api/v1/onboarding/applications/{id}` - application detail + answers + LLM verdict
- **`POST /api/v1/onboarding/applications/{id}/approve`** - apply approve flow. Body: `{"note": "..."}`
- **`POST /api/v1/onboarding/applications/{id}/deny`** - apply deny flow. Body: `{"note": "...", "user_dm": "optional override DM"}`

### Moderation (`read:moderation` / `write:moderation`)

- `GET /api/v1/moderation/warnings?discord_id=&limit=`
- **`POST /api/v1/moderation/warnings`** - body: `{"discord_id": "...", "reason": "...", "severity": 1}`
- **`POST /api/v1/moderation/warnings/{id}/clear`**
- `GET /api/v1/moderation/actions?discord_id=&limit=` - kicks, bans, tempbans, mutes

### Levels (`read:levels`)

- `GET /api/v1/levels/leaderboard?limit=`
- `GET /api/v1/levels/{discordId}` - XP, level, message count, rank

### Tickets (`read:tickets`)

- `GET /api/v1/tickets?status=&limit=` - status one of `open|in_progress|resolved|closed`
- `GET /api/v1/tickets/{id}` - ticket + message thread (user + staff + system + internal notes)

### Feedback (`read:feedback`)

- `GET /api/v1/feedback?limit=` - suggestions with vote tallies

### Reaction roles (`read:reaction-roles`)

- `GET /api/v1/reaction-roles` - groups with their option list

### Engagement (`read:engagement`)

- `GET /api/v1/engagement/polls` - open polls
- `GET /api/v1/engagement/giveaways` - open giveaways

### Autoresponders (`read:autoresponders`)

- `GET /api/v1/autoresponders` - all trigger rules

### Alerts (`read:alerts`)

- `GET /api/v1/alerts` - all defined alert rules

### Scheduler (`read:scheduler`)

- `GET /api/v1/scheduler/events?upcoming=true` - scheduled events; pass `upcoming=true` to skip past or cancelled ones

### Timezones (`read:timezones`)

- `GET /api/v1/timezones/{discordId}` - IANA tz id + source + last updated

### Analytics (`read:analytics`)

- `GET /api/v1/analytics/overview` - member, application, level totals

### Minecraft (`read:mc-players`)

Player stats are returned live when the player is online, or from the most recent quit-time snapshot under `plugins/Warden/playerdata/<name>.json` when they are offline.

- `GET /api/v1/players/{username}` - player stats. Live snapshot for online players (uses Bukkit getters); cached snapshot for offline players. When the player has never been seen on this server, returns `{ "error": true, "message": "Player not online, or not found" }`.

  Keys returned: `username, uuid, health, food, world, experience, level, deaths, kills, jumps, gamemode, bed (when set), time, death, address, lastJoined, online, location`. Numeric stats are emitted as strings for backward compatibility with existing consumers; `lastJoined` is unix ms; `online` is the only boolean.

- `GET /api/v1/discord/name/{username}` - Discord account linked to a Minecraft username. Requires DiscordSRV to be installed.
- `GET /api/v1/discord/id/{uuid}` - Discord account linked to a Minecraft UUID (canonical hyphenated form). Requires DiscordSRV.

  Keys returned: `error, username, uuid, discordId, discordName, discordGlobalName`. `discordName` is the post-pomelo unique handle; `discordGlobalName` is the display name (falls back to `discordName` if the user has not set one). The legacy `discordTag` field is intentionally not emitted - discriminators no longer exist.

`address` exposes the player's last-known IP. The `read:mc-players` scope is the gate: only mint keys with this scope for integrations that need it, and prefer per-integration keys you can revoke individually.

---

## Response conventions

- Discord ids and other long numerics are emitted as JSON **strings**, so JavaScript clients don't lose precision.
- Timestamps are unix milliseconds.
- List endpoints wrap rows in `{ "items": [...] }`. A few list endpoints also include a count field (`total`, `count`) at the top level when it can be computed cheaply.
- Optional fields are `null` rather than omitted, with a few exceptions where omission is meaningful (e.g. `closed_at` on an open ticket).

---

## Rate limits

The API shares the global Javalin connector with the dashboard. The per-IP rate limits documented at [Rate Limits](Rate-Limits.md) for `/onboard/*` and `/auth/*` do **not** apply to `/api/v1`. If you need API rate limiting, terminate it at your reverse proxy.

Polling friendly behaviour:

- `/api/v1/health` is intentionally cheap and unauthenticated rate-limit-wise; safe to poll once per second.
- Endpoints that return large lists honour a `limit` query parameter capped at 1000.

---

## Auditing

Every write action records to `audit_log` with `actor = 'api'`. The `payload_json` includes:

- `id` - id of the API key used
- `label` - its label
- `prefix` - public prefix

So you can attribute a moderation action or an onboarding decision back to the specific key (and through the key's `created_by`, to the admin who minted it).

Key lifecycle events (`api_key_create`, `api_key_revoke`, `api_key_delete`) are recorded the same way.

---

## SDK / client tips

- **Curl + jq**: the API is JSON in / JSON out, so `curl ... | jq` covers most ops scripts.
- **Postman / Insomnia**: import `/api/v1/openapi.json` directly.
- **TypeScript / Python**: run `openapi-typescript` or `openapi-python-client` against `/api/v1/openapi.json` for typed clients.
- **CI / cron**: use a single-purpose key (e.g. labelled `nightly-export`) with only the read scopes the script needs. Revoke and re-mint whenever the script changes hands.
