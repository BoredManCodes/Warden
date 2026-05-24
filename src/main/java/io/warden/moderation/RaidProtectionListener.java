package io.warden.moderation;

import io.warden.audit.AuditService;
import io.warden.discord.WardenEmbeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Anti-raid: counts joins inside a rolling window and trips a lockdown when the
 * configured threshold is crossed. Lockdown applies the configured action to
 * each new joiner until the auto-disable timer elapses. Also rejects accounts
 * younger than the configured minimum age regardless of lockdown state.
 */
public final class RaidProtectionListener extends ListenerAdapter {

    private final RaidProtectionDao dao;
    private final ModerationService moderation;
    private final AuditService audit;
    private final Logger log;

    public RaidProtectionListener(RaidProtectionDao dao, ModerationService moderation,
                                  AuditService audit, Logger log) {
        this.dao = dao;
        this.moderation = moderation;
        this.audit = audit;
        this.log = log;
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        RaidProtectionDao.Config cfg;
        try { cfg = dao.get(); } catch (Exception e) {
            log.log(Level.WARNING, "raid: config load failed", e);
            return;
        }
        if (!cfg.enabled()) return;

        Member member = event.getMember();
        Guild guild = event.getGuild();
        moderation.recordJoin(member.getId());

        if (cfg.accountAgeMinDays() > 0) {
            Instant created = member.getUser().getTimeCreated().toInstant();
            long days = Duration.between(created, Instant.now()).toDays();
            if (days < cfg.accountAgeMinDays()) {
                applyAction(guild, member.getId(), cfg, "account_too_young_" + days + "d");
                return;
            }
        }

        long now = System.currentTimeMillis();
        boolean inLockdown = cfg.lockdownUntil() != null && cfg.lockdownUntil() > now;
        if (!inLockdown) {
            int recent = moderation.recentJoins(cfg.joinsWindowSeconds());
            if (recent >= cfg.joinsThreshold()) {
                long until = now + cfg.autoDisableMinutes() * 60_000L;
                try {
                    dao.setLockdownUntil(until);
                    audit.write("system", "raid_lockdown_triggered", null, java.util.Map.of(
                            "joinsInWindow", recent,
                            "windowSeconds", cfg.joinsWindowSeconds(),
                            "until", until));
                } catch (Exception ignored) {}
                inLockdown = true;
                if (cfg.logChannelId() != null && !cfg.logChannelId().isBlank()) {
                    TextChannel ch = guild.getTextChannelById(cfg.logChannelId());
                    if (ch != null) {
                        MessageEmbed embed = WardenEmbeds.brand(new EmbedBuilder()
                                .setTitle("Raid protection triggered")
                                .setDescription(recent + " joins in " + cfg.joinsWindowSeconds()
                                        + "s crossed the configured threshold.")
                                .addField("Lockdown until", "<t:" + (until / 1000) + ":t>", true)
                                .addField("Action", cfg.lockdownAction() == null
                                        ? "log only" : cfg.lockdownAction(), true)
                                .setColor(new Color(0xE53935))
                                .setTimestamp(Instant.now()))
                                .build();
                        ch.sendMessageEmbeds(embed).queue(ok -> {}, err -> {});
                    }
                }
            }
        }
        if (inLockdown) {
            applyAction(guild, member.getId(), cfg, "raid_lockdown");
        }
    }

    private void applyAction(Guild guild, String userId, RaidProtectionDao.Config cfg, String reason) {
        switch (cfg.lockdownAction() == null ? "" : cfg.lockdownAction().toLowerCase(java.util.Locale.ROOT)) {
            case "ban" -> guild.ban(UserSnowflake.fromId(userId), 0, java.util.concurrent.TimeUnit.DAYS)
                    .reason(reason).queue(ok -> {}, err -> {});
            case "kick" -> guild.kick(UserSnowflake.fromId(userId)).reason(reason).queue(ok -> {}, err -> {});
            default -> {
                // log only
            }
        }
        try {
            audit.write("raid", "raid_action_applied", userId, java.util.Map.of(
                    "action", cfg.lockdownAction(), "reason", reason));
        } catch (Exception ignored) {}
    }
}
