package io.warden.alerts;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-fire bag of state passed to {@link AlertService#fire}. Carries
 * <ul>
 *   <li>the {@code {var}} → value substitutions used by simple templates;</li>
 *   <li>the raw Bukkit {@link Event} (when one applies) and an optional
 *       {@code user} object (for Discord events), made available to SpEL
 *       templates as {@code event.*} / {@code server.*} / {@code user.*};</li>
 *   <li>the related {@link OfflinePlayer} so PlaceholderAPI and as-player
 *       commands have a stable reference.</li>
 * </ul>
 */
public final class AlertContext {

    private final Map<String, String> vars = new HashMap<>();
    private OfflinePlayer player;
    private UUID playerUuid;
    private Event event;
    private Object user;

    public AlertContext set(String key, String value) {
        if (key == null) return this;
        vars.put(key, value == null ? "" : value);
        return this;
    }

    public AlertContext player(OfflinePlayer p) {
        this.player = p;
        if (p != null) this.playerUuid = p.getUniqueId();
        return this;
    }

    public AlertContext playerUuid(UUID uuid) {
        this.playerUuid = uuid;
        return this;
    }

    public AlertContext event(Event ev) {
        this.event = ev;
        return this;
    }

    public AlertContext user(Object u) {
        this.user = u;
        return this;
    }

    public Map<String, String> vars() { return vars; }
    public OfflinePlayer player() { return player; }
    public UUID playerUuid() { return playerUuid; }
    public Event event() { return event; }
    public Object user() { return user; }

    /** SpEL root object: exposes {@code event}, {@code server}, {@code user}, {@code player}. */
    public AlertRoot toSpelRoot() {
        return new AlertRoot(event, Bukkit.getServer(), user, player);
    }

    public record AlertRoot(Event event, Object server, Object user, Object player) {}
}
