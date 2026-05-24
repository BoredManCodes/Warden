# Dashboard: API Keys

`/dash/api-keys` mints, lists, and revokes bearer tokens for the `/api/v1` JSON API. Visible to **Config admin and Owner** only, same gate as [Permissions](Dashboard-Permissions.md) and [HTTPS Panel](Dashboard-HTTPS.md).

---

## What an API key is

A long bearer token of the shape:

```
WRDN-<24 random url-safe chars>.<24 random url-safe chars>
```

The half before the dot is the **prefix**: the dashboard keeps it in plain text so you can tell keys apart in the table. The half after the dot is the **secret**: only its SHA-256 hash lives in the database. The full plaintext is shown to you exactly once, the moment the key is created. If you lose it, mint a new one.

Keys carry a fixed list of scopes (see below). A request with a token missing the required scope gets a `403`. A request with an unknown, revoked, or malformed token gets a `401`.

---

## Minting a key

1. Type a label. Free-text; just for your reference (`moderation-bot`, `analytics-export`, `helpdesk-integration`).
2. Tick at least one scope. The form is grouped by module so it is easy to scan.
3. Submit. The page reloads with a green card containing the plaintext token and a one-click copy button. Take it now - it will not be shown again.

The key row in the table below the form now shows the prefix, the scopes, `Active`, and a `never` last-used timestamp.

---

## Scopes

Scopes use the form `read:<module>` or `write:<module>`. Pick the smallest set that the calling integration actually needs.

| Scope | Grants |
| --- | --- |
| `read:health` | Plugin status and module readiness |
| `read:members` | Discord identities tracked by Warden |
| `read:audit` | The audit log |
| `read:analytics` | Aggregate metrics |
| `read:onboarding` | Pending and historical applications, answers, LLM verdict |
| `write:onboarding` | Approve, deny applications |
| `read:moderation` | Warnings and mod actions |
| `write:moderation` | Create warnings, clear warnings |
| `read:levels` | XP leaderboard, per-user level |
| `read:tickets` | Tickets and message threads |
| `read:feedback` | Feedback / suggestions |
| `read:reaction-roles` | Reaction-role groups |
| `read:engagement` | Polls and giveaways |
| `read:autoresponders` | Autoresponder rules |
| `read:alerts` | Alert rules |
| `read:scheduler` | Scheduled events |
| `read:timezones` | Per-user timezones |
| `read:mc-players` | Minecraft player stats (`/api/v1/players/{username}`) and Discord-link lookups (`/api/v1/discord/{name,id}/{...}`). Grants access to player IPs - scope only the integrations that actually need it. |

Writing actions log their actor as `api` in `audit_log`. The `payload_json` of an audit row written by an API call includes the key's id, label, and prefix so you can attribute changes back to a specific integration.

---

## Revoke vs delete

Each key in the table has two destructive actions:

- **Revoke** sets `revoked_at` and leaves the row in place. Any subsequent request with the token returns `401`. Choose this when you want to keep the audit trail (which writes the key id and label) intact.
- **Delete** removes the row entirely. Use this for keys created by mistake or for housekeeping after a key has been revoked long enough.

Both actions write to `audit_log` with action `api_key_revoke` or `api_key_delete`.

---

## Calling the API

Pass the token as a bearer header:

```bash
curl -H "Authorization: Bearer WRDN-xxxx.xxxx" \
     "https://your-server.example/api/v1/members?limit=25"
```

The dashboard page renders a small curl example with your actual `public_url` substituted. Full reference and an in-browser try-it-out console are at [`/api/docs`](API-Reference.md). The OpenAPI 3.1 spec itself is at `/api/v1/openapi.json` and is CORS-open so external tooling can pull it directly.

---

## Storage

Keys live in the `api_keys` table:

```
id            INTEGER PRIMARY KEY AUTOINCREMENT
label         TEXT     -- free text from the dashboard
prefix        TEXT     -- WRDN-<24 chars>, the public half
token_hash    TEXT UNIQUE  -- SHA-256(full plaintext)
scopes_json   TEXT     -- ["read:members", ...]
created_by    TEXT     -- Discord id of the admin who minted it, or '' for system
created_at    INTEGER  -- unix ms
last_used_at  INTEGER  -- unix ms, updated on every successful verify
revoked_at    INTEGER  -- unix ms, NULL while active
```

The hash is the unique lookup column: verification reads `WHERE token_hash = ?`, never the prefix or the label. There is no plaintext stored on disk.

---

## Operational notes

- A key with **zero scopes** can authenticate but cannot reach any module endpoint. The create form rejects that case to save you a wasted round trip.
- `last_used_at` is best-effort: a successful verify writes it, but a write failure on that update is logged at `FINE` and ignored so a flaky disk does not block the actual request.
- Tokens are not bound to an IP. If you need IP allow-listing, terminate it at your reverse proxy.
- Revoking and re-minting is the recommended response to a leak. There is no "rotate" action - the prefix is part of the secret, so a new token always has a new prefix too.
