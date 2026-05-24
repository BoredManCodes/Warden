package io.warden.tickets;

public record Ticket(
        long id,
        Long categoryId,
        String discordId,
        String discordUsername,
        String subject,
        String body,
        TicketStatus status,
        String assigneeId,
        String assigneeName,
        String staffChannelId,
        String staffMessageId,
        String mode,
        String channelId,
        long lastActivityAt,
        long createdAt,
        Long closedAt,
        String transcriptPath,
        String transcriptToken,
        Long transcriptGeneratedAt,
        String mirrorChannelId,
        String mirrorWebhookId,
        String mirrorWebhookToken
) {
    public boolean isChannelMode() {
        return TicketsConfig.MODE_CHANNEL.equalsIgnoreCase(mode);
    }

    public boolean hasTranscript() {
        return transcriptToken != null && !transcriptToken.isBlank()
                && transcriptPath != null && !transcriptPath.isBlank();
    }

    public boolean hasMirror() {
        return mirrorChannelId != null && !mirrorChannelId.isBlank()
                && mirrorWebhookId != null && !mirrorWebhookId.isBlank()
                && mirrorWebhookToken != null && !mirrorWebhookToken.isBlank();
    }
}
