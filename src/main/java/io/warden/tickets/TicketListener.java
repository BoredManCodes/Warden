package io.warden.tickets;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes ticket:* button clicks (open a modal) and ticket:modal:* submissions
 * (create the ticket) to {@link TicketService}.
 */
public final class TicketListener extends ListenerAdapter {

    private final TicketService service;
    private final Logger log;

    public TicketListener(TicketService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith(TicketService.OPEN_BUTTON_PREFIX)) return;
        long categoryId;
        try {
            categoryId = Long.parseLong(id.substring(TicketService.OPEN_BUTTON_PREFIX.length()));
        } catch (NumberFormatException e) {
            event.reply("Malformed button.").setEphemeral(true).queue();
            return;
        }
        try {
            var catOpt = service.categories().find(categoryId);
            if (catOpt.isEmpty() || !catOpt.get().enabled()) {
                event.reply("That category is unavailable.").setEphemeral(true).queue();
                return;
            }
            TicketCategory cat = catOpt.get();

            TextInput subject = TextInput.create(TicketService.MODAL_SUBJECT_FIELD, "Subject", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMinLength(3)
                    .setMaxLength(120)
                    .setPlaceholder("Short summary")
                    .build();
            TextInput body = TextInput.create(TicketService.MODAL_BODY_FIELD, "Details", TextInputStyle.PARAGRAPH)
                    .setRequired(true)
                    .setMinLength(5)
                    .setMaxLength(3500)
                    .setPlaceholder("Describe your " + cat.name().toLowerCase() + " in as much detail as you like.")
                    .build();
            Modal modal = Modal.create(
                            TicketService.MODAL_PREFIX + cat.id(),
                            "Open ticket: " + cat.name())
                    .addActionRow(subject)
                    .addActionRow(body)
                    .build();
            event.replyModal(modal).queue();
        } catch (Exception e) {
            log.log(Level.WARNING, "ticket open button failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Could not open the ticket form, try again.").setEphemeral(true).queue();
            }
        }
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id == null || !id.startsWith(TicketService.MODAL_PREFIX)) return;
        long categoryId;
        try {
            categoryId = Long.parseLong(id.substring(TicketService.MODAL_PREFIX.length()));
        } catch (NumberFormatException e) {
            event.reply("Malformed modal.").setEphemeral(true).queue();
            return;
        }
        try {
            var subjectInput = event.getValue(TicketService.MODAL_SUBJECT_FIELD);
            var bodyInput = event.getValue(TicketService.MODAL_BODY_FIELD);
            String subject = subjectInput == null ? "" : subjectInput.getAsString().trim();
            String body = bodyInput == null ? "" : bodyInput.getAsString().trim();
            if (subject.isEmpty() || body.isEmpty()) {
                event.reply("Both fields are required.").setEphemeral(true).queue();
                return;
            }
            String discordId = event.getUser().getId();
            String username = event.getUser().getEffectiveName();
            long id2 = service.openTicket(event.getJDA(), categoryId, discordId, username, subject, body);
            if (id2 < 0) {
                event.reply("Could not create the ticket. Please ping a mod.").setEphemeral(true).queue();
                return;
            }
            event.reply("Ticket #" + id2 + " opened. Staff will be in touch.").setEphemeral(true).queue();
        } catch (Exception e) {
            log.log(Level.WARNING, "ticket modal submit failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Something went wrong, try again.").setEphemeral(true).queue();
            }
        }
    }
}
