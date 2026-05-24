# In-Game Commands

Warden exposes one in-game command, `/warden`, with three subcommands. Permission node: `warden.admin` (default: op).

```
/warden status
/warden reload
/warden reonboard <discordId | playerName>
```

---

## /warden status

Reports the runtime state of the plugin:

- Is JDA connected to the Discord gateway?
- Is the Javalin web server bound and listening?
- How many queued analytics events are pending?

If anything is unhealthy, the relevant component prints in red with a one-line hint.

---

## /warden reload

Re-reads `config.yml` and applies anything that can be applied live. For boot-bound keys, the command reports them with a message like:

```
Warden: config.yml reloaded.
The following keys changed but require a server restart to take effect:
  - web.bind_port
  - discord.bot_token
```

See [Configuration Reference](Configuration-Reference.md) for the boot-bound versus live list.

Most settings you would normally want to change after install (roles, delivery, questions, landing copy, AI gateway) are stored in the database and managed from the dashboard. Those are always live; no reload required.

---

## /warden reonboard

Resets a user's onboarding state and replays delivery. Useful for:

- A member who DM-blocked the bot the first time and now wants to onboard
- A denial that should be re-tried
- A development testing loop where you want to repeatedly trigger the flow

Two argument forms:

```
/warden reonboard 123456789012345678          # raw 15-21 digit Discord snowflake
/warden reonboard JimboTheTester              # Minecraft player name, requires DiscordSRV
```

The player-name form looks up the linked Discord account via DiscordSRV. If DiscordSRV is not installed or the player has not linked, the command errors with a helpful message.

What happens:

1. The user's prior answers are cleared
2. Their state is reset to `pending_link`
3. If they are still in the guild, the onboarding delivery is replayed (DM or channel post per your config)
4. If they are no longer in the guild, the state is reset but no delivery is attempted; they will get the flow if they rejoin

The reset is recorded in `audit_log` with the in-game player who triggered it as the actor.
