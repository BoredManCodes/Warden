# Dashboard: Moderation

`/dash/moderation` is the automod, raid protection, and warnings hub. Visible to **Config admin**.

---

## Tabs

### Automod

Configurable content filters with thresholds and action escalation:

- **Spam** (repeated messages)
- **Caps** (% uppercase per message)
- **Mentions** (count per message)
- **Emoji flood** (emoji density)
- **Zalgo** (combining-character text)
- **Invites** (Discord invite links)

Each filter has:

- Enable toggle
- Threshold (e.g. "above 70% caps in messages longer than 20 chars")
- Action: delete, warn, timeout, kick, ban
- Ignore-roles list (e.g. exempt the `Trusted` role)
- Ignore-channels list (e.g. allow caps in `#bot-spam`)

### Raid protection

Detects join bursts and applies a configured response.

- **Threshold**: N joins within M seconds
- **Action**: kick / ban the burst joiners, or log-only (notify mods but take no action)
- **Cooldown**: how long the raid response stays active

A "raid active" banner appears at the top of `/dash/moderation` while the response is engaged. Manual override buttons let you end it early or extend it.

### Warnings

Immutable history of warnings issued by automod or by mods. Per-row:

- Member name
- Warning reason (template string or free text)
- Issuing actor (mod or automod)
- Timestamp
- **Clear single** button (does not delete the row; marks it inactive)

A member's accumulated active warnings affects automod's escalation logic (third warning auto-escalates to timeout, etc.).

---

## Audit trail

Every automod action and every raid response writes to `audit_log` with the triggering message id, the filter name, and the chosen action. View on [Dashboard Audit](Dashboard-Audit.md) or filter by `actor_type=bot, action=automod`.

---

## Common pitfalls

- **Members in the `@everyone` role tripping caps filter on legitimate excited chat**: add common high-traffic channels to the ignore-channels list, or raise the threshold for the caps filter
- **Bots posting embedded links that look like invites**: add bot user ids to the ignore-roles list (give a `bots` role to your bots)
- **First-time joiners getting banned by raid protection during normal organic growth**: dial up the threshold (e.g. 10 joins in 30s, not 3 joins in 30s); the default values are calibrated for active small communities
