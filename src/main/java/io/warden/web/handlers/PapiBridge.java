package io.warden.web.handlers;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * Reflection-only bridge to PlaceholderAPI so Warden compiles and runs whether
 * PAPI is installed or not. The first lookup caches the {@code setPlaceholders}
 * method (or its absence) so subsequent calls are cheap.
 *
 * Returns null when PAPI is absent, the lookup fails, or the placeholder is blank.
 */
public final class PapiBridge {

    private static volatile boolean checked = false;
    private static volatile Method setPlaceholders;

    private PapiBridge() {}

    public static boolean available() {
        ensureInit();
        return setPlaceholders != null;
    }

    /**
     * Resolve a PlaceholderAPI placeholder string. {@code player} can be null to
     * resolve server-wide placeholders. Returns null when PAPI isn't installed
     * or the call throws.
     */
    public static String resolve(String placeholder, OfflinePlayer player) {
        if (placeholder == null || placeholder.isBlank()) return null;
        ensureInit();
        Method m = setPlaceholders;
        if (m == null) return null;
        try {
            Object result = m.invoke(null, player, placeholder);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureInit() {
        if (checked) return;
        synchronized (PapiBridge.class) {
            if (checked) return;
            try {
                if (Bukkit.getServer() != null
                        && Bukkit.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    Class<?> cls = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                    setPlaceholders = cls.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                }
            } catch (Throwable t) {
                setPlaceholders = null;
            }
            checked = true;
        }
    }
}
