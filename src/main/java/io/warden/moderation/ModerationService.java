package io.warden.moderation;

import io.warden.audit.AuditService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.attribute.ISlowmodeChannel;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level moderation actions. Wraps JDA primitives so the slash command
 * handlers and auto-mod listener share a single code path.
 */
public final class ModerationService {

    private final ModActionDao actions;
    private final WarningDao warnings;
    private final AuditService audit;
    private final Logger log;

    /** In-memory join queue for raid detection; cleared periodically. */
    private final ConcurrentMap<String, Long> recentJoinTimestamps = new ConcurrentHashMap<>();

    public ModerationService(ModActionDao actions, WarningDao warnings, AuditService audit, Logger log) {
        this.actions = actions;
        this.warnings = warnings;
        this.audit = audit;
        this.log = log;
    }

    public ModActionDao actionDao() { return actions; }

    public void kick(Guild guild, String targetId, String moderatorId, String reason) {
        guild.kick(UserSnowflake.fromId(targetId)).reason(safe(reason)).queue(
                ok -> recordAction("kick", targetId, moderatorId, reason, null, null),
                err -> log.log(Level.WARNING, "kick failed: " + err.getMessage(), err));
    }

    public void ban(Guild guild, String targetId, String moderatorId, String reason, int deleteMessageDays) {
        guild.ban(UserSnowflake.fromId(targetId), deleteMessageDays, java.util.concurrent.TimeUnit.DAYS)
                .reason(safe(reason)).queue(
                        ok -> recordAction("ban", targetId, moderatorId, reason, null, null),
                        err -> log.log(Level.WARNING, "ban failed: " + err.getMessage(), err));
    }

    public void tempban(Guild guild, String targetId, String moderatorId, String reason, int durationSeconds) {
        long expiresAt = System.currentTimeMillis() + durationSeconds * 1000L;
        guild.ban(UserSnowflake.fromId(targetId), 0, java.util.concurrent.TimeUnit.DAYS)
                .reason(safe(reason)).queue(
                        ok -> recordAction("tempban", targetId, moderatorId, reason, durationSeconds, expiresAt),
                        err -> log.log(Level.WARNING, "tempban failed: " + err.getMessage(), err));
    }

    public void timeout(Guild guild, String targetId, String moderatorId, String reason, int durationSeconds) {
        Duration dur = Duration.ofSeconds(durationSeconds);
        guild.retrieveMemberById(targetId).queue(member -> {
            if (member == null) return;
            member.timeoutFor(dur).reason(safe(reason)).queue(
                    ok -> recordAction("timeout", targetId, moderatorId, reason,
                            durationSeconds, System.currentTimeMillis() + durationSeconds * 1000L),
                    err -> log.log(Level.WARNING, "timeout failed: " + err.getMessage(), err));
        }, err -> log.log(Level.WARNING, "timeout: member not found: " + targetId));
    }

    public void mute(Guild guild, String targetId, String moderatorId, String reason,
                     int durationSeconds, String mutedRoleId) {
        if (mutedRoleId == null || mutedRoleId.isBlank()) {
            // Fall back to a timeout when no muted-role is configured.
            timeout(guild, targetId, moderatorId, reason, durationSeconds);
            return;
        }
        Role role = guild.getRoleById(mutedRoleId);
        if (role == null) {
            log.warning("mute: muted role " + mutedRoleId + " not found, falling back to timeout");
            timeout(guild, targetId, moderatorId, reason, durationSeconds);
            return;
        }
        guild.retrieveMemberById(targetId).queue(member -> {
            if (member == null) return;
            long expiresAt = System.currentTimeMillis() + durationSeconds * 1000L;
            guild.addRoleToMember(member, role).reason(safe(reason)).queue(
                    ok -> recordAction("mute", targetId, moderatorId, reason,
                            durationSeconds, expiresAt),
                    err -> log.log(Level.WARNING, "mute failed: " + err.getMessage(), err));
        });
    }

