package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.tickets.Ticket;
import io.warden.tickets.TicketDao;
import io.warden.tickets.TranscriptService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Public, token-gated transcript endpoints. The DM sent to the reporter when
 * their ticket closes points at /tickets/transcript/{token}, and the rendered
 * HTML references images and files via /tickets/transcript-asset/{ticketId}/
 * {messageId}/{name}. Authentication is the unguessable token itself; asset
 * fetches require the ticket to still have a transcript token assigned.
 */
public final class TranscriptPublicHandlers {

    private final TicketDao tickets;
    private final TranscriptService transcripts;

    public TranscriptPublicHandlers(TicketDao tickets, TranscriptService transcripts) {
        this.tickets = tickets;
        this.transcripts = transcripts;
    }

    public void view(Context ctx) throws Exception {
        String token = ctx.pathParam("token");
        Ticket t = tickets.findByTranscriptToken(token).orElse(null);
        if (t == null) {
            ctx.status(404).html(notFound());
            return;
        }
        java.util.Optional<Path> file = transcripts.transcriptFile(t);
        if (file.isEmpty()) {
            ctx.status(404).html(notFound());
            return;
        }
        ctx.contentType("text/html; charset=utf-8");
        ctx.result(Files.newInputStream(file.get()));
    }

    public void asset(Context ctx) throws Exception {
        long ticketId = Long.parseLong(ctx.pathParam("ticketId"));
        long messageId = Long.parseLong(ctx.pathParam("messageId"));
        String name = ctx.pathParam("name");
        Ticket t = tickets.find(ticketId).orElse(null);
        if (t == null || !t.hasTranscript()) {
            ctx.status(404).html("Asset not found.");
            return;
        }
        java.util.Optional<Path> p = transcripts.resolveAttachment(ticketId, messageId, name);
        if (p.isEmpty()) {
            ctx.status(404).html("Asset not found.");
            return;
        }
        ctx.header("Cache-Control", "private, max-age=86400");
        String guess = java.net.URLConnection.guessContentTypeFromName(name);
        ctx.contentType(guess == null ? "application/octet-stream" : guess);
        ctx.result(Files.newInputStream(p.get()));
    }

    private static String notFound() {
        return "<!doctype html><meta charset=utf-8><title>Transcript not found · Warden</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:520px;margin:80px auto;"
                + "color:#2e3338;padding:0 16px;}h1{margin-bottom:.4em;}</style>"
                + "<h1>Transcript not found</h1>"
                + "<p>This transcript link is invalid, expired, or has been removed by staff.</p>";
    }
}
