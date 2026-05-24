# Warden

A Paper plugin that ships a complete community surface for your Minecraft + Discord server: configurable Discord onboarding, a role-gated web dashboard, a fully customisable public landing page, optional AI assistance, and an activity analytics layer - all from one fat JAR running inside your existing server JVM.

What started as "DM new joiners a rules-and-questions gate" grew into a small, opinionated all-in-one. Drop the JAR into `plugins/`, fill in a Discord bot token and a session secret, and Warden gives you:

- A configurable onboarding flow (DMs / channel posts / web entry, with optional LLM triage)
- A public landing site at `/` with editable HTML, CSS, hero / brand images, accent colour, PlaceholderAPI-driven counters, FAQ + feature cards
- A mod dashboard at `/dash` gated by Discord OAuth + Discord roles you configure
- Three-tier role-based access: server owner / Config admin / Web manager / Mod
- AI-powered "polish this" buttons on every public-facing string, plus "generate feature cards from the plugins you have installed"
- Built-in event capture for activity analytics (M1 of a Plan-style stats page; rollups + charts are the next milestone)
- A live map embed (Dynmap, Pl3xMap, BlueMap, squaremap) at `/map` if you already run one

Everything except secrets is editable from the dashboard. Nothing requires a recompile or a restart to change.

```
  ┌─ Discord ──────────┐         ┌─ Paper server JVM ──────────────────────┐
  │ New member joins   │ ──────► │ Warden plugin                            │
  │ Bot DMs / posts    │ ◄────── │  ├─ JDA (Discord bot + voice/msg events) │
  │ Mods approve/deny  │         │  ├─ Javalin (dashboard + landing :8788)  │
  │ AI assists copy    │ ◄────── │  ├─ SQLite WAL (config + state + events) │
  └────────────────────┘         │  ├─ OpenAI-compatible LLM (optional)     │
                                 │  └─ PlaceholderAPI bridge (optional)     │
  ┌─ Web visitor ──────┐         │                                          │
  │ /          landing │ ◄────── │                                          │
  │ /rules     rules   │ ◄────── │                                          │
  │ /map       embed   │ ◄────── │                                          │
  │ /onboard   flow    │ ◄────── │                                          │
  │ /dash/*    mod UI  │ ◄────── │                                          │
  └────────────────────┘         └──────────────────────────────────────────┘
```

---

## Requirements

