package io.warden.tickets;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pipes inbound user messages back into the ticket thread on the dashboard:
 *  - DMs from a user with an active ticket  ->  appended as a user reply
 *  - Messages in a ticket channel           ->  appended as a user or staff reply
 *
 * Attachments are downloaded into the local attachments dir so they remain
 * available even if the Discord CDN URL expires. Also handles the "Close
 * ticket" button on channel-mode tickets.
 */
public final class TicketInboundListener extends ListenerAdapter {

    private final TicketService service;
    private final String modRoleProvider; // unused; kept for parity if needed later
    private final Logger log;
    private final HttpClient http = HttpClient.newHttpClient();

    public TicketInboundListener(TicketService service, Logger log) {
        this.service = service;
        this.modRoleProvider = "";
        this.log = log;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;

        try {
            if (event.getChannelType() == ChannelType.PRIVATE) {
                handleDm(event);
            } else if (event.getChannelType() == ChannelType.TEXT) {
                handleTicketChannel(event);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "TicketInboundListener.onMessageReceived failed", e);
        }
    }

    private void handleDm(MessageReceivedEvent event) {
        try {
            String discordId = event.getAuthor().getId();
            Optional<Ticket> active = service.tickets().findActiveForUser(discordId);
            if (active.isEmpty()) return;
            Ticket t = active.get();

            // If this ticket lives in a channel, ignore stray DMs (or guide the user).
            if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
                event.getChannel().sendMessage(
                        "Your ticket #" + t.id() + " is being handled in <#" + t.channelId() + ">; "
                                + "please post there so staff see your reply.").queue(ok -> {}, err -> {});
                return;
            }

            appendUserReply(event.getJDA(), t, event.getMessage(), discordId);
        } catch (Exception e) {
            log.log(Level.WARNING, "handleDm failed", e);
        }
    }

    private void handleTicketChannel(MessageReceivedEvent event) {
        try {
            String channelId = event.getChannel().getId();
            Optional<Ticket> tOpt = service.tickets().findByChannelId(channelId);
            if (tOpt.isEmpty()) return;
            Ticket t = tOpt.get();

            String authorId = event.getAuthor().getId();
            boolean isReporter = authorId.equals(t.discordId());
            String authorName = event.getAuthor().getEffectiveName();

            String body = event.getMessage().getContentRaw();
            List<TicketMessage.Attachment> atts = downloadAttachments(t.id(), event.getMessage());

            if (isReporter) {
                service.recordInboundUserMessage(event.getJDA(), t, body, atts);
            } else {
                // Treat any other member posting in the ticket channel as staff.
                try {
                    service.tickets().appendMessage(t.id(), TicketMessage.KIND_STAFF,
                            authorId, authorName, body == null ? "" : body, atts);
                    service.tickets().touch(t.id());
                } catch (Exception e) {
                    log.log(Level.WARNING, "channel staff append failed", e);
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "handleTicketChannel failed", e);
        }
    }

    private void appendUserReply(net.dv8tion.jda.api.JDA jda, Ticket t, Message msg, String discordId) {
        String body = msg.getContentRaw();
        List<TicketMessage.Attachment> atts = downloadAttachments(t.id(), msg);
        if ((body == null || body.isBlank()) && atts.isEmpty()) return;
        service.recordInboundUserMessage(jda, t, body, atts);
    }

    private List<TicketMessage.Attachment> downloadAttachments(long ticketId, Message msg) {
        List<TicketMessage.Attachment> out = new ArrayList<>();
        for (Message.Attachment att : msg.getAttachments()) {
            try {
                String name = att.getFileName();
                String url = att.getUrl();
                long size = att.getSize();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    log.warning("ticket " + ticketId + " attachment download HTTP " + resp.statusCode() + " for " + name);
                    out.add(new TicketMessage.Attachment(name, "", url, size));
                    continue;
                }
                try (InputStream body = resp.body()) {
                    TicketMessage.Attachment stored = service.storeDiscordAttachment(
                            ticketId, name, url, body, size);
                    out.add(stored);
                }
            } catch (IOException | InterruptedException e) {
                log.log(Level.WARNING, "downloadAttachments failed for ticket " + ticketId, e);
            }
        }
        return out;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith(TicketService.CLOSE_BUTTON_PREFIX)) return;
        long ticketId;
        try {
            ticketId = Long.parseLong(id.substring(TicketService.CLOSE_BUTTON_PREFIX.length()));
        } catch (NumberFormatException e) {
            event.reply("Malformed close button.").setEphemeral(true).queue();
            return;
        }
        try {
            String staffId = event.getUser().getId();
            String staffName = event.getUser().getEffectiveName();
            boolean ok = service.changeStatus(event.getJDA(), ticketId, TicketStatus.CLOSED, staffId, staffName);
            event.reply(ok ? "Ticket #" + ticketId + " closed." : "Could not close ticket.")
                    .setEphemeral(true).queue();
        } catch (Exception e) {
            log.log(Level.WARNING, "close button failed", e);
            if (!event.isAcknowledged()) {
                event.reply("Failed to close ticket.").setEphemeral(true).queue();
            }
        }
    }
}
