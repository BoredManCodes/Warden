package io.warden.alerts;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Turns a Bukkit event class name (fully-qualified, or a short name that lives
 * in one of the standard Bukkit / Paper packages, or a class shipped by another
 * loaded plugin) into the loaded {@link Class}. Used by {@link AlertManager}
 * to register dynamic listeners and by {@link DsrvAlertImporter} to validate
 * pasted Trigger strings.
 *
 * Lookups are cached forever - event class identity doesn't change across the
 * lifetime of a server JVM, and we want repeated reload() passes to be cheap.
 */
public final class EventClassResolver {

    private static final ConcurrentMap<String, Class<? extends Event>> CACHE = new ConcurrentHashMap<>();

    /** Packages we'll search when given a short class name with no dots. */
    private static final String[] SHORT_NAME_PACKAGES = {
            "org.bukkit.event.player.",
            "org.bukkit.event.entity.",
            "org.bukkit.event.block.",
            "org.bukkit.event.inventory.",
            "org.bukkit.event.server.",
            "org.bukkit.event.world.",
            "org.bukkit.event.weather.",
            "org.bukkit.event.vehicle.",
            "org.bukkit.event.hanging.",
            "org.bukkit.event.enchantment.",
            "org.bukkit.event.raid.",
            "io.papermc.paper.event.player.",
            "io.papermc.paper.event.server.",
            "io.papermc.paper.event.entity.",
            "io.papermc.paper.event.block.",
    };

    private EventClassResolver() {}

    public static Class<? extends Event> resolve(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        Class<? extends Event> cached = CACHE.get(trimmed);
        if (cached != null) return cached;

        Class<? extends Event> found = lookup(trimmed);
        if (found != null) CACHE.put(trimmed, found);
        return found;
    }

    private static Class<? extends Event> lookup(String name) {
        ClassLoader local = EventClassResolver.class.getClassLoader();
        // Direct FQCN attempt - the common case.
        Class<? extends Event> direct = tryLoad(name, local);
        if (direct != null) return direct;

        // Short name? Walk known packages.
        if (!name.contains(".")) {
            for (String pkg : SHORT_NAME_PACKAGES) {
                Class<? extends Event> hit = tryLoad(pkg + name, local);
                if (hit != null) return hit;
            }
        }

        // Last resort: walk every loaded plugin's classloader. Useful for events
        // shipped by other plugins (e.g. ProtocolLib, AdvancedAchievements).
        try {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                ClassLoader pl = p.getClass().getClassLoader();
                if (pl == null || pl == local) continue;
                Class<? extends Event> hit = tryLoad(name, pl);
                if (hit != null) return hit;
                if (!name.contains(".")) {
                    for (String pkg : SHORT_NAME_PACKAGES) {
                        Class<? extends Event> hitPkg = tryLoad(pkg + name, pl);
                        if (hitPkg != null) return hitPkg;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Bukkit may not be initialised yet on very-early calls; not fatal.
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> tryLoad(String fqcn, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(fqcn, false, cl);
            if (Event.class.isAssignableFrom(c)) return (Class<? extends Event>) c;
        } catch (Throwable ignored) {
            // not found in this classloader; caller will keep searching
        }
        return null;
    }
}
