# Leaderboard Page

`/leaderboard` is a public-facing stats page. It serves a leaderboard of top Discord and Minecraft members based on the analytics data Warden has captured. Useful for community engagement and as a public "what is alive on this server" surface.

---

## What it shows

By default, the leaderboard surfaces:

- Top members by Discord messages (last 30 days)
- Top members by voice channel time (last 30 days)
- Top players by Minecraft playtime (last 30 days)
- Server-wide totals: total messages, voice hours, MC sessions

The exact mix is controlled by which `daily_metrics` rollups have populated. Empty leaderboards render with a placeholder ("no activity yet") so the page is not blank on a fresh install.

---

## Enabling

The leaderboard is gated by [Dashboard Permissions](Dashboard-Permissions.md). By default it is hidden until you explicitly mark it public on the **Levels** tab or on the Permissions page.

---

## Customising

The template at `plugins/Warden/www/leaderboard.html` is unpacked on first boot and never overwritten. Edit freely. Placeholders include `{{top_discord_messages}}`, `{{top_voice_minutes}}`, `{{top_mc_hours}}`, `{{server_name}}`, `{{brand_image_url}}`, and all the standard landing placeholders.

Refer to the in-app Template fields reference on `/dash/config` -> Landing tab for the live list.

---

## Privacy

Only public Discord display names and Minecraft player names are shown. No IPs, no email, no internal ids. Members who want to be excluded can be hidden by adding their Discord id to the opt-out list on `/dash/levels` (see [Dashboard Levels](Dashboard-Levels.md)).

---

## Rate limits

The leaderboard route is cached for 60 seconds per request to keep the database off the critical path. Repeat visits within that window are served from memory. There is no per-IP rate limit on this route beyond the global 20-per-minute cap on public `/` routes.
