package io.warden.feedback;

public record FeedbackNote(
        long id,
        long feedbackId,
        String authorKind,
        String authorId,
        String authorName,
        String body,
        long createdAt
) {
    public static final String KIND_USER = "user";
    public static final String KIND_STAFF = "staff";
    public static final String KIND_SYSTEM = "system";
}
