# Discord Application Setup

Warden needs a Discord application with both a bot user and OAuth2 credentials. This page walks through the developer portal once.

Start at [discord.com/developers/applications](https://discord.com/developers/applications) and click **New Application**. Name it whatever you like (the name appears in the Discord OAuth consent dialog and as the bot's display name).

---

## Three values to copy

| Value | Where in the portal |
|---|---|
| **Bot token** | App -> **Bot** -> **Reset Token**. Visible once only, copy now. |
| **Client id** | App -> **General Information** -> **Application ID** |
| **Client secret** | App -> **OAuth2** -> **Reset Secret** |

Paste these into `plugins/Warden/config.yml`:

```yaml
discord:
  bot_token: "<paste here>"
  client_id: "<paste here>"
  client_secret: "<paste here>"
```

The bot token may also be supplied via the `WARDEN_DISCORD_BOT_TOKEN` env var, and the client secret via `WARDEN_DISCORD_CLIENT_SECRET`. Env vars take precedence over the file.

---

## Privileged intents

On the **Bot** tab, scroll to **Privileged Gateway Intents** and enable:

- **Server Members Intent**: required for member join events
- **Message Content Intent**: required if you want the message-volume analytics to count characters

The voice + ban intents the analytics layer captures are not privileged and are requested automatically.

---

## OAuth2 redirect

On the **OAuth2** tab, under **Redirects**, click **Add Redirect** and paste:

```
https://<your-public-url>/auth/discord/callback
```

For local development, the form is `http://localhost:8788/auth/discord/callback`. Whichever URL you use here must match `web.public_url` in `config.yml` byte-for-byte; otherwise Discord rejects the callback.

---

## Inviting the bot

On the **OAuth2** -> **URL Generator** tab, pick:

- Scopes: `bot`, `applications.commands`
- Bot permissions (minimum):
  - Manage Roles
  - Send Messages
  - Send Messages in Threads
  - Embed Links
  - Use External Emojis
  - View Channels

Copy the generated URL, open it in a browser, and add the bot to your guild. You will need server-owner or Manage Server permission to authorise it.

---

## Guild id

Turn on **Developer Mode** in Discord client settings (Advanced section), then right-click your server icon and click **Copy Server ID**. Paste into `discord.guild_id`.

---

## Done

You should now have four values written to `config.yml`: `bot_token`, `client_id`, `client_secret`, `guild_id`. Restart the server and continue with the [First-Run Wizard](First-Run-Wizard.md).
