package io.warden.analytics;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/** Captures one row per guild message. Body content is never stored. */
public final class DiscordMessageAnalyticsListener extends ListenerAdapter {

    private final AnalyticsService analytics;
    private final String configuredGuildId;

    public DiscordMessageAnalyticsListener(AnalyticsService analytics, String configuredGuildId) {
        this.analytics = analytics;
        this.configuredGuildId = configuredGuildId == null ? "" : configuredGuildId;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;
        String guildId = event.getGuild().getId();
        if (!configuredGuildId.isBlank() && !configuredGuildId.equals(guildId)) return;

        Message msg = event.getMessage();
        analytics.recordMessage(
                event.getAuthor().getId(),
                event.getChannel().getId(),
                guildId,
                msg.getTimeCreated().toInstant().toEpochMilli(),
                msg.getContentRaw().length(),
                !msg.getAttachments().isEmpty(),
                msg.getReferencedMessage() != null
        );
    }
}
