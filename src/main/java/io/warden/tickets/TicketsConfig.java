package io.warden.tickets;

public record TicketsConfig(
        String staffChannelId,
        boolean dmReporterOnOpen,
        boolean dmReporterOnReply,
        boolean dmReporterOnStatus,
        String openAckMessage,
        boolean closedLockReplies,
        String defaultMode,
        String channelCategoryId
) {
    public static final String MODE_DM = "dm";
    public static final String MODE_CHANNEL = "channel";

    public static TicketsConfig blank() {
        return new TicketsConfig(
                "",
                true,
                true,
                true,
                "Thanks, your ticket has been opened. Staff will be in touch.",
                true,
                MODE_DM,
                "");
    }

    public String normalisedDefaultMode() {
        return MODE_CHANNEL.equalsIgnoreCase(defaultMode) ? MODE_CHANNEL : MODE_DM;
    }
}