    public void unmute(Guild guild, String targetId, String moderatorId, String mutedRoleId) {
        if (mutedRoleId == null || mutedRoleId.isBlank()) return;
        Role role = guild.getRoleById(mutedRoleId);
        if (role == null) return;
        guild.retrieveMemberById(targetId).queue(member -> {
            if (member == null) return;
            guild.removeRoleFromMember(member, role).reason("auto-unmute").queue(
                    ok -> recordAction("unmute", targetId, moderatorId, "auto", null, null),
                    err -> log.log(Level.WARNING, "unmute failed: " + err.getMessage(), err));
        });
    }

    public void setSlowmode(GuildChannel channel, int seconds, String moderatorId) {
        if (channel instanceof ISlowmodeChannel slow) {
            slow.getManager().setSlowmode(seconds).queue(
                    ok -> {
                        recordAction("slowmode", channel.getId(), moderatorId, "slowmode=" + seconds, seconds, null);
                    },
                    err -> log.log(Level.WARNING, "slowmode failed: " + err.getMessage(), err));
        }
    }

    public long warn(String targetId, String moderatorId, String reason, int severity) {
        try {
            long id = warnings.create(targetId, moderatorId, reason, severity);
            audit.write("mod:" + moderatorId, "warn", targetId, java.util.Map.of(
                    "reason", reason == null ? "" : reason, "id", id, "severity", severity));
            return id;
        } catch (Exception e) {
            log.log(Level.WARNING, "warn write failed", e);
            return -1;
        }
    }

    public void recordAction(String action, String targetId, String moderatorId,
                             String reason, Integer durationSeconds, Long expiresAt) {
        try {
            actions.record(action, targetId, moderatorId, reason, durationSeconds, expiresAt);
            audit.write("mod:" + moderatorId, "mod_action", targetId, java.util.Map.of(
                    "action", action,
                    "reason", reason == null ? "" : reason,
                    "durationSeconds", durationSeconds == null ? -1 : durationSeconds));
        } catch (Exception e) {
            log.log(Level.WARNING, "mod action write failed", e);
        }
    }

    /** Returns the timestamp count for raid detection. The caller decides what to do if it's too high. */
    public int recentJoins(int windowSeconds) {
        long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
        recentJoinTimestamps.values().removeIf(t -> t < cutoff);
        return recentJoinTimestamps.size();
    }

    public void recordJoin(String userId) {
        recentJoinTimestamps.put(userId, System.currentTimeMillis());
    }

    public void clearJoinHistory() { recentJoinTimestamps.clear(); }

    /** Process expired mod actions: unmute on mute expiry, unban on tempban expiry. */
    public void revokeDue(JDA jda, String guildId, String mutedRoleId) {
        if (jda == null) return;
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return;
        try {
            long now = System.currentTimeMillis();
            for (ModActionDao.ModAction a : actions.dueForRevoke(now)) {
                switch (a.action()) {
                    case "tempban" -> guild.unban(UserSnowflake.fromId(a.targetDiscordId())).reason("tempban expired").queue(
                            ok -> safeMarkRevoked(a.id()),
                            err -> log.log(Level.WARNING, "unban failed: " + err.getMessage()));
                    case "mute" -> {
                        if (mutedRoleId != null && !mutedRoleId.isBlank()) {
                            Role role = guild.getRoleById(mutedRoleId);
                            if (role != null) {
                                guild.retrieveMemberById(a.targetDiscordId()).queue(m -> {
                                    if (m != null) {
                                        guild.removeRoleFromMember(m, role).reason("mute expired").queue(
                                                ok -> safeMarkRevoked(a.id()),
                                                err -> log.log(Level.WARNING, "auto-unmute failed: " + err.getMessage()));
                                    } else safeMarkRevoked(a.id());
                                }, err -> safeMarkRevoked(a.id()));
                            } else safeMarkRevoked(a.id());
                        } else safeMarkRevoked(a.id());
                    }
                    case "timeout" -> safeMarkRevoked(a.id());
                    default -> safeMarkRevoked(a.id());
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "revokeDue failed", e);
        }
    }

    private void safeMarkRevoked(long id) {
        try { actions.markRevoked(id); } catch (Exception ignored) {}
    }

    private static String safe(String r) {
        if (r == null) return "";
        if (r.length() > 500) return r.substring(0, 500);
        return r;
    }
}
