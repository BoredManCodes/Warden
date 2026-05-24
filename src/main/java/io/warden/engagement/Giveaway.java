package io.warden.engagement;

import java.util.List;

public record Giveaway(
        long id,
        String channelId,
        String messageId,
        String creatorId,
        String prize,
        String description,
        int winners,
        String requiredRole,
        long endsAt,
        Long drawnAt,
        Long cancelledAt,
        List<String> winnerIds,
        long createdAt
) {
    public Giveaway {
        winnerIds = winnerIds == null ? List.of() : List.copyOf(winnerIds);
    }
    public boolean open(long now) { return cancelledAt == null && drawnAt == null && endsAt > now; }
}
