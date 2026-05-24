package io.warden.discord.listeners;

import io.warden.discord.flow.DmFlow;
import io.warden.onboarding.OnboardingService;
import io.warden.onboarding.OnboardingService.StepResult;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.QuestionKind;
import io.warden.data.dao.QuestionDao;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes onboard:* button clicks to OnboardingService and replies in the DM
 * (or for "open" button, opens the appropriate modal).
 */
public final class ButtonListener extends ListenerAdapter {

    private final OnboardingService service;
    private final QuestionDao questionDao;
    private final Logger log;

    public ButtonListener(OnboardingService service, QuestionDao questionDao, Logger log) {
        this.service = service;
        this.questionDao = questionDao;
        this.log = log;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("onboard:")) return;

        String[] parts = id.split(":", -1);
        if (parts.length < 2) return;
        String discordId = event.getUser().getId();
        String guildName = event.getGuild() != null ? event.getGuild().getName() : "the server";

        try {
            switch (parts[1]) {
                case "start" -> handleSimple(event, service.onStart(discordId), guildName);
                case "agree" -> handleSimple(event, service.onAgree(discordId), guildName);
                case "disagree" -> handleSimple(event, service.onDisagree(discordId), guildName);
                case "open" -> {
                    if (parts.length < 3) {
                        event.reply("Malformed button.").setEphemeral(true).queue();
                        return;
                    }
                    long questionId;
                    try {
                        questionId = Long.parseLong(parts[2]);
                    } catch (NumberFormatException e) {
                        event.reply("Malformed button.").setEphemeral(true).queue();
                        return;
                    }
                    Optional<Question> q = questionDao.findById(questionId);
                    if (q.isEmpty() || !q.get().active()
                            || (q.get().kind() != QuestionKind.SHORT_TEXT && q.get().kind() != QuestionKind.LONG_TEXT)) {
                        event.reply("That question is no longer accepting text answers.").setEphemeral(true).queue();
                        return;
                    }
                    event.replyModal(DmFlow.answerModal(q.get())).queue();
                }
                default -> {
                    // Unknown onboard subcommand - ignore silently so we don't spam misclicks.
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "ButtonListener for id=" + id + " failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong - try again, or ask a mod.").setEphemeral(true).queue();
            } else {
                event.getHook().sendMessage("Something went wrong - try again, or ask a mod.").setEphemeral(true).queue();
            }
        }
    }

    private void handleSimple(ButtonInteractionEvent event, StepResult step, String guildName) {
        var messages = DmFlow.render(step, guildName);
        if (messages.isEmpty()) {
            event.deferReply(true).queue();
            return;
        }
        var first = messages.get(0);
        event.reply(first).queue();
        for (int i = 1; i < messages.size(); i++) {
            event.getHook().sendMessage(messages.get(i)).queue();
        }
    }
}
