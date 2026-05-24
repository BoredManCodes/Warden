package io.warden.tickets;

public record TicketCategory(
        long id,
        String slug,
        String name,
        String description,
        String emoji,
        String buttonStyle,
        int sortOrder,
        boolean enabled,
        String deliveryMode,
        String channelCategoryId,
        long createdAt,
        long updatedAt
) {
    public static final String MODE_INHERIT = "inherit";
    public static final String MODE_DM = "dm";
    public static final String MODE_CHANNEL = "channel";

    public String normalisedDeliveryMode() {
        if (deliveryMode == null) return MODE_INHERIT;
        return switch (deliveryMode.toLowerCase(java.util.Locale.ROOT)) {
            case MODE_DM -> MODE_DM;
            case MODE_CHANNEL -> MODE_CHANNEL;
            default -> MODE_INHERIT;
        };
    }
}
