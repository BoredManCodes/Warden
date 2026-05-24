# Dashboard: Engagement

`/dash/engagement` is the community-engagement toolkit: polls, giveaways, and reminders. Visible to **Mod and above**.

---

## Polls

Create a Discord poll with question, options, and end time. Options:

- **Anonymous**: tally without showing who voted
- **Multi-vote**: members can pick more than one option
- **End time**: poll auto-closes at this time; the final tally is posted as a follow-up

Active polls appear in the list with live vote counts. Closed polls remain in the list as historical records.

---

## Giveaways

Run a Discord giveaway with prize, winners count, and entry method.

- **Prize**: free text shown in the giveaway embed
- **Winners**: how many to draw
- **Required role**: only members with this role can enter
- **Required activity**: optional, e.g. "must have sent at least 10 messages in the last 30 days"
- **End time**: when the draw happens; winners are pinged in the channel

After the draw, the page shows the winners; a **Reroll** button picks new winners if the original winners are unreachable.

---

## Reminders

Set a one-shot or recurring reminder posted by the bot. Useful for:

- "Reminder: server reset is Saturday 18:00 UTC"
- "Weekly: post the Patreon thank-you message every Sunday"

Recurring reminders use the same scheduler as `/dash/scheduler` (cron-like syntax). One-shot reminders just take a target timestamp.

---

## Slash commands

All three features (polls, giveaways, reminders) can also be created via Discord slash commands by users with the mod role; the dashboard is just a richer editor / browser. Use whichever workflow suits the moment.

---

## Audit

Poll creation, giveaway draws, and reminder posts all write to `audit_log`. View on [Dashboard Audit](Dashboard-Audit.md).
