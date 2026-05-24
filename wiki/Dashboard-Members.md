# Dashboard: Members

`/dash/members` is the member directory: every Discord user known to Warden with their current state, linked Minecraft account (if any), and quick mod actions. Visible to **Mod and above**.

---

## Directory view

The table shows one row per member with:

- Discord display name + avatar
- Current onboarding state (`pending_link`, `awaiting_answers`, `escalated`, `approved`, `denied`, etc.)
- Linked Minecraft username (via DiscordSRV) if any
- Join timestamp
- Quick action: **Reset onboarding** (one click runs the equivalent of `/warden reonboard`)

Filters:

- By state (multiselect)
- By linked / unlinked
- By joined-after date
- Free text search across Discord name and MC name

---

## Per-member detail

Clicking a row opens `/dash/members/{discordId}`, the per-member detail page. This is where you go to understand "who is this person and what have they done":

### Profile card

- Discord name + avatar
- Linked MC name + UUID (if DiscordSRV is installed and the link exists)
- Current state with last state-change timestamp
- Joined timestamp
- Approved timestamp (if approved)
- Current Discord roles snapshot

### Activity sparkline

A 90-day timeline showing:

- Discord messages per day (bar)
- Minecraft sessions per day (line)

Hover for exact counts; click into a day for the audit-log entries for that date.

### Onboarding history

The full step-by-step record of this member's onboarding:

- When delivery happened (DM, channel, or both)
- When they accepted rules
- Each question and the answer they gave
- The triage decision (and LLM reasoning if applicable)
- The mod who actioned the final decision (if any)

### Mod actions

- **Reset onboarding**: equivalent to `/warden reonboard`. Clears answers, sets state to `pending_link`, replays delivery if they are still in the guild.
- **Add note**: free-form private note attached to the member (visible only to mods). Useful for context that does not warrant a Discord-level action.
- **Open in Discord**: deep link that opens the member's Discord profile in the client.

---

## Privacy

The per-member detail page is mod-gated. It exposes onboarding answers (which can be personal), so do not grant mod access lightly. The audit log records who viewed which member detail page; abuse of this surface is therefore visible to other mods on `/dash/audit`.
