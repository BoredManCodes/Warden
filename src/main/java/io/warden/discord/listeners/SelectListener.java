package io.warden.discord.listeners;

import io.warden.discord.flow.DmFlow;
import io.warden.onboarding.OnboardingService;
import io.warden.onboarding.model.AnswerValue;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles selections on single/multi choice questions sent by DmFlow.askQuestion.
 * Custom-id format: onboard:choice:&lt;questionId&gt;
 */
public final class SelectListener extends ListenerAdapter {

    private final OnboardingService service;
    private final Logger log;

    public SelectListener(OnboardingService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("onboard:choice:")) return;

        long questionId;
        try {
            questionId = Long.parseLong(id.substring("onboard:choice:".length()));
        } catch (NumberFormatException e) {
            event.reply("Malformed select id.").setEphemeral(true).queue();
            return;
        }

        String discordId = event.getUser().getId();
        String guildName = event.getGuild() != null ? event.getGuild().getName() : "the server";

        List<String> values = event.getValues();
        // single = first, multi = all; OnboardingService.validate handles either against question kind.
        AnswerValue answer = values.size() == 1
                ? AnswerValue.of(values.get(0))
                : AnswerValue.of(values);

        try {
            var step = service.submitAnswer(discordId, questionId, answer);
            var messages = DmFlow.render(step, guildName);
            if (messages.isEmpty()) {
                event.deferReply(true).queue();
                return;
            }
            event.reply(messages.get(0)).queue();
            for (int i = 1; i < messages.size(); i++) {
                event.getHook().sendMessage(messages.get(i)).queue();
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "SelectListener for id=" + id + " failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong saving that selection - try again.").setEphemeral(true).queue();
            }
        }
    }
}
