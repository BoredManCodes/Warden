# Dashboard: Feedback

`/dash/feedback` is the community feature-request board. Members submit ideas; the community votes; mods triage. Visible to **Mod and above** for the mod surfaces; member submission and voting can be made public via [Dashboard Permissions](Dashboard-Permissions.md).

---

## Concept

Each entry is one piece of feedback: a feature request, a suggestion, a complaint. Each has:

- Title + description
- Submitter (Discord member)
- Status: **open**, **planned**, **done**, **rejected**
- Vote count (members upvote and downvote)
- Staff response (optional public comment from mods)

Members submit via Discord slash command `/feedback` or via the public web form (if enabled in settings).

---

## Browsing

The board view supports:

- **Filter by status**: see only open, only planned, etc.
- **Sort**: by net votes, by newest, by oldest, by recent activity
- **Search**: substring across title and description

---

## Per-item detail

- Full description with Markdown rendering
- Vote tally with upvote / downvote counts
- Comments thread (members can reply, mods can highlight a comment as the official response)
- Mod actions: change status, pin, lock further comments, delete

Status changes notify the submitter via DM if they have not opted out.

---

## Settings

`/dash/feedback/settings` (Config admin):

- Enable / disable the feedback system globally
- Allow public submission via the web form (vs Discord-only)
- Require N karma / messages before a member can submit
- Submission questions (what fields the form asks for, beyond title + description)
- Auto-archive threshold: items with no activity for N days are auto-archived

---

## Use cases

- **Roadmap visibility**: members see what is being worked on (planned status), what has shipped (done), and what was declined (rejected, with a brief reason)
- **Prioritisation signal**: vote counts give a rough sense of what the community actually wants; useful when planning the next dev cycle
- **Triage queue**: filtering by open + sorted-by-votes is a useful weekly mod review
