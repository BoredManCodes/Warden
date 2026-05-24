# Dashboard: Config

`/dash/config` is the tabbed settings hub. Reachable by **Owner**, **Config admin**, and (Landing tab only) **Web manager**. See [Access Roles](Access-Roles.md) for the tier breakdown.

---

## The tabs

| Tab | Who can edit | What it controls |
|---|---|---|
| **Roles** | Config admin (Owner-only for the admin/web-manager fields) | The four-tier role mapping + welcome / review channels |
| **Delivery** | Config admin | DM vs channel post, message templates |
| **Entry** | Config admin | Which entry methods are offered (Discord button, web code, web OAuth) |
| **Gating** | Config admin | Whether the gated role is actually applied to new joiners |
| **Triage** | Config admin | Auto-approve / Mods only / LLM auto / LLM only, with confidence thresholds |
| **AI** | Config admin | LLM gateway URL, model, API key, test button |
| **Approve** | Config admin | DM template, public-channel announce, extra role grants on approval |
| **Deny** | Config admin | DM template, deny action (leave / strip / kick / ban) |
| **Rules** | Config admin | The Markdown shown at the start of the onboarding flow |
| **Landing** | Config admin, Web manager | Everything for the public site: server name, branding, hero, accent, stats labels, PAPI overrides, feature cards, FAQ, promo video, Google Analytics |
| **Questions** | Config admin | Drag-and-drop reorderable question list with per-row Required / Active checkboxes |

---

## Tab: Roles

Set each role id either by typing the snowflake or by picking from a dropdown of guild roles. Channel ids the same way. The Owner-only fields (Config admin and Web manager) are visible to Config admins but read-only; only the Owner can change them.

Channels:

- **Welcome channel**: where the public welcome post is sent if you have channel-delivery on, and where the post-approval announcement goes if enabled
- **Mod review channel**: where escalated-application embeds are posted for mods to approve / deny directly from Discord

## Tab: Delivery

Two toggles (DM, channel post) plus a message template for each. Templates support `{{member_mention}}`, `{{member_name}}`, `{{server_name}}`, `{{onboard_url}}`, and others; the in-app Template fields reference panel lists the full set.

The Polish-with-AI button next to each template uses the AI gateway to rewrite the message. See [AI Assistance](AI-Assistance.md).

## Tab: Entry

Three toggles, any combination. Discord button is the simplest, web code is needed for members who block bot DMs, web OAuth is the smoothest UX. At least one must be on for the flow to work at all.

## Tab: Gating

A single toggle. When off, members keep their default access on join and the flow is purely informational. When on, the gated role is applied.

## Tab: Triage

Pick the mode and (for LLM auto) set the approve / deny confidence thresholds. Defaults are 0.75 and 0.85 respectively; the deny threshold is intentionally higher because false denials are costly. See [Onboarding Flow](Onboarding-Flow.md).

## Tab: AI

API key, base URL, model, plus a Test connection button. See [AI Assistance](AI-Assistance.md).

## Tab: Approve

DM template (with Polish-with-AI), public-channel announcement toggle, list of extra roles to grant on approval beyond `full_role_id`.

## Tab: Deny

DM template + deny action picker (leave / strip_gated / kick / ban). See [Onboarding Flow](Onboarding-Flow.md) for the action meanings.

## Tab: Rules

Plain textarea with a Markdown preview. See [Rules Page](Rules-Page.md).

## Tab: Landing

The largest tab. See [Landing Page](Landing-Page.md) for the full breakdown of every section (mode, identity, map, branding, stats, feature cards, FAQ, promo video, Google Analytics, Template fields reference).

## Tab: Questions

Drag handles on each row reorder the questions; the new order saves immediately (no Save button). Per-row Required and Active checkboxes AJAX-toggle without a save round-trip. Add Question opens a modal for the question type, prompt, and (for choice questions) the option list.

Question types: short text, long text, single choice, multi choice. Choice questions have a sub-editor for the option list with drag-and-drop reordering of options.

The Generate Questions with AI button (top of the tab) asks your LLM to draft N questions appropriate for your community. Result lands in the editor for review before saving. See [AI Assistance](AI-Assistance.md).

---

## Saving

Most tabs have a single Save button at the bottom that persists every editable field on that tab. Save success is shown as a toast at the top. Save failures (validation errors, etc.) inline-highlight the offending field.

Some controls (the Questions tab drag-and-drop, the per-row checkboxes) AJAX-save individually; no overall Save needed.

---

## Audit trail

Every config change writes to `audit_log` as a `web` actor with before / after diff in the payload. View the history on [Dashboard Audit](Dashboard-Audit.md). Useful for "what did the config look like before someone broke it".
