package io.warden.moderation;

import io.warden.audit.AuditService;
import io.warden.data.dao.SettingsDao;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires the {@link AutoModService} rule evaluator into JDA. On a hit we delete
 * the offending message, write an audit row, optionally post to a log channel,
 * and (when warn thresholds are configured) escalate by recording a warning.
 */
public final class AutoModListener extends ListenerAdapter {

    private final AutoModService autoMod;
    private final ModerationService moderation;
    private final SettingsDao settingsDao;
    private final AuditService audit;
    private final Logger log;

    public AutoModListener(AutoModService autoMod, ModerationService moderation,
                           SettingsDao settingsDao, AuditService audit, Logger log) {
        this.autoMod = autoMod;
        this.moderation = moderation;
        this.settingsDao = settingsDao;
        this.audit = audit;
        this.log = log;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        AutoModService.Violation v = autoMod.evaluate(event.getMessage());
        if (v == null) return;

        String userId = event.getAuthor().getId();
        try {
            event.getMessage().delete().reason("automod: " + v.rule()).queue(
                    ok -> {},
                    err -> log.log(Level.FINE, "automod delete failed: " + err.getMessage()));
        } catch (Exception ignored) {}

        try {
            audit.write("automod", "automod_hit", userId, java.util.Map.of(
                    "rule", v.rule(),
                    "reason", v.reason(),
                    "channelId", event.getChannel().getId(),
                    "messageId", event.getMessageId()));
        } catch (Exception ignored) {}

        AutomodConfig cfg = v.cfg();
        if (cfg.logChannelId() != null && !cfg.logChannelId().isBlank()) {
            TextChannel logCh = event.getGuild().getTextChannelById(cfg.logChannelId());
            if (logCh != null) {
                String text = "[automod] " + event.getAuthor().getAsMention()
                        + " in <#" + event.getChannel().getId() + ">: **" + v.rule() + "** "
                        + sanitize(v.reason());
                logCh.sendMessage(text).queue(ok -> {}, err -> {});
            }
        }

        long warnId = moderation.warn(userId, "automod", "automod: " + v.rule(), 1);
        if (warnId < 0) return;

        int totalWarnings;
        try { totalWarnings = autoMod.warningDao().activeCount(userId); }
        catch (Exception e) { return; }

        for (AutomodConfig.WarnThreshold t : cfg.warnThresholds()) {
            if (t.count() == totalWarnings) {
                applyThreshold(event.getGuild(), userId, t);
            }
        }
    }

    private void applyThreshold(Guild guild, String targetId, AutomodConfig.WarnThreshold t) {
        try {
            String mutedRoleId = "";
            try { mutedRoleId = settingsDao.get().gatedRoleId(); }
            catch (Exception ignored) {}
            int duration = Math.max(0, t.durationSeconds());
            switch (t.action() == null ? "" : t.action().toLowerCase(java.util.Locale.ROOT)) {
                case "mute" -> moderation.mute(guild, targetId, "automod",
                        "warn threshold " + t.count(), duration > 0 ? duration : 600, mutedRoleId);
                case "timeout" -> moderation.timeout(guild, targetId, "automod",
                        "warn threshold " + t.count(), duration > 0 ? duration : 600);
                case "kick" -> moderation.kick(guild, targetId, "automod", "warn threshold " + t.count());
                case "tempban" -> moderation.tempban(guild, targetId, "automod",
                        "warn threshold " + t.count(), duration > 0 ? duration : 86400);
                case "ban" -> moderation.ban(guild, targetId, "automod", "warn threshold " + t.count(), 0);
                default -> {}
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "automod threshold action failed", e);
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        if (s.length() > 200) s = s.substring(0, 200) + "...";
        return s.replace("@", "@​");
    }
}
