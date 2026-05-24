# Access Roles

Warden has four nested access tiers. A higher tier always sees everything a lower tier sees.

| Tier | What they see | Set by |
|---|---|---|
| **Owner** | Everything. Implicit on the Discord server owner and anyone with the `ADMINISTRATOR` permission in the guild. | Discord itself |
| **Config admin** | All of `/dash/config` (Roles, Delivery, Entry, Triage, AI, Approve, Deny, Rules, Landing, Questions) plus every mod-facing page. | Owner picks `config_admin_role_id` |
| **Web manager** | The **Landing** tab of `/dash/config` only. Designed for delegating cosmetic / marketing edits without granting onboarding-flow control. | Owner picks `web_manager_role_id` |
| **Mod** | `/dash/pending`, `/dash/audit`, `/dash/members`, `/dash/tickets`, `/dash/feedback`, `/dash/scheduler`, and the other mod-facing pages. Can approve / deny applications. No config access. | Owner or Config admin picks `mod_role_id` |

---

## Why the tiers are nested

A Config admin is also a Mod. A Web manager is **not** a Mod by default; the Web manager role is a sidecar for letting someone edit the landing page without seeing the moderation surfaces. If you want a single person to do both, give them both roles, or just promote them to Mod.

The Owner tier is special: it always exists implicitly via Discord-level permissions and cannot be revoked from the dashboard. This guarantees that you can never permanently lock yourself out of `/dash/config` no matter how the role ids are set.

---

## Where each role is configured

All four are set on `/dash/config` -> **Roles** tab. The Owner-only fields (`config_admin_role_id`, `web_manager_role_id`) are visible to Config admins but read-only; only the Owner can change them. This prevents a compromised Config admin role from escalating itself further.

---

## Per-page permission overrides

For finer-grained access control, see [Dashboard Permissions](Dashboard-Permissions.md). The Permissions page lets you map specific dashboard pages to specific Discord roles, overriding the default mod-tier check. Useful for, say, letting a "Trainee Mod" role into `/dash/feedback` but not `/dash/audit`.

The Owner and Config admin tiers always bypass page-permission overrides.

---

## What mods see when they click a locked entry

Clicking the **Config** sidebar entry as a Mod opens a modal explaining the required role and pointing at the Owner to grant access. Same applies for any page where the visitor's role tier is below the page's gate.

---

## Session lifecycle

Sessions are HMAC-signed cookies tied to a Discord user id and a snapshot of their role state at sign-in time. If your role changes (you are promoted or demoted), your existing cookie still reflects the old state. Log out and back in to mint a fresh cookie. See [Troubleshooting](Troubleshooting.md) for the common "you are signed in but not a mod" case.
