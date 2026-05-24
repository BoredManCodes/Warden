# Onboarding Flow

The onboarding flow is the core feature: a new member joins your guild, sees your rules, answers your questions, and either gets promoted to the full member role or is queued for human review.

This page walks through the full life cycle, the decision points, and the edge cases.

---

## Trigger

A member joins the configured Discord guild. The `GuildJoinListener` fires inside JDA and hands off to Warden's onboarding service.

The flow only runs if at least one **Delivery** method (DM or channel post) and at least one **Entry** method (Discord button, web code, web OAuth) is enabled. With everything off, the join is just recorded in `audit_log` and ignored.

---

## Step 1: Gated role applied

If gating is on (`/dash/config` -> **Gating** tab), Warden assigns `gated_role_id` to the new member. This is your "limited access" role: ideally something with read-only access to a small set of public channels (a rules channel, a help channel) and nothing else.

If gating is off, the member keeps default access and the flow is purely informational.

---

## Step 2: Delivery

Per your delivery config:

- **DM**: the bot DMs the member with the welcome message + entry buttons
- **Public channel post**: the bot posts in `welcome_channel_id` with the welcome message + entry buttons

You can have both on; some communities prefer the public option (visible, no DM-disabled fallback) plus a DM (private, more reliable for non-server-list members).

Welcome message templates support `{{...}}` placeholders (member mention, server name, etc.). See the in-app **Template fields reference** panel on the Delivery tab for the full list.

---

## Step 3: Entry

The welcome message offers whichever entry methods you have enabled:

- **Start in Discord**: a button that opens a Discord modal. Question answers are captured in DM via Discord's modal UI.
- **Open on web**: a button or link that opens `/onboard` with an 8-character code pre-filled. Useful for members on mobile who prefer a web form.
- **Sign in with Discord**: takes the member straight through Discord OAuth on the web; no code paste required.

A member can mix and match: start the flow in Discord, switch to the web mid-flow, finish in Discord. Pairing is via guest cookie + signed code, validated server-side.

---

## Step 4: Rules

Whichever entry method they chose, the member first sees the rules you have set on `/dash/config` -> **Rules** tab. Rules are written in Markdown; supported subset includes headings, lists, bold, italic, code, links. See [Rules Page](Rules-Page.md).

The member must click **I agree** before the flow proceeds. Disagreeing terminates the flow without entering a "denied" state; they can try again later.

---

## Step 5: Questions

Once rules are accepted, Warden walks the member through your configured questions in order. Question types:

- **Short text**: single line, max 200 chars
- **Long text**: multi-line, max 2000 chars
- **Single choice**: pick exactly one from a list
- **Multi choice**: pick one or more from a list

Each question has an Active flag (skips it if off) and a Required flag (must answer to continue). The order is set by drag-and-drop on the **Questions** tab. See [Dashboard Config](Dashboard-Config.md) for the editor.

---

## Step 6: Triage

On submit, the configured **triage mode** decides the outcome:

| Mode | What happens |
|---|---|
| **Auto-approve** | Immediate approval. No LLM call. Useful for trust-based communities. |
| **Mods only** | Queue to `/dash/pending`. No LLM call. Every application waits for a human. |
| **LLM auto** | The LLM is asked for an approve / deny / escalate verdict with a confidence number. If confidence is above the threshold, the verdict is applied. Otherwise the application is escalated to mods. |
| **LLM only** | The LLM verdict is taken verbatim. An "escalate" verdict is coerced to deny. Useful only if you fully trust your prompt + model and want zero mod intervention. |

The thresholds for LLM auto are configured on `/dash/config` -> **Triage** tab. Default is 0.75 for approve and 0.85 for deny: more conservative on deny, since false positives are costly.

LLM failures (rate limit, network, malformed JSON) **always escalate**, never silently lose the application. Retries cover transient 429 / 5xx / IO with exponential backoff and full jitter (3 retries, 500 ms base, 8 s cap). 4xx config errors fail fast.

---

## Step 7: Approve

On approve:

- Bot swaps `gated_role_id` for `full_role_id`
- Bot optionally DMs a welcome message (configurable on the **Approve** tab)
- Bot optionally posts a public welcome in the welcome channel
- Bot optionally grants extra roles (the "approval grants" list)

## Step 7 alt: Deny

On deny:

- Bot optionally DMs a polite rejection (configurable on the **Deny** tab)
- Per `deny_action`, the bot either:
  - **leave**: keep the gated role applied; the member stays in a frozen state
  - **strip_gated**: remove the gated role but keep them in the guild
  - **kick**: kick the member from the guild
  - **ban**: ban the member from the guild

If the member is denied via "leave" or "strip_gated", you can later re-onboard them with `/warden reonboard <discordId>` from in-game (see [In-Game Commands](In-Game-Commands.md)).

---

## Audit trail

Every step writes to `audit_log` with timestamp, actor (bot, LLM, mod, web), and a JSON payload describing the change. See [Dashboard Audit](Dashboard-Audit.md) for the viewer.

You can replay any decision from the audit log later: who approved, when, with what note, and what the LLM said if it was involved.
