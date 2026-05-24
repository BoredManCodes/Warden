package io.warden.analytics;

import io.warden.data.dao.AnalyticsMetaDao;
import io.warden.data.dao.DiscordMemberEventDao;
import io.warden.data.dao.DiscordMessageDao;
import io.warden.data.dao.DiscordVoiceSessionDao;
import io.warden.data.dao.McSessionDao;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Front door for analytics event capture. All public methods are non-blocking:
 * they queue the write onto the shared background executor so JDA's gateway
 * thread and Bukkit's main thread never block on SQLite.
 *
 * Failures are logged at FINE and swallowed - analytics must never break a flow.
 */
public final class AnalyticsService {

    /** A session left open longer than this is treated as a crash and force-closed. */
    private static final long MAX_SESSION_MS = TimeUnit.HOURS.toMillis(24);

    private final DiscordMessageDao messages;
    private final DiscordMemberEventDao memberEvents;
    private final DiscordVoiceSessionDao voiceSessions;
    private final McSessionDao mcSessions;
    private final AnalyticsMetaDao meta;
    private final ExecutorService executor;
    private final GeoIpService geoip;
    private final Logger log;

    private volatile String ipSalt;

    public AnalyticsService(
            DiscordMessageDao messages,
            DiscordMemberEventDao memberEvents,
            DiscordVoiceSessionDao voiceSessions,
            McSessionDao mcSessions,
            AnalyticsMetaDao meta,
            ExecutorService executor,
            GeoIpService geoip,
            Logger log
    ) {
        this.messages = messages;
        this.memberEvents = memberEvents;
        this.voiceSessions = voiceSessions;
        this.mcSessions = mcSessions;
        this.meta = meta;
        this.executor = executor;
        this.geoip = geoip;
        this.log = log;
    }

    /** Best-effort country lookup. Returns null when GeoIP is disabled / unknown. */
    public String lookupCountry(String rawIp) {
        return geoip.lookupCountryIso(rawIp).orElse(null);
    }

    /**
     * Loads (or generates) the per-install IP salt and closes any sessions
     * orphaned by a previous restart. Call once on plugin enable.
     */
    public void initOnEnable() {
        try {
            this.ipSalt = loadOrCreateIpSalt();
        } catch (Exception e) {
            log.log(Level.WARNING, "analytics: failed to load IP salt - IP hashes will be skipped", e);
            this.ipSalt = null;
        }
        long now = System.currentTimeMillis();
        executor.submit(() -> {
            try {
                int mc = mcSessions.closeAllOpen(now, MAX_SESSION_MS);
                int vc = voiceSessions.closeAllOpen(now, MAX_SESSION_MS);
                if (mc > 0 || vc > 0) {
                    log.info("analytics: closed " + mc + " orphan MC session(s) and " + vc + " orphan voice session(s) from prior run");
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "analytics: orphan session cleanup failed", e);
            }
        });
    }

    private String loadOrCreateIpSalt() throws Exception {
        var existing = meta.get(AnalyticsMetaDao.KEY_IP_SALT);
        if (existing.isPresent() && !existing.get().isBlank()) return existing.get();
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        meta.put(AnalyticsMetaDao.KEY_IP_SALT, hex);
        return hex;
    }

    public String hashIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank() || ipSalt == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ipSalt.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(rawIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    public void recordMessage(String discordId, String channelId, String guildId,
                              long atMs, int length, boolean hasAttachment, boolean isReply) {
        executor.submit(() -> {
            try {
                messages.insert(discordId, channelId, guildId, atMs, length, hasAttachment, isReply);
            } catch (Exception e) {
                log.log(Level.FINE, "analytics: message insert failed", e);
            }
        });
    }

    public void recordMemberEvent(String discordId, String kind, long atMs, String reason) {
        executor.submit(() -> {
            try {
                memberEvents.insert(discordId, kind, atMs, reason);
            } catch (Exception e) {
                log.log(Level.FINE, "analytics: member event insert failed", e);
            }
        });
    }

    public void recordVoiceSwitch(String discordId, String oldChannelId, String newChannelId,
                                  String guildId, long atMs) {
        executor.submit(() -> {
            try {
                if (oldChannelId != null) {
                    voiceSessions.closeOpenFor(discordId, atMs);
                }
                if (newChannelId != null) {
                    voiceSessions.open(discordId, newChannelId, guildId, atMs);
                }
            } catch (Exception e) {
                log.log(Level.FINE, "analytics: voice session write failed", e);
            }
        });
    }

    public void recordMcLogin(String uuid, String name, long startedAt,
                              String ipHash, String country, String clientBrand) {
        executor.submit(() -> {
            try {
                // Defensive close in case the previous logout never landed.
                mcSessions.closeOpenFor(uuid, startedAt);
                mcSessions.open(uuid, name, startedAt, ipHash, country, clientBrand);
            } catch (Exception e) {
                log.log(Level.FINE, "analytics: mc login insert failed", e);
            }
        });
    }

    public void recordMcLogout(String uuid, long endedAt) {
        executor.submit(() -> {
            try {
                mcSessions.closeOpenFor(uuid, endedAt);
            } catch (Exception e) {
                log.log(Level.FINE, "analytics: mc logout update failed", e);
            }
        });
    }
}
