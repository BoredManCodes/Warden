package io.warden.discord.listeners;

import io.warden.discord.flow.DmFlow;
import io.warden.onboarding.OnboardingService;
import io.warden.onboarding.model.AnswerValue;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles submissions of the answerModal sent by ButtonListener. Custom-id format:
 * onboard:modal:&lt;questionId&gt; - the text input is always named "value".
 */
public final class ModalListener extends ListenerAdapter {

    private final OnboardingService service;
    private final Logger log;

    public ModalListener(OnboardingService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id == null || !id.startsWith("onboard:modal:")) return;

        long questionId;
        try {
            questionId = Long.parseLong(id.substring("onboard:modal:".length()));
        } catch (NumberFormatException e) {
            event.reply("Malformed modal id.").setEphemeral(true).queue();
            return;
        }

        var input = event.getValue(DmFlow.MODAL_VALUE_ID);
        String value = input != null ? input.getAsString() : "";
        String discordId = event.getUser().getId();
        String guildName = event.getGuild() != null ? event.getGuild().getName() : "the server";

        try {
            var step = service.submitAnswer(discordId, questionId, AnswerValue.of(value));
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
            log.log(Level.WARNING, "ModalListener for id=" + id + " failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong saving that answer - try again.").setEphemeral(true).queue();
            }
        }
    }
}
