# Dashboard: Permissions

`/dash/permissions` maps individual dashboard pages to Discord roles. Visible to **Config admin and Owner**.

---

## Why this exists

The default access model has four tiers (Owner / Config admin / Web manager / Mod, see [Access Roles](Access-Roles.md)). That covers 95% of needs. The Permissions page covers the rest:

- "I want a Trainee Mod role that can see `/dash/feedback` but not `/dash/audit`"
- "I want a Marketing role that can see only `/dash/stats/overview`, no other stats sub-pages"
- "I want the public to see `/leaderboard` but not the rest of the stats family"

Each page can be assigned one or more Discord roles. A member with any of those roles can access the page, regardless of their position in the four-tier model.

---

## Pages with permission overrides

Every `/dash/*` page is in the table, plus the public-eligible `/leaderboard` route. Per-page, you can:

- Add one or more roles (these gain access)
- Mark the page as public (no session required)

If the page row is empty (no roles, not public), the default tier check applies. Most pages default to Mod-tier; the Config tab defaults to Config-admin-tier; the public-eligible pages default to gated.

---

## Tier bypass

Owner and Config admin tiers always have access regardless of the Permissions configuration. This prevents accidental self-lockout: if you make a mistake on this page, you can always undo it as Owner.

Web managers do not bypass; they only get the Landing tab unless explicitly granted other pages here.

---

## Use cases

- **Public stats**: mark `/dash/stats/activity` and `/leaderboard` as public to expose them to anyone visiting the URL. Sensitive overview data on `/dash/stats` stays mod-gated.
- **Helper role**: a `Helper` role that can see `/dash/feedback` and `/dash/tickets` but not the audit log
- **Marketing**: a `Marketing` role that gets the Landing tab via Web manager, plus `/dash/stats` for content-planning context

---

## Auditing

Permission edits write to `audit_log`. View who granted which page to which role on [Dashboard Audit](Dashboard-Audit.md) by filtering action `permissions_change`.
