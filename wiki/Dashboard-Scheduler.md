# Dashboard: Scheduler

`/dash/scheduler` is the community event calendar: schedule events in UTC, members RSVP, the bot pings them at the right local time. Visible to **Mod and above** for the editor; the public event list at `/tz/events` is open to any signed-in member.

---

## Concept

A community event is something like:

- "Saturday 18:00 UTC: build competition kickoff"
- "Next Tuesday 20:00 UTC: PVP tournament"
- "First Sunday of each month 15:00 UTC: hot drop"

Warden stores the time in UTC, asks each member for their timezone (once, on first encounter), and renders the event time in their local zone when they view it. RSVPs are tracked per member with going / maybe / no.

---

## Creating an event

| Field | Purpose |
|---|---|
| **Title** | Shown in lists and notifications |
| **Description** | Free text, supports Markdown |
| **Start time (UTC)** | When the event begins |
| **End time (UTC)** | Optional; for fixed-duration events |
| **Channel** | Where the event embed is posted |
| **Ping role** | Optional role pinged when the event opens |
| **Reminder lead time** | Optional; reminder DMs sent N minutes before |
| **Repeat** | None / daily / weekly / monthly / custom cron |

On save, Warden posts the event embed to the target channel with RSVP buttons (Going / Maybe / No). The embed updates live as RSVPs come in.

---

## RSVPs

The event detail page on the dashboard shows:

- Going list (with each member's local timezone)
- Maybe list
- No list
- Not yet responded count

The RSVPs feed the reminder DMs: only members who clicked Going or Maybe get the lead-time reminder.

---

## Timezone capture

The first time a member interacts with the scheduler (RSVP or visit `/tz/events`), they are bounced to `/tz` to pick a timezone. The picker has a map UI and a city search; the chosen IANA zone id is stored against their Discord id. No location data is stored, only the zone.

After picking, all future event times are rendered in their zone.

---

## Public event list

`/tz/events` is the read-only public view of upcoming events: title, description, time in viewer's zone, RSVP buttons. No dashboard role required; just a Discord OAuth session.

The page is reachable from the top nav on the landing site if you enable the "Events" nav link on the Landing tab.

---

## Recurring events

For weekly / monthly / cron-style recurrence, Warden generates the next 4 occurrences ahead of time and posts the embed for each. As one passes, the next is queued. This keeps the RSVP UX simple (one embed per occurrence, not one perpetual embed).

If you cancel a recurring event, all future occurrences are removed; past occurrences are retained for the audit log.
