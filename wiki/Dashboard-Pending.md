# Dashboard: Pending

`/dash/pending` is the review queue for applications waiting on a human decision. Visible to **Mod and above**.

---

## What lands here

An application reaches the pending queue when:

- The triage mode is **Mods only** (every application waits for a human), or
- The triage mode is **LLM auto** and the LLM's confidence fell below the threshold (escalated), or
- The triage mode is **LLM auto** and the LLM call failed (escalated for safety)

Auto-approved and LLM-auto-approved applications skip this queue entirely and go straight to the audit log.

---

## What you see per row

- Discord username + avatar
- Time since submission
- Triage outcome so far (LLM verdict if any, with confidence)
- Quick **Approve** / **Deny** buttons

Click into a row for the full detail page:

- All questions and answers
- Full LLM reasoning if the LLM was consulted (including the verdict, confidence, and reasoning text)
- A note field for the mod's decision (becomes part of the audit log)
- An option to DM a custom rejection reason on deny

---

## Approve / Deny

**Approve** swaps the gated role for `full_role_id`, posts the configured welcome message, and writes an `audit_log` entry attributing the decision to you.

**Deny** opens a modal asking for the deny action (per `deny_action` setting: leave / strip_gated / kick / ban) and an optional DM message to the applicant. Submitting performs the action and writes to the audit log.

---

## Bulk actions

The queue list supports multi-select with a header checkbox. Bulk Approve and Bulk Deny apply the same action to every selected application. Useful when a wave of similar joiners (e.g. a community influx after a video) all answered correctly and a single mod's attention can clear them.

---

## From Discord

Warden also posts an embed for each escalated application in `mod_review_channel_id` (configured on the Roles tab). Mods can approve / deny directly from the embed buttons; the action is identical to clicking from the dashboard and is attributed to the Discord user who clicked.

---

## Empty state

When the queue is empty, the page shows a friendly "all caught up" message with a count of total decisions made in the last 7 days. Soothing.
