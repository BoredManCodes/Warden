package io.warden.analytics;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Opens an mc_sessions row on join and closes it on quit. The raw IP is used
 * inline for two things only: a salted SHA-256 hash for unique-IP counting, and
 * a country lookup via the optional GeoIP service. The raw value is then
 * discarded; only the hash and the ISO country code reach the database.
 */
public final class PlayerSessionAnalyticsListener implements Listener {

    private final AnalyticsService analytics;

    public PlayerSessionAnalyticsListener(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String ipHash = null;
        String country = null;
        try {
            if (p.getAddress() != null && p.getAddress().getAddress() != null) {
                String rawIp = p.getAddress().getAddress().getHostAddress();
                ipHash = analytics.hashIp(rawIp);
                country = analytics.lookupCountry(rawIp);
            }
        } catch (Exception ignored) {
            // best-effort; never fail a join because analytics couldn't read an address
        }
        String brand = null;
        try {
            brand = p.getClientBrandName();
        } catch (NoSuchMethodError | Exception ignored) {
            // older Paper API; leave null
        }
        analytics.recordMcLogin(
                p.getUniqueId().toString(),
                p.getName(),
                System.currentTimeMillis(),
                ipHash,
                country,
                brand
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        analytics.recordMcLogout(
                event.getPlayer().getUniqueId().toString(),
                System.currentTimeMillis()
        );
    }
}
