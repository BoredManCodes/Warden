# Analytics

Warden captures the raw events needed for a Plan-style stats page covering both Discord and Minecraft activity, anchored on the onboarding funnel. The dashboard surfaces are on `/dash/stats/*`; see [Dashboard Stats](Dashboard-Stats.md) for the per-page tour.

---

## What is captured

| Event | Stored | Source |
|---|---|---|
| Discord message | One row per message: timestamp, channel id, author id, char count, attachment flag, reply flag. **Body is never stored.** | JDA `MessageReceivedEvent` |
| Discord member join / leave / ban / unban / kick | One row per event with timestamp, member id, type, optional reason. | JDA guild member events |
| Discord voice session | One row per continuous stretch in one channel: member id, channel id, start, end, duration. | JDA voice update events |
| Minecraft login session | One row per session: UUID, name snapshot, hashed IP, optional country, client brand, start, end. | Bukkit `PlayerJoinEvent` + `PlayerQuitEvent` |
| Onboarding events | DM delivered, rules accepted, answers submitted, decision (approve / deny / escalate). | Warden internal |
| Mod actions | Approvals, denials, manual reonboards, attributed to mod id. | Warden internal |

All writes are queued onto a single background executor so JDA's gateway thread and Bukkit's main thread never block on SQLite.

---

## IP handling

IPs are hashed with a per-install random salt; raw IPs are never persisted. The salt is generated on first start and stored in the analytics-meta table. The hash is one-way and cannot be reversed.

If GeoIP is enabled and a MaxMind licence key is supplied, the IP is looked up against the local GeoLite2 database **before** hashing, and the country code is stored alongside the hash. See [Configuration Reference](Configuration-Reference.md) for the `analytics.geoip.*` keys.

---

## Voice session edge cases

A "session" is one continuous stretch in one voice channel. Switching channels closes one session and opens another. Disconnects close the current session. Server-wide gateway hiccups occasionally leave orphan sessions; these are auto-closed on startup using the last-known timestamp.

---

## Rollups

A nightly `RollupJob` aggregates raw events into `daily_metrics`:

- Discord joins / leaves / bans / kicks per day
- Discord messages per channel per day
- Discord voice minutes per channel per day
- MC unique sessions per day
- MC playtime minutes per day
- Onboarding funnel counters per day

A weekly `CohortJob` builds the cohort tables on the `/dash/stats/retention` page.

Both jobs are idempotent: re-running them on a day that already has a rollup row updates the row in place. Useful if you ever need to backfill after a downtime.

---

## Dashboard pages

| Path | Page | What it shows |
|---|---|---|
| `/dash/stats` | Overview | Headline cards, 90-day activity timeline, funnel, decision-mix donut, invite sources |
| `/dash/stats/activity` | Activity | Messages per day, top channels, DAU/WAU/MAU, hour-of-day heatmap |
| `/dash/stats/retention` | Retention | Cohort table (D1/D7/D30) and survival curves |
| `/dash/stats/geo` | Geography | World map of MC join origins from GeoIP |
| `/dash/members/{discordId}` | Per-member | Profile card + 90-day activity sparkline |

See [Dashboard Stats](Dashboard-Stats.md) and [Dashboard Members](Dashboard-Members.md) for the full page tour.

---

## ANALYTICS_PLAN.md

The full design is in `ANALYTICS_PLAN.md` at the repo root. It covers schema decisions, retention math, the choice not to map Discord IPs (JDA does not expose them), and reference inspiration from the Plan plugin (read for ideas, do not copy: GPL).

---

## Disabling

Capture cannot be globally disabled from the dashboard (the schema and capture path are stable; rollups are next). If you do not want event capture, the cleanest option is to drop the analytics tables (`discord_messages`, `discord_member_events`, `discord_voice_sessions`, `mc_sessions`, `daily_metrics`, `cohort_membership`) before starting; they are recreated empty on next boot. This is not recommended; the storage cost is tiny (a busy server produces a few MB per month) and the data unlocks every future analytics feature.
