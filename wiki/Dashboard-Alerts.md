# Dashboard: Alerts

`/dash/alerts` is the event-to-Discord-message engine: hook any Bukkit or Discord event to a custom Discord embed and / or a list of in-game commands. Visible to **Config admin**.

---

## Concept

Alerts let you build:

- "Ping `@staff` when a player exceeds 100 anticheat violations"
- "Post a celebratory embed when a player reaches level 50"
- "Run a `bc Welcome back!` broadcast when a returning player logs in after 30 days"
- "Mirror death events to a Discord channel for the storytelling crowd"

Each alert is a (event, condition, action) triple, dynamically registered at runtime.

---

## Alert structure

| Field | Purpose |
|---|---|
| **Name** | Label for the dashboard |
| **Event** | Bukkit event class (picked from a curated list or pasted by FQCN) |
| **Condition** | Optional expression filter ("only fire if `event.player.world.name == 'world_nether'`") |
| **Cooldown** | Seconds between fires for the same subject; prevents spam |
| **Discord channel** | Where the embed goes |
| **Embed** | Title, description, colour, fields, author, footer, optional thumbnail and image |
| **Commands** | Bukkit commands to run on the console alongside the Discord post |

---

## Event picker

A dropdown shows the most common events with friendly descriptions:

- `PlayerJoinEvent`, `PlayerQuitEvent`, `PlayerDeathEvent`
- `BlockBreakEvent`, `BlockPlaceEvent`
- `EntityDeathEvent`
- `AsyncPlayerChatEvent`
- ... and ~30 others

If your event is not in the dropdown, paste the fully qualified class name. Warden uses reflection to register a listener. Any subclass of `org.bukkit.event.Event` works.

---

## Placeholders

Embed fields and the command list support `{{...}}` placeholders. The available set depends on the event type:

- `{{player_name}}`, `{{player_uuid}}`, `{{player_world}}` for player events
- `{{block_type}}`, `{{block_x}}`, `{{block_y}}`, `{{block_z}}` for block events
- `{{victim_name}}`, `{{killer_name}}` for death events
- `{{papi:%placeholder%}}` to expand any PlaceholderAPI placeholder

A live placeholder reference panel shows the available set for the currently selected event.

---

## Import from DiscordSRV alerts.yml

If you already use DiscordSRV's alerts feature, the **Import** button on `/dash/alerts/import` accepts a paste of your `alerts.yml` and converts each entry into a Warden alert. The conversion is best-effort; review and tweak before enabling.

---

## Testing

Each alert has a **Test fire** button that runs the action against a dummy event payload. The Discord post appears in the target channel and any commands are executed on the console (with caution: a dangerous command in the list will run).

---

## Reload

The Alerts page subscribes to events dynamically. When you save, the affected event class is re-subscribed; no server restart needed. The dashboard shows a green check confirming the new registration.
