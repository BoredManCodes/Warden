package io.warden.engagement;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public final class EngagementListener extends ListenerAdapter {

    private final EngagementService service;
    private final Logger log;

    public EngagementListener(EngagementService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null) return;
        JDA jda = event.getJDA();
        if (id.startsWith(EngagementService.POLL_BUTTON_PREFIX)) {
            String[] parts = id.substring(EngagementService.POLL_BUTTON_PREFIX.length()).split(":");
            if (parts.length != 2) return;
            long pollId;
            int optionIndex;
            try {
                pollId = Long.parseLong(parts[0]);
                optionIndex = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) { return; }
            event.deferReply(true).queue();
            String reply = service.onPollClick(jda, event.getUser().getId(), pollId, optionIndex);
            event.getHook().sendMessage(reply).setEphemeral(true).queue();
        } else if (id.startsWith(EngagementService.GIVEAWAY_BUTTON_PREFIX)) {
            String[] parts = id.substring(EngagementService.GIVEAWAY_BUTTON_PREFIX.length()).split(":");
            if (parts.length != 2) return;
            long giveId;
            try { giveId = Long.parseLong(parts[0]); }
            catch (NumberFormatException e) { return; }
            if (event.getMember() == null) {
                event.reply("Guild only.").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue();
            String reply = service.onGiveawayClick(jda, event.getMember(), giveId, parts[1]);
            event.getHook().sendMessage(reply).setEphemeral(true).queue();
        }
    }
}
