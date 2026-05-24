# Dashboard: Levels

`/dash/levels` is the XP / leveling system: members earn XP from Discord activity, rank up, and unlock role rewards. Visible to **Config admin**.

---

## Concept

Every Discord message earns the author a randomised amount of XP (within a configurable range), subject to a per-user cooldown. XP accumulates and crosses level thresholds; each level can grant a role, post a public announcement, or both.

The leveling system is **off by default**. Enable on the page with a single toggle.

---

## Settings

| Field | Purpose |
|---|---|
| **Enabled** | Master switch |
| **Min XP per message** | Lower bound of the randomised earn (default 15) |
| **Max XP per message** | Upper bound (default 25) |
| **Cooldown** | Minimum seconds between XP-earning messages from the same user (default 60) |
| **Level-up channel** | Where the public level-up message is posted (or "DM only" to skip the channel post) |
| **Level-up message** | Template string with `{{member_mention}}`, `{{level}}` placeholders |
| **Rank card accent** | Hex colour for the per-user rank card |
| **Rank card background** | Optional URL for a custom background image |
| **Public leaderboard enabled** | Toggle for `/leaderboard` exposing the top-ranked members |

---

## Role rewards

A reorderable list of (level threshold, role) pairs. On reaching the threshold, the role is granted.

- **Stacking**: if on, lower-level rewards are kept when higher ones are earned. If off, the previous reward role is removed when the next one is granted (single-role progression).
- **Add reward** opens a modal for level and role.

---

## XP multipliers

A list of (target, multiplier) pairs. Targets can be:

- A role (e.g. `Patron` x 2.0 to give boosters extra XP)
- A channel (e.g. `#general` x 1.5 to encourage activity there)

Multipliers stack multiplicatively when a message hits more than one.

---

## Rank cards

Each member has a rank card accessible via the Discord slash command `/rank` (and on the `/leaderboard` page if public). The card shows:

- Avatar
- Username
- Current level
- Progress bar to next level
- Total XP

The accent colour and background image are customised on this page. See [Leaderboard Page](Leaderboard-Page.md) for the public view.

---

## Opt-out

Members can opt out of the leveling system entirely via a slash command. Opted-out members earn no XP and are excluded from the leaderboard. The opt-out list is shown at the bottom of the page; mods can add or remove ids.
