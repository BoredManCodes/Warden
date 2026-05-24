package io.warden.alerts;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dynamic Bukkit-event subscription for alerts. Walks every enabled alert,
 * resolves its trigger to a Bukkit event class ({@link AlertEvent#bukkitClass()}
 * for built-ins, {@link Alert#triggerClass()} for DSRV-style customs), and
 * registers one {@link EventExecutor} per unique class.
 *
 * Discord-side alerts ({@code discord_member_join}/{@code discord_member_leave})
 * and lifecycle alerts ({@code server_start}/{@code server_stop}) skip this
 * registration path - they fire directly from
 * {@link AlertDiscordListener} / WardenPlugin.
 *
 * Call {@link #reload()} after creating, editing, deleting, or toggling an
 * alert to rebuild the subscription map.
 */
public final class AlertManager {

    private final AlertService service;
    private final AlertDao dao;
    private final Plugin plugin;
    private final Logger log;

    /** One marker Listener per unique event class, so we can {@link HandlerList#unregisterAll} cleanly. */
    private final Map<Class<? extends Event>, Listener> registered = new HashMap<>();
    /** Per-class snapshot of the alerts that listener should fan-out to. Mutated only inside {@link #reload()}. */
    private final Map<Class<? extends Event>, List<Alert>> byClass = new HashMap<>();

    public AlertManager(AlertService service, AlertDao dao, Plugin plugin, Logger log) {
        this.service = service;
        this.dao = dao;
        this.plugin = plugin;
        this.log = log;
    }

    /** Rebuild subscriptions from the alerts table. Safe to call any time. */
    public synchronized void reload() {
        // Unregister everything first; HandlerList by-listener lookup is keyed
        // by Listener instance, which is why we hold one per class above.
        for (Listener l : registered.values()) {
            try { HandlerList.unregisterAll(l); }
            catch (Throwable t) { log.log(Level.WARNING, "alerts: unregister listener failed", t); }
        }
        registered.clear();
        byClass.clear();

        // Group alerts by Bukkit event class. We use a LinkedHashMap so the
        // dispatch order matches alert id order, which is what the dashboard shows.
        Map<Class<? extends Event>, List<Alert>> grouped = new LinkedHashMap<>();
        List<Alert> all;
        try {
            all = dao.listAll();
        } catch (Exception e) {
            log.log(Level.WARNING, "alerts: failed to list alerts during reload", e);
            return;
        }
        int customs = 0, builtins = 0, skipped = 0;
        for (Alert a : all) {
            if (!a.enabled()) { skipped++; continue; }
            Class<? extends Event> cls = resolveClass(a);
            if (cls == null) { skipped++; continue; }
            grouped.computeIfAbsent(cls, k -> new CopyOnWriteArrayList<>()).add(a);
            if (a.isCustomTrigger()) customs++; else builtins++;
        }

        for (var entry : grouped.entrySet()) {
            Class<? extends Event> cls = entry.getKey();
            byClass.put(cls, entry.getValue());
            Listener marker = new Listener() {};
            try {
                Bukkit.getPluginManager().registerEvent(
                        cls, marker, EventPriority.MONITOR,
                        new DispatchExecutor(cls), plugin, true);
                registered.put(cls, marker);
            } catch (Throwable t) {
                log.log(Level.WARNING, "alerts: failed to register dynamic listener for "
                        + cls.getName() + ": " + t.getMessage());
            }
        }

        log.info("alerts: reloaded - " + builtins + " built-in + " + customs
                + " custom-trigger alerts across " + registered.size()
                + " event class(es); " + skipped + " skipped (disabled or unresolvable)");
    }

    /** Drop all subscriptions. Called on plugin disable. */
    public synchronized void shutdown() {
        for (Listener l : registered.values()) {
            try { HandlerList.unregisterAll(l); }
            catch (Throwable ignored) {}
        }
        registered.clear();
        byClass.clear();
    }

    private static Class<? extends Event> resolveClass(Alert a) {
        if (a.isCustomTrigger()) return EventClassResolver.resolve(a.triggerClass());
        AlertEvent e = a.parsedEvent();
        if (e == null) return null;
        if (e.bukkitClass() == null) return null;
        return EventClassResolver.resolve(e.bukkitClass());
    }

    /**
     * Per-class executor that fans an incoming Bukkit event out to every alert
     * registered against that class. Variables are populated once via
     * {@link AlertVarsPopulator}; condition evaluation + rendering happen
     * per-alert inside {@link AlertService#fireAlert}.
     */
    private final class DispatchExecutor implements EventExecutor {
        private final Class<? extends Event> targetClass;

        DispatchExecutor(Class<? extends Event> targetClass) {
            this.targetClass = targetClass;
        }

        @Override
        public void execute(Listener marker, Event event) throws EventException {
            if (!targetClass.isInstance(event)) return;
            List<Alert> alerts = byClass.get(targetClass);
            if (alerts == null || alerts.isEmpty()) return;
            AlertContext ctx = new AlertContext();
            try { AlertVarsPopulator.populate(event, ctx); }
            catch (Throwable t) { log.log(Level.FINE, "alerts: populator failed", t); }

            // PLAYER_FIRST_JOIN is a degenerate case: same Bukkit class as
            // PLAYER_JOIN but only fires when hasPlayedBefore() is false. We
            // honour it here so built-in alerts behave like the v1 implementation.
            boolean firstJoin = false;
            if (event instanceof org.bukkit.event.player.PlayerJoinEvent pje) {
                firstJoin = !pje.getPlayer().hasPlayedBefore();
            }

            for (Alert a : alerts) {
                if (!a.isCustomTrigger()) {
                    AlertEvent ae = a.parsedEvent();
                    if (ae == AlertEvent.PLAYER_FIRST_JOIN && !firstJoin) continue;
                    if (ae == AlertEvent.PLAYER_JOIN
                            && event instanceof org.bukkit.event.player.PlayerJoinEvent) {
                        // PLAYER_JOIN fires for every join (first-time included),
                        // matching v1 semantics. No filter.
                    }
                }
                service.fireAlert(a, ctx);
            }
        }
    }
}
