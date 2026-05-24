# First-Run Wizard

With `discord.bot_token`, `discord.client_id`, `discord.client_secret`, `discord.guild_id`, and `web.session_secret` filled in, restart the server and visit your dashboard URL. Warden walks you through an eight-step checklist on first arrival.

---

## The eight steps

1. **Java + Paper detected**. Auto-checked by the server itself; if the page loads, you have passed.
2. **Bot token + guild id valid**. Warden has connected to the Discord gateway and resolved the guild. If this is red, the most common cause is a typo in the token or guild id; check the console for the JDA exception.
3. **Web server reachable on public_url**. Warden self-tests by hitting its own `public_url`. If you put a hostname here that does not resolve back, this step fails; either fix DNS or set `public_url` to your LAN IP for now.
4. **Session secret present**. Generated on first start. See [Session Secret](Session-Secret.md).
5. **Discord OAuth round-trip works**. The first sign-in attempt completes successfully. Failure here usually means the redirect URL in the Discord Developer Portal does not match `public_url`.
6. **Mod role chosen**. Walks you to `/setup/mod-role` (see below).
7. **Delivery + entry configured**. At least one delivery channel (DM or public post) and at least one entry method (Discord button, web code, web OAuth) are enabled in `/dash/config`.
8. **HTTPS (optional)**. Soft check, not a blocker. Visit `/dash/https` for a walkthrough.

---

## Step-by-step

### Sign in with Discord

Click **Sign in with Discord** on the welcome page. The server owner and anyone with the `ADMINISTRATOR` permission in the guild is treated as the highest-rank access tier (Owner) on first sign-in, so you can always get in before any role is configured.

### Pick the mod role

You will land on `/setup/mod-role`. Pick a Discord role that should be treated as the "mod" role for the dashboard from now on. This role can:

- See `/dash/pending` (applications waiting for review)
- See `/dash/audit` (decision timeline)
- See `/dash/members`, `/dash/tickets`, `/dash/feedback`, and the other mod-facing pages
- Approve / deny applications via dashboard or via Discord embeds

You can change this later from `/dash/config` -> **Roles** tab.

### Configure roles

You will be redirected to `/dash/config`. The **Roles** tab lets you set:

- `gated_role_id`: applied to new joiners (the "limited access" role)
- `full_role_id`: applied on approval
- `mod_role_id`: dashboard + Discord button access for mods (what you just picked)
- `config_admin_role_id`: full access to `/dash/config` (Owner-only setting)
- `web_manager_role_id`: Landing tab access only (Owner-only setting)
- Welcome and mod-review channel ids

### Pick at least one delivery and one entry method

On the **Delivery** tab: turn on **DM**, **public channel post**, or both. Edit the message templates if you want.

On the **Entry** tab: turn on at least one of **Discord button**, **web code**, **web OAuth**.

Save. Warden is now live for new joiners.

### (Optional) HTTPS

Visit `/dash/https` for the walkthrough. Free Let's Encrypt cert; no reverse proxy required. See [HTTPS](HTTPS.md) for the short version.

---

## What "silent" means before delivery is enabled

Until you turn on at least one delivery method, Warden:

- Records every join to `audit_log`
- Does not assign the gated role
- Does not DM anyone
- Does not post to any channel
- Does not start any onboarding flow

This default exists so installing Warden into an active community does not spam every existing member with a DM. Configure delivery, save, and the next joiner gets the full flow.
