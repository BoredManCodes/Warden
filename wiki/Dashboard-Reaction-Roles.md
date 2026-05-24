# Dashboard: Reaction Roles

`/dash/reaction-roles` is the self-assign role panel system: post a Discord message with buttons; members click to toggle roles. Visible to **Config admin**.

---

## Concept

A reaction-role panel is a Discord message with one or more button options, each tied to a role. Members click the button to gain the role (or click again to remove it, depending on the mode).

Despite the legacy name "reaction roles", Warden uses Discord buttons, not emoji reactions. They render natively in Discord, have proper labels and descriptions, and survive Discord's API churn better than reaction listeners.

---

## Panel modes

| Mode | Behaviour |
|---|---|
| **Normal** | Click to add, click to remove. Members can have any combination. |
| **Unique** | Members can have at most one role from this panel. Clicking a different button removes the previous role and adds the new one. |
| **Verify** | One-time grant. Once granted, the role cannot be removed via the panel. Useful for "I have read the rules" confirmations. |
| **Reversed** | Click to remove (the role is granted by some other means; the panel lets members opt out). |
| **Limit** | Like Normal, but with a maximum number of selections per member. |
| **Binding** | Like Verify, but the panel itself becomes unusable to the member after their first selection. |

---

## Creating a panel

The **New panel** button opens a multi-step editor:

1. Title + description + colour
2. Mode (see above)
3. Option list: each option is an emoji + role + label + optional description. Drag to reorder.
4. Required role gate (only members with this role can interact, e.g. you can require `Verified` before showing the colour-roles panel)
5. Target channel
6. For Limit mode: max selections per member

On save, Warden posts the panel message to the target channel. Editing the panel later updates the message in place.

---

## Per-panel actions

From the list view:

- **Edit**: opens the editor
- **Resend**: deletes the existing Discord message and posts a fresh one (useful if the channel has scrolled past or the message was accidentally deleted)
- **Delete**: removes the panel and deletes the Discord message
- **Toggle**: temporarily disables the panel without deleting (buttons stop working; an "inactive" footer is added to the message)

---

## Audit trail

Every grant / removal triggered by a panel writes to `audit_log` with the panel id, button id, and member id. Visible on [Dashboard Audit](Dashboard-Audit.md) by filtering action `reaction_role_change`. Useful for "why does this member have the `Patron` role?".
