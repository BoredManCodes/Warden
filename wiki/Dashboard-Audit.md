# Dashboard: Audit

`/dash/audit` is the immutable timeline of every decision Warden has ever made: joins recorded, applications submitted, LLM verdicts, mod approvals, denials, manual reonboards, role grants, config changes. Visible to **Mod and above**.

---

## What is logged

Roughly everything that mutates state, attributed to whoever (or whatever) caused it. The actor types are:

- **bot**: Warden took an autonomous action (e.g. applied the gated role on join)
- **llm**: the LLM decided
- **mod**: a moderator clicked Approve / Deny / Reset
- **web**: a config change was saved on the dashboard
- **ingame**: an in-game command like `/warden reonboard`

Each entry has:

- Timestamp (UTC stored, rendered in viewer's timezone)
- Actor type + actor id + actor display name
- Subject (the Discord user the action applied to, where applicable)
- Action (approve, deny, configure, reset, etc.)
- A JSON payload describing the change, including before/after diffs for config edits

---

## Filtering

A filter bar at the top lets you narrow by:

- Date range
- Actor type
- Action type
- Specific Discord user (subject)
- Specific mod (actor)

Filters are URL-encoded so links are shareable.

---

## Search

A search box does substring matching against actor names, subject names, and payload text. Useful for "did anyone ever change the LLM threshold?" or "who approved JimboTheTester?"

---

## Export

A CSV export button on the filter bar exports the currently visible filtered rows. Useful for monthly community reports or forensic review after a moderation incident.

---

## Retention

Audit log entries are kept indefinitely. The table is append-only; nothing is ever deleted, even if the underlying Discord user leaves the guild. If you need to drop old entries to keep the database small, do it via direct SQL on `audit_log`; Warden does not surface a UI for log truncation.

---

## Use cases

- "Who approved this member who is now causing problems?"
- "Did the LLM trigger this denial, or a mod?"
- "Show me everything `mod_role` mods did last week."
- "What did the config look like before the recent rule change?"
- "Replay the decisions for a specific joiner to understand the path they took."
