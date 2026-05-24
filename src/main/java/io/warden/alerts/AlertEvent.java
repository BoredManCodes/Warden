package io.warden.alerts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of built-in alert triggers. Each entry has a stable key (stored on
 * the alert row when no custom {@code trigger_class} is set), a label for the
 * picker, a one-line description, the variables that
 * {@link AlertVarsPopulator} populates when the matching Bukkit event fires,
 * and the Bukkit event class FQCN that {@link AlertManager} should register a
 * listener for. Discord-side events and server lifecycle events have a null
 * {@code bukkitClass}; those are dispatched directly from
 * {@link AlertDiscordListener} / WardenPlugin.
 */
public enum AlertEvent {

    PLAYER_JOIN("player_join", "Player joins server",
            "A player joins the Minecraft server.",
            List.of("player", "player_uuid", "player_display", "world"),
            "org.bukkit.event.player.PlayerJoinEvent"),
    PLAYER_FIRST_JOIN("player_first_join", "Player joins for the first time",
            "A player joins for their first time ever (hasPlayedBefore() is false).",
            List.of("player", "player_uuid", "player_display", "world"),
            "org.bukkit.event.player.PlayerJoinEvent"),
    PLAYER_QUIT("player_quit", "Player leaves server",
            "A player disconnects from the Minecraft server.",
            List.of("player", "player_uuid", "player_display", "world"),
            "org.bukkit.event.player.PlayerQuitEvent"),
    PLAYER_DEATH("player_death", "Player dies",
            "A player dies. {death_message} carries the default vanilla message.",
            List.of("player", "player_uuid", "player_display", "world", "death_message", "killer"),
            "org.bukkit.event.entity.PlayerDeathEvent"),
    PLAYER_ADVANCEMENT("player_advancement", "Player completes an advancement",
            "A player completes an advancement. Recipe / hidden advancements are filtered out.",
            List.of("player", "player_uuid", "player_display", "world", "advancement"),
            "org.bukkit.event.player.PlayerAdvancementDoneEvent"),
    PLAYER_CHAT("player_chat", "Player sends chat message",
            "A player sends a chat message in Minecraft.",
            List.of("player", "player_uuid", "player_display", "world", "message"),
            "io.papermc.paper.event.player.AsyncChatEvent"),
    PLAYER_WORLD_CHANGE("player_world_change", "Player changes world",
            "A player teleports or moves between worlds.",
            List.of("player", "player_uuid", "player_display", "world", "from_world"),
            "org.bukkit.event.player.PlayerChangedWorldEvent"),
    SERVER_START("server_start", "Server starts",
            "Warden has finished initialising (server is ready for players).",
            List.of(),
            null),
    SERVER_STOP("server_stop", "Server stops",
            "Warden is shutting down (the server is going down).",
            List.of(),
            null),

    DISCORD_MEMBER_JOIN("discord_member_join", "Discord member joins",
            "A user joins the Discord guild.",
            List.of("user", "user_id", "user_mention", "user_tag"),
            null),
    DISCORD_MEMBER_LEAVE("discord_member_leave", "Discord member leaves",
            "A user leaves or is removed from the Discord guild.",
            List.of("user", "user_id", "user_tag"),
            null);

    private final String key;
    private final String label;
    private final String description;
    private final List<String> variables;
    private final String bukkitClass;

    AlertEvent(String key, String label, String description,
               List<String> variables, String bukkitClass) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.variables = List.copyOf(variables);
        this.bukkitClass = bukkitClass;
    }

    public String key() { return key; }
    public String label() { return label; }
    public String description() { return description; }
    public List<String> variables() { return variables; }
    public String bukkitClass() { return bukkitClass; }

    /** Does this event carry a Minecraft player context (used for PAPI resolution + as-player commands)? */
    public boolean hasPlayer() {
        return this != SERVER_START && this != SERVER_STOP
                && this != DISCORD_MEMBER_JOIN && this != DISCORD_MEMBER_LEAVE;
    }

    public static AlertEvent fromKey(String key) {
        if (key == null) return null;
        for (AlertEvent e : values()) if (e.key.equals(key)) return e;
        return null;
    }

    /** Ordered map used by the editor when rendering the event picker. */
    public static Map<String, AlertEvent> picker() {
        Map<String, AlertEvent> m = new LinkedHashMap<>();
        for (AlertEvent e : values()) m.put(e.key, e);
        return m;
    }
}
