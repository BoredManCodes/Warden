# Warden Wiki

Welcome to the Warden wiki. Warden is a Paper plugin that gives your Minecraft + Discord community a complete onboarding, moderation, and analytics surface from a single fat JAR. Drop the JAR into `plugins/`, fill in a Discord bot token and a session secret, and you get:

- A configurable Discord onboarding flow (DM, channel post, or web)
- A public landing page at `/` with editable HTML, CSS, hero image, accent colour, stats counters, FAQ, and feature cards
- A mod dashboard at `/dash` gated by Discord OAuth and role
- Four nested access tiers: Owner, Config admin, Web manager, Mod
- AI assisted copy polishing and content generation
- Activity analytics: Discord and Minecraft event capture, daily rollups, charts
- A live map embed at `/map` (Dynmap, Pl3xMap, BlueMap, squaremap)
- A ticket system, automod, raid protection, reaction roles, levels, scheduler, polls, giveaways, anticheat surfacing, alerts engine, and more

Everything except secrets is editable from the dashboard. No recompile, no restart for most settings.

---

## Quick links

### Getting started

- [Setup Guide](Setup-Guide.md): install in five minutes
- [Discord Application Setup](Discord-Application-Setup.md): bot token, OAuth, intents
- [First-Run Wizard](First-Run-Wizard.md): the eight-step checklist
- [Configuration Reference](Configuration-Reference.md): every key in `config.yml`
- [Session Secret](Session-Secret.md): how to generate and override it
- [HTTPS](HTTPS.md): native TLS with Let's Encrypt
- [Access Roles](Access-Roles.md): the four-tier permission model

### Features

- [Onboarding Flow](Onboarding-Flow.md): the full life cycle of a new joiner
- [Landing Page](Landing-Page.md): customising the public site
- [Rules Page](Rules-Page.md): editing the Markdown shown to applicants
- [Map Page](Map-Page.md): live map embed
- [Leaderboard Page](Leaderboard-Page.md): public stats surface
- [AI Assistance](AI-Assistance.md): LLM triage, Polish with AI, Generate features
- [Analytics](Analytics.md): event capture, rollups, charts
- [In-Game Commands](In-Game-Commands.md): `/warden status`, `reload`, `reonboard`
- [Rate Limits](Rate-Limits.md): per-IP caps on public routes
- [API Reference](API-Reference.md): bearer-token JSON API + Swagger UI at `/api/docs`

### Dashboard pages

- [Pending](Dashboard-Pending.md): applications awaiting human review
- [Audit](Dashboard-Audit.md): full timeline of every decision
- [Members](Dashboard-Members.md): member directory + per-user detail
- [Config](Dashboard-Config.md): tabbed settings hub
- [Stats](Dashboard-Stats.md): activity, retention, geography
- [Tickets](Dashboard-Tickets.md): support ticket workflow
- [Alerts](Dashboard-Alerts.md): event-to-Discord-message engine
- [Moderation](Dashboard-Moderation.md): automod, raid protection, warnings
- [Permissions](Dashboard-Permissions.md): page access mapping
- [API Keys](Dashboard-API-Keys.md): mint bearer tokens for `/api/v1`
- [Levels](Dashboard-Levels.md): XP, rank cards, role rewards
- [Reaction Roles](Dashboard-Reaction-Roles.md): self-assign role panels
- [Engagement](Dashboard-Engagement.md): polls, giveaways, reminders
- [Scheduler](Dashboard-Scheduler.md): timezone-aware event scheduling
- [Feedback](Dashboard-Feedback.md): feature request board
- [Invites](Dashboard-Invites.md): invite tracking + labels
- [Grim Anticheat](Dashboard-Grim.md): violation viewer
- [About](Dashboard-About.md): credits and links
- [HTTPS Panel](Dashboard-HTTPS.md): in-app TLS walkthrough

### Operations

- [Building from Source](Building-From-Source.md)
- [Troubleshooting](Troubleshooting.md)

---

## Architecture at a glance

```
  Discord                          Paper server JVM
  New member joins      ----->     Warden plugin
  Bot DMs / posts       <-----      |- JDA (Discord bot + voice/msg events)
  Mods approve/deny                 |- Javalin (dashboard + landing :8788)
  AI assists copy       <-----      |- SQLite WAL (config + state + events)
                                    |- OpenAI-compatible LLM (optional)
                                    `- PlaceholderAPI bridge (optional)
  Web visitor
  /          landing    <-----
  /rules     rules      <-----
  /map       embed      <-----
  /onboard   flow       <-----
  /dash/*    mod UI     <-----
```

---

## Requirements

- Paper 1.21.x (or a compatible fork: Spigot covers the core APIs Warden uses)
- Java 21
- A Discord application + bot
- A reachable web port for the dashboard (default `8788`)

Optional but supported:

- An OpenAI-Responses-compatible LLM gateway (Manifest, OpenAI, OpenRouter, local Ollama, anything that speaks `POST /v1/responses` with Bearer auth) for triage and AI polish
- DiscordSRV for resolving Minecraft player names to Discord accounts (used by `/warden reonboard <playerName>`)
- PlaceholderAPI for overriding the live-stat counters on the landing page
- GrimAC for the violations dashboard page

---

## License

Warden is licensed under the Apache License, Version 2.0. See `LICENSE` for the full text and `NOTICE` for the project notice. Third-party libraries bundled into the fat JAR are listed with their licenses in `THIRD_PARTY_LICENSES.md`.
