package io.warden.feedback;

public record Feedback(
        long id,
        String discordId,
        String discordUsername,
        String title,
        String body,
        FeedbackStatus status,
        String staffResponse,
        String channelId,
        String messageId,
        long createdAt,
        long updatedAt,
        Long closedAt
) {}
