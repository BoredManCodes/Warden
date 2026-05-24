# Dashboard: Invites

`/dash/invites` tracks every invite code Warden has seen, who used it, and lets you attach labels for analytics grouping. Visible to **Mod and above**.

---

## What you see

The table lists every Discord invite code, with:

- Code (e.g. `abc123`)
- Created by (which member made it)
- Created at
- Uses (current count vs. max if set)
- Expires (or "never")
- Status: **active**, **expired**, **exhausted**
- **Label**: free-text label you set; used for analytics grouping

---

## Labels

Invite codes are short and meaningless on their own. Attach a label like `Reddit`, `TikTok`, `Twitter campaign Jan 2026`, `Friend referral`, and your analytics page on `/dash/stats` groups joins by these labels.

Click into a label cell to edit inline; press Enter to save. No round-trip; the label is AJAX-persisted.

---

## Joins by invite source

The chart on `/dash/stats` "Invite sources" panel groups the last 30 days of joins by the label of the invite they came in through. Unlabelled invites are bucketed as "Other".

This is how you answer "Did that TikTok campaign actually bring members?" without manually tracking individual invites.

---

## Live sync

Warden snapshots the guild's invite list on startup and on every member join, diffs to detect which invite gained a use, and attributes the new member to that invite. This is the standard Discord-bot pattern for invite tracking. JDA does not provide an explicit "this member used invite X" event; the snapshot diff is the workaround.

The snapshot is also refreshed on a 10-minute background job to catch invites created or revoked while Warden was offline.

---

## Historical invites

Expired or exhausted invites are kept in the list (with the appropriate status) for historical attribution. If you want to clean the list, delete rows from the database directly; the dashboard does not surface a delete button to avoid losing analytics attribution.
