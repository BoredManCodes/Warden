package io.warden.analytics;

import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/** Tracks voice channel join / leave / move so we can chart VC activity. */
public final class DiscordVoiceAnalyticsListener extends ListenerAdapter {

    private final AnalyticsService analytics;
    private final String configuredGuildId;

    public DiscordVoiceAnalyticsListener(AnalyticsService analytics, String configuredGuildId) {
        this.analytics = analytics;
        this.configuredGuildId = configuredGuildId == null ? "" : configuredGuildId;
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        String guildId = event.getGuild().getId();
        if (!configuredGuildId.isBlank() && !configuredGuildId.equals(guildId)) return;

        AudioChannelUnion left = event.getChannelLeft();
        AudioChannelUnion joined = event.getChannelJoined();
        analytics.recordVoiceSwitch(
                event.getMember().getId(),
                left == null ? null : left.getId(),
                joined == null ? null : joined.getId(),
                guildId,
                System.currentTimeMillis()
        );
    }
}
