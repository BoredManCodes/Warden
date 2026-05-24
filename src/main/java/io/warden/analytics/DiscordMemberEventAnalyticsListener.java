package io.warden.analytics;

import io.warden.data.dao.DiscordMemberEventDao;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/** Logs join / leave / ban / unban events for the configured guild. */
public final class DiscordMemberEventAnalyticsListener extends ListenerAdapter {

    private final AnalyticsService analytics;
    private final String configuredGuildId;

    public DiscordMemberEventAnalyticsListener(AnalyticsService analytics, String configuredGuildId) {
        this.analytics = analytics;
        this.configuredGuildId = configuredGuildId == null ? "" : configuredGuildId;
    }

    private boolean isThisGuild(String guildId) {
        return configuredGuildId.isBlank() || configuredGuildId.equals(guildId);
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        analytics.recordMemberEvent(
                event.getUser().getId(),
                DiscordMemberEventDao.KIND_JOIN,
                event.getMember().getTimeJoined().toInstant().toEpochMilli(),
                null);
    }

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        analytics.recordMemberEvent(
                event.getUser().getId(),
                DiscordMemberEventDao.KIND_LEAVE,
                System.currentTimeMillis(),
                null);
    }

    @Override
    public void onGuildBan(@NotNull GuildBanEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        analytics.recordMemberEvent(
                event.getUser().getId(),
                DiscordMemberEventDao.KIND_BAN,
                System.currentTimeMillis(),
                null);
    }

    @Override
    public void onGuildUnban(@NotNull GuildUnbanEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        analytics.recordMemberEvent(
                event.getUser().getId(),
                DiscordMemberEventDao.KIND_UNBAN,
                System.currentTimeMillis(),
                null);
    }
}
