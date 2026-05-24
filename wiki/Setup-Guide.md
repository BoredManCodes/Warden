# Setup Guide

This is the short path from a blank `plugins/` directory to a working Warden install. Allow about five minutes once you have a Discord application ready.

> If you have not created the Discord application yet, do that first. See [Discord Application Setup](Discord-Application-Setup.md).

---

## 1. Drop the JAR in place

Put `warden-<version>.jar` into your Paper server's `plugins/` directory. No other files are required.

## 2. Start the server once

On first start, Warden:

- Creates `plugins/Warden/config.yml` with default keys
- Creates the SQLite database at `plugins/Warden/data/warden.db`
- Generates a random 32-byte hex `web.session_secret` and writes it back to `config.yml`
- Unpacks the default landing templates to `plugins/Warden/www/`
- Logs the dashboard URL to the console

The plugin starts in a **silent state**: members who join the guild are recorded in the audit log but are not DMed, not gated, and not posted about. This avoids surprises on installations into existing communities. You opt in to delivery from the dashboard once configuration is set.

## 3. Stop the server and edit config.yml

Open `plugins/Warden/config.yml` and fill in the four required values:

```yaml
discord:
  bot_token: "..."           # from Bot tab in the Developer Portal
  client_id: "..."           # application id, General Information tab
  client_secret: "..."       # from OAuth2 tab
  guild_id: "..."            # right-click your server icon (Developer Mode on) -> Copy Server ID
```

The other keys can stay at their defaults for now. See [Configuration Reference](Configuration-Reference.md) for the full list.

## 4. Start the server again

Once the four Discord values are present, Warden connects to the Discord gateway and the web server comes up on `web.bind_host:web.bind_port` (default `0.0.0.0:8788`).

## 5. Open the dashboard

Visit `http://<your-public-url>/` in a browser. You will see the first-run welcome page with the eight-step setup checklist. Sign in with Discord, then walk through the checklist. See [First-Run Wizard](First-Run-Wizard.md) for the page-by-page tour.

## 6. Switch on at least one delivery method

In `/dash/config`:

- Turn on **DM** or **public channel post** (or both) on the **Delivery** tab
- Turn on **Discord button**, **web code**, or **web OAuth** (or any combination) on the **Entry** tab
- Save

Until at least one Delivery + one Entry method is enabled, Warden stays silent.

## 7. Optional: switch to HTTPS

Visit `/dash/https` for a step-by-step walkthrough using free Let's Encrypt certificates. Warden terminates TLS natively; no reverse proxy is required. See [HTTPS](HTTPS.md) for the short version.

---

## Next steps

- Tune the [Onboarding Flow](Onboarding-Flow.md) (rules, questions, triage mode)
- Customise the [Landing Page](Landing-Page.md)
- Plug in an [AI gateway](AI-Assistance.md) if you want triage + Polish-with-AI
- Configure [Access Roles](Access-Roles.md) so co-mods can use the dashboard