- **Paper 1.21.x** (or any Bukkit-compatible fork - Spigot covers the core APIs Warden uses)
- **Java 21**
- A Discord application + bot ([discord.com/developers/applications](https://discord.com/developers/applications))
- A reachable web port for the dashboard (default `8788`)

Optional but supported:

- An **OpenAI-Responses-compatible LLM gateway** (Manifest, OpenAI, OpenRouter, local Ollama, anything that speaks `POST /v1/responses` with Bearer auth) for triage + AI-polish features
- **DiscordSRV** for resolving Minecraft player names to Discord accounts (used by `/warden reonboard <playerName>`)
- **PlaceholderAPI** for overriding the live-stat counters on the landing page with any placeholder string

---

## Install (5 minutes)

1. Drop `warden-<version>.jar` into your server's `plugins/` directory.
2. Start the server once. Warden creates `plugins/Warden/config.yml`, the SQLite DB at `plugins/Warden/data/warden.db`, and unpacks default templates to `plugins/Warden/www/`.
3. Stop the server. Fill in `plugins/Warden/config.yml` (see [config.yml](#configyml) below).
4. Start the server again. Visit `http://<your-public-url>/` - you'll see a setup checklist that walks you through the rest.

Default state on first run is **silent**: Warden records joins to the audit log but does not message, gate, or DM anyone until you opt in from the dashboard. This avoids surprises on installations into existing communities.

---

## Discord developer portal setup

Three values to copy from your Discord application:

| Value | Where |
|---|---|
| **Bot token** | App → *Bot* → *Reset Token* (copy once; can't be viewed again) |
| **Client id** | App → *General Information* → *Application ID* |
| **Client secret** | App → *OAuth2* → *Reset Secret* |

Privileged intents and the OAuth redirect:

- **Bot** → *Privileged Gateway Intents* → enable **Server Members Intent** and **Message Content Intent**.
- **OAuth2** → *Redirects* → add `https://<your-public-url>/auth/discord/callback` (or `http://localhost:8788/auth/discord/callback` for local dev).

Invite the bot to your server with scope `bot applications.commands` and at minimum: *Manage Roles, Send Messages, Send Messages in Threads, Embed Links, Use External Emojis, View Channels*. The voice + ban events the analytics layer captures need the corresponding intents but Warden requests them automatically.

---

## config.yml

```yaml
discord:
  bot_token: ""              # or set WARDEN_DISCORD_BOT_TOKEN
  client_id: ""              # application id
  client_secret: ""          # or set WARDEN_DISCORD_CLIENT_SECRET
  guild_id: ""               # right-click your server with Developer Mode on → Copy Server ID
  bootstrap_mod_role_id: ""  # optional fallback until you pick mod_role_id in the dashboard

web:
  bind_host: "0.0.0.0"
  bind_port: 8788
  public_url: "http://localhost:8788"   # what visitors see; threads into DM links and OAuth redirects
  session_secret: ""         # auto-generated on first start; or set WARDEN_SESSION_SECRET
  www_dir: "www"             # editable landing/rules/map templates, relative to plugins/Warden/

db:
  file: "data/warden.db"     # relative to plugins/Warden/
```



### Session secret

Warden generates a random 32-byte hex `web.session_secret` on first start and writes it back to `config.yml`. You only need to touch this if you want to supply the value yourself: for example, to keep secrets out of the YAML file and pass them in via the `WARDEN_SESSION_SECRET` env var, or to pre-seed the file from a config-management tool.

Linux / macOS:

```sh
openssl rand -hex 32
```

Windows PowerShell:

```powershell
$b = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
($b | ForEach-Object { '{0:x2}' -f $_ }) -join ''
```

---

### HTTPS

Warden can terminate TLS natively, no reverse proxy required. Point it at a pair of PEM files (same format Let's Encrypt produces) and flip a flag:

```yaml
web:
  bind_host: "0.0.0.0"
  bind_port: 8788
  public_url: "https://warden.example.com:8443"   # what threads into DMs and OAuth redirects
  ssl:
    enabled: true
    port: 8443
    cert_file: "/etc/letsencrypt/live/warden.example.com/fullchain.pem"
    key_file:  "/etc/letsencrypt/live/warden.example.com/privkey.pem"
    redirect_http: true                            # 301 plain HTTP requests to public_url
```

The full walkthrough lives on the **HTTPS** tab of the dashboard (`/dash/https`, Config-admin only). It covers:

- DNS + firewall prerequisites
- Issuing a free Let's Encrypt cert with **certbot** on Linux or **win-acme** on Windows
- Configuring auto-renewal so the cert refreshes itself every 60 days
- Edge cases (port-80 / port-443 binding on Linux, self-signed for testing, NAT scenarios)

Remember to update the Discord Developer Portal redirect to match (`https://warden.example.com:8443/auth/discord/callback`).

#### Reverse-proxy alternative

If you already run nginx / Caddy / Cloudflare Tunnel and prefer to terminate TLS there, leave `web.ssl.enabled` off, keep Warden on plain HTTP bound to `127.0.0.1`, and point `public_url` at the HTTPS hostname your proxy serves. A one-line Caddyfile is enough:

```
warden.example.com {
    reverse_proxy 127.0.0.1:8788
}
```

---

## First-run setup wizard

With `discord.bot_token`, `discord.guild_id`, and `web.session_secret` filled in:

1. Visit `http://<your-public-url>/`. The welcome page shows an 8-step checklist.
2. Click **Sign in with Discord**. Discord server owners and members with the `ADMINISTRATOR` permission are treated as the highest-rank access tier on first sign-in, so you can always get in before any role is configured.
3. You'll land on `/setup/mod-role`. Pick the role that should be treated as your **mod** role from now on (gates `/dash/pending`, `/dash/audit`, `/dash/members`).
4. You'll land on `/dash/config`. The Roles tab lets you set:
   - `gated_role_id` - applied to new joiners (the "limited access" role)
   - `full_role_id` - applied on approval
   - `mod_role_id` - dashboard + Discord button access for mods
   - `config_admin_role_id` - full access to `/dash/config` (**server owner only** can set this)
   - `web_manager_role_id` - Landing-page-only access to `/dash/config` (**server owner only**)
   - Welcome + mod-review channel ids
5. Turn on at least one **Delivery** method (DM, public channel post, or both) and one **Entry** method (Discord button, web code, or web OAuth). Save.
6. *(Optional, recommended once you're ready for the public internet)* Visit **`/dash/https`** for a walkthrough on getting a free Let's Encrypt certificate and switching the dashboard, onboarding flow, and landing page to HTTPS. No reverse proxy required - Warden terminates TLS natively. See [HTTPS](#https) above for the short version.

---

## Access roles

Warden has four tiers, each strictly nested:

| Tier | Sees on the dashboard | Set by |
|---|---|---|
| **Owner** | Everything. Implicit on Discord server owner + anyone with `ADMINISTRATOR` perm in the guild. | Discord |
| **Config admin** | Everything in `/dash/config` (Roles, Delivery, Entry, Triage, AI, Approve, Deny, Rules, Landing, Questions) plus all mod surfaces. | Owner picks `config_admin_role_id` in /dash/config |
| **Mod** | `/dash/pending`, `/dash/audit`, `/dash/members`. Can approve / deny applications. No config access. | Owner / Config admin picks `mod_role_id` |
| **Web manager** | The Landing tab of `/dash/config` only. Designed for delegating cosmetic / marketing edits without granting onboarding-flow control. | Owner picks `web_manager_role_id` |

`/dash/config/*` (questions CRUD, LLM test, AI polish endpoints) is Config-admin-only. The Landing tab is reachable by Web managers. Mods clicking the disabled "Config" sidebar entry see a modal explaining the required role.

---

## The onboarding flow

1. Member joins your guild.
2. Warden assigns `gated_role_id` if gating is on.
3. Per your delivery config: bot DMs the member, posts in a public welcome channel, or both.
4. The message offers entry methods you've enabled:
   - **Start in Discord** button → modal-driven Q&A in DMs
   - **Open on web** link → paste the 8-char code at `/onboard`
   - **Sign in with Discord** at `/onboard` → OAuth, no code needed
5. The member sees your rules (Markdown), then walks through your configured questions (short text / long text / single choice / multi choice, in any order).
6. On submit, the configured **triage mode** decides:
   - **Auto-approve** - immediate approval, no LLM call
   - **Mods only** - queue to `/dash/pending`, no LLM call
   - **LLM auto** - LLM triages, auto approve / deny if confident enough, otherwise escalate to mods
   - **LLM only** - LLM verdict taken verbatim (escalates coerced to deny)
7. On **approve**: bot swaps gated role for `full_role_id`, optionally DMs the welcome message, optionally posts a public welcome.
8. On **deny**: bot DMs a polite rejection (optional), and per `deny_action` either leaves the gated role / strips it / kicks / bans.

Every step writes to `audit_log` so you can replay decisions later from `/dash/audit`.

---

## Public landing site

Three pages live under `/`:

- `/` - the landing template (`plugins/Warden/www/landing.html`). Hero + stats strip + features + community CTA + FAQ. Customisable as far as raw HTML / CSS lets you go.
- `/rules` - the same Markdown you edit on the Rules tab, rendered as HTML inside the shared landing chrome.
- `/map` - an iframe embed of whichever web map plugin you run (Dynmap, Pl3xMap, BlueMap, squaremap, or any URL).

The Landing tab on `/dash/config` exposes:

- Mode: enabled / disabled / redirect-to-URL
- Server name, tagline, server address, Join button URL
- Map provider + URL + nav-link label
- **Brand image URL** (falls back to your Discord guild icon if blank, then to the bundled Warden icon)
- **Hero image URL** (renders as a full-bleed background of the hero section with a dark scrim, replacing the default grid pattern)
- **Accent colour** (hex picker; replaces the `--accent` variable in `landing.css`)
- **Stats labels** - rename "Players online" / "Discord members" to whatever
- **PAPI overrides** - point either stat at any PlaceholderAPI placeholder (e.g. `%server_online%`, `%bedwars_active_games%`). Warden runs fine without PAPI installed; missing placeholders silently fall back to the built-in value.
- Reorderable list of **feature cards** (icon + title + body)
- Reorderable list of **FAQ** entries

A collapsible **Template fields reference** panel at the top of the Landing tab lists every `{{...}}` placeholder the renderer supports, grouped by which file consumes it. Copy-paste straight into your template; no docs round-trip required.

### Bringing your own template

`plugins/Warden/www/` is unpacked on first run with the bundled templates and CSS, then never overwritten - edit anything you want without losing your changes on upgrade. Drop in a fully custom `landing.html`, `landing.css`, and any assets alongside; the files are served at `/www/*`. Reference any of the `{{...}}` placeholders or skip the ones you don't need. Unknown tokens render literally, so typos surface immediately.

---

## AI assistance (optional)

When the AI gateway is configured (`/dash/config` → AI tab → API key + base URL + model), three features unlock:

1. **Triage** - the LLM-auto / LLM-only triage modes (see [The onboarding flow](#the-onboarding-flow)).
2. **Polish with AI** - every editable landing-page string (FAQ question, FAQ answer, feature title, feature body) has a small AI button next to it. Clicking it replaces the text in place with a polished version. **CTRL+Z works**: the replacement uses `execCommand('insertText')` so the browser's native undo stack restores the original wording.
3. **Generate features from plugins** - a button at the top of the feature-cards editor that asks your LLM to draft N feature cards based on the actual plugins installed on your server (read via Bukkit's `PluginManager`). Optional *Replace existing* toggle, count picker (1–12). Result lands in the editor for you to edit / polish / re-order before saving.

The gateway URL accepts any OpenAI-Responses-compatible endpoint: the official OpenAI API, [Manifest](https://app.manifest.build), OpenRouter, a local Ollama instance, etc. Test the connection without saving via the **Test connection** button on the AI tab - Warden asks for a joke, displays the latency + response, and tells you exactly what failed if it did.

Triage failures (rate limit, network, malformed JSON) **escalate** the application, never silently lose it. Retries cover transient 429 / 5xx / IO with exponential backoff + full jitter (3 retries, 500 ms base, 8 s cap); 4xx config errors fail fast.

---

## In-game commands

```
/warden status                                      # are JDA and the web server up?
/warden reload                                      # re-read config.yml; reports which keys need a full restart
/warden reonboard <discordId | playerName>          # reset a user's state, clear answers, replay delivery
```

Requires the `warden.admin` permission (default: op).

- **reload** re-reads `config.yml` and lists any boot-bound keys (bot token, web port, OAuth ids, DB path, session secret) that need a server restart to take effect. Dashboard-managed settings are always live.
- **reonboard** accepts either a raw 15–21 digit Discord snowflake, or - when DiscordSRV is installed - a Minecraft player name. Clears all of the user's prior answers, resets their state to `pending_link`, and replays the onboarding delivery if they're still in the guild.

---

## Analytics (M1 - event capture live, rollups + UI in progress)

Warden captures the raw events needed for a Plan-style analytics page:

- Discord message volume per channel / per user (body **never stored** - char count + attachment / reply flags only)
- Discord join / leave / ban / unban / kick events
- Discord voice channel sessions (one row per continuous stretch in one channel)
- Minecraft login sessions (UUID, name snapshot, hashed IP, optional country, client brand)

All writes are queued onto a single background executor so JDA's gateway thread and Bukkit's main thread never block on SQLite. IPs are hashed with a per-install random salt; raw IPs are never persisted. Orphan sessions from crashed shutdowns are auto-closed on startup.

The dashboard page (`/dash/stats`) and rollup job that turn these into charts are the next milestone. The schema and capture path are stable.

See `ANALYTICS_PLAN.md` for the full design.

---

## Dashboard tour

`https://<public_url>/dash/*`, gated by Discord OAuth.

- **`/dash/pending`** (Mod+) - applications waiting for human review. LLM reasoning + confidence shown inline; Approve / Deny with optional note.
- **`/dash/audit`** (Mod+) - filterable timeline of every decision the bot, LLM, mods, or web has taken.
- **`/dash/members`** (Mod+) - server member list with onboarding state, linked-account chip (DiscordSRV), and per-row "reset onboarding".
- **`/dash/config`** (Web manager / Config admin / Owner) - tabbed settings:
  - **Roles** - gated / full / mod / config-admin (owner only) / web-manager (owner only), welcome + review channels
  - **Delivery** - DM and/or public channel post + message template
  - **Entry** - which combination of entry methods to offer
  - **Gating** - whether the gated role is actually applied
  - **Triage** - mod-only / LLM auto / LLM only / auto-approve, with thresholds
  - **AI** - gateway URL, model, key, test button
  - **Approve / Deny** - DM templates, public-channel announce, extra role grants, deny actions
  - **Rules** - Markdown shown at the start of the flow
  - **Landing** - everything for the public site (see above)
  - **Questions** - drag-and-drop reorderable list with per-row Required / Active checkboxes that AJAX-toggle without a save

Mods can also approve / deny directly from the embed Warden posts in your `mod_review_channel_id` when an application escalates.

---

## Rate limits

The public-facing routes are rate-limited per IP:

- `/onboard/redeem` (code-paste endpoint): **8 / minute** - tightened since this is the only brute-forceable surface.
- `/onboard/*` POSTs overall: **20 / minute** - generous enough for the rules + N questions flow.
- `/auth/*` (OAuth start + callback): **30 / minute**.

Exceeding any returns HTTP 429 with a 60-second `Retry-After`.

---

## Building from source

```sh
git clone <repo>
cd warden
./gradlew shadowJar         # produces build/libs/warden-<version>.jar
./gradlew test              # run the JUnit suite
./gradlew copyToTestServer -PtestServer=/path/to/paper/server
```

Dependencies: Java 21 toolchain, no system Gradle (uses the wrapper), no Maven, no Node, no system tooling.

The build:
- shades JDA, Javalin, Jetty, Jackson, Hikari, JTE, sqlite-jdbc, OkHttp, slf4j-jdk14 into the fat JAR under `io.warden.shaded.*` so it can coexist with other plugins
- ships a single `resources/schema.sql` that `SchemaLoader` runs once at startup; statements are idempotent so the loader is a no-op on existing databases

---

## Troubleshooting

**"OAuth state invalid or expired"** - your `public_url` likely doesn't match what your browser sees as the host (`localhost` vs `127.0.0.1` vs LAN IP). State is HMAC-signed and self-validating, so the only way to see this is if more than 10 minutes passed between starting OAuth and returning, or the bot restarted in between.

**"You're signed in, but you're not a mod" / locked out of `/dash/config`** - your session cookie predates a role change. Log out and back in to mint a fresh cookie; the new login reads your current role / owner / admin status.

**Plugin remapper lock error on restart** - the previous JVM didn't shut down cleanly and is still holding `plugins/.paper-remapped/warden-*.jar` open. Kill any leftover `java.exe` and start again. `/stop` in the console always shuts down cleanly; `Ctrl-C` on Windows doesn't.

**JDA "Using fallback logger" warning** - cosmetic. SLF4J's ServiceLoader binding doesn't survive our shading config. The bot works fine.

**Hero / brand image not updating after a config change** - `plugins/Warden/www/landing.html` is unpacked once and never overwritten. If you've upgraded from an older Warden, your template might predate the `{{brand_image_url}}` / `{{hero_image_block}}` placeholders. Delete `landing.html` (and `landing.css` if styling looks off) and restart to get the fresh defaults. Or open the file and add the placeholders manually - they're listed in the in-app Template fields reference panel on the Landing tab.

---

## License

Licensed under the Apache License, Version 2.0. See `LICENSE` for the
full text and `NOTICE` for the project notice. Third-party libraries
bundled into the fat JAR are listed with their licenses in
`THIRD_PARTY_LICENSES.md`.
