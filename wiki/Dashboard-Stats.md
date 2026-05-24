# Dashboard: Stats

The `/dash/stats/*` family is the analytics dashboard. Four pages, each focused on a different cut of the captured data. See [Analytics](Analytics.md) for what is captured and how.

Default audience: **Mod and above**. Some sub-pages can be exposed publicly via [Dashboard Permissions](Dashboard-Permissions.md) (for example, a stripped-down public Activity page powered by the same rollups).

---

## Overview: /dash/stats

Headline cards at the top:

- Total members
- Discord MAU (monthly active)
- Minecraft MAU
- Pending review count
- Approval rate (last 30d)
- Average time-to-decision

Below the cards:

- 90-day activity timeline (Discord joins / Discord leaves / MC unique logins per day, stacked area)
- Onboarding funnel for the last 30 days (joined Discord -> DM delivered -> entered onboarding -> rules accepted -> answers submitted -> approved / denied / escalated)
- Decision-mix donut: auto-approved / mod-approved / auto-denied / mod-denied / still escalated
- Linked-account ratio: percentage of approved members that linked an MC account via DiscordSRV
- Invite sources: top invite codes by 30-day join counts (with labels from [Dashboard Invites](Dashboard-Invites.md))

---

## Activity: /dash/stats/activity

- Discord messages per day (channel-stacked area, last 60d)
- Top channels by message volume (last 7d, table)
- DAU / WAU / MAU lines for Discord and Minecraft side by side
- Hour-of-day heatmap: when is the server alive
- Most active members (Discord and MC)
- Most active voice channels

---

## Retention: /dash/stats/retention

A weekly cohort table: rows are cohorts of new joiners by their join week, columns are D1 / D7 / D30 retention. Three filters apply the cohort definition:

- **Joined Discord**: any member who joined the guild that week
- **Joined MC**: members who also logged in on the Minecraft server
- **Approved + linked**: members who finished onboarding and linked their MC account

Survival-curve overlay below the table for the same three cohorts.

---

## Geography: /dash/stats/geo

World map of MC join origins from GeoIP. Top countries table to the right. Discord-side cannot be mapped (JDA does not expose user IPs).

If GeoIP is not configured, this page shows "GeoIP not configured" with a link to [Configuration Reference](Configuration-Reference.md). See `analytics.geoip.*`.

---

## Moderation: /dash/stats/mod

- Bans over time (Discord and MC, two series)
- Reason distribution (from the audit log)
- Mod actions per moderator over the last 30d (approvals, denials, manual reonboards). Useful for shift planning, not for ranking people.

---

## Charts

Charts are rendered client-side with ApexCharts. The endpoints under `/dash/api/stats/*` return JSON; the page is a thin template that pulls from those endpoints. Useful if you want to build your own UI on top of the same data.

---

## Performance

All charts read from `daily_metrics` and `weekly_cohorts`, never from raw event tables. The rollup jobs run nightly; if you want fresher data, the **Refresh now** button at the top of each page re-runs the rollup for the visible date range. Manual refreshes are rate-limited to once every 5 minutes per user.
