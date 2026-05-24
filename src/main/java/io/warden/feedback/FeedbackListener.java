package io.warden.feedback;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vote buttons (fb:up:* / fb:down:*) and the feedback-modal submission
 * (fbmodal). Slash command + button-to-open-modal lives in
 * {@link FeedbackCommandListener}.
 */
public final class FeedbackListener extends ListenerAdapter {

    public static final String MODAL_ID = "fbmodal";
    public static final String MODAL_TITLE = "title";
    public static final String MODAL_BODY = "body";

    private final FeedbackService service;
    private final Logger log;

    public FeedbackListener(FeedbackService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null) return;
        int dir;
        long fid;
        if (id.startsWith(FeedbackService.VOTE_UP_PREFIX)) {
            dir = 1;
            try { fid = Long.parseLong(id.substring(FeedbackService.VOTE_UP_PREFIX.length())); }
            catch (NumberFormatException e) { return; }
        } else if (id.startsWith(FeedbackService.VOTE_DOWN_PREFIX)) {
            dir = -1;
            try { fid = Long.parseLong(id.substring(FeedbackService.VOTE_DOWN_PREFIX.length())); }
            catch (NumberFormatException e) { return; }
        } else {
            return;
        }
        event.deferReply(true).queue();
        String reply = service.onVote(event.getJDA(), event.getUser().getId(), fid, dir);
        event.getHook().sendMessage(reply).setEphemeral(true).queue();
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!MODAL_ID.equals(event.getModalId())) return;
        try {
            var titleInput = event.getValue(MODAL_TITLE);
            var bodyInput = event.getValue(MODAL_BODY);
            String title = titleInput == null ? "" : titleInput.getAsString().trim();
            String body = bodyInput == null ? "" : bodyInput.getAsString().trim();
            if (title.isEmpty() || body.isEmpty()) {
                event.reply("Both fields are required.").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue();
            FeedbackService.OpenResult result = service.open(event.getJDA(),
                    event.getUser().getId(), event.getUser().getEffectiveName(), title, body);
            if (!result.ok()) {
                event.getHook().sendMessage(result.error()).setEphemeral(true).queue();
                return;
            }
            event.getHook().sendMessage("Thanks! Feedback #" + result.id()
                    + " posted. Other members can now vote on it.").setEphemeral(true).queue();
        } catch (Exception e) {
            log.log(Level.WARNING, "feedback modal failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong, try again.").setEphemeral(true).queue();
            } else {
                event.getHook().sendMessage("Something went wrong, try again.").setEphemeral(true).queue();
            }
        }
    }
}
