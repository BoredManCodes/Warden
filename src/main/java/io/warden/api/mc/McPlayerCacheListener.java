package io.warden.api.mc;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Persists a snapshot of the player to disk on quit, so the
 * GET /api/v1/players/{username} handler can serve their last-known state
 * after they log off.
 */
public final class McPlayerCacheListener implements Listener {

    private final McPlayerCache cache;

    public McPlayerCacheListener(McPlayerCache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            cache.saveOnQuit(event.getPlayer());
        } catch (Throwable t) {
            // Listener must never break the quit pipeline.
        }
    }
}
