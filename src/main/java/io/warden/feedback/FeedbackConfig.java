package io.warden.feedback;

public record FeedbackConfig(
        String channelId,
        boolean openViaCommand,
        boolean dmReporterOnStatus,
        boolean dmReporterOnResponse,
        boolean requireUniquePerUser,
        boolean lockedWhenResolved
) {
    public static FeedbackConfig blank() {
        return new FeedbackConfig("", true, true, true, false, true);
    }
}
