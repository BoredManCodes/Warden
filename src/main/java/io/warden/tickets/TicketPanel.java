package io.warden.tickets;

public record TicketPanel(
        long id,
        String channelId,
        String messageId,
        String title,
        String description,
        String colorHex,
        long createdAt,
        long updatedAt
) {}
