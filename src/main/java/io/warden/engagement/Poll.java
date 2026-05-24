package io.warden.engagement;

import java.util.List;

public record Poll(
        long id,
        String channelId,
        String messageId,
        String creatorId,
        String question,
        List<String> options,
        boolean anonymous,
        boolean multiChoice,
        Long endsAt,
        Long closedAt,
        long createdAt
) {
    public Poll {
        options = options == null ? List.of() : List.copyOf(options);
    }
    public boolean open(long now) {
        return closedAt == null && (endsAt == null || endsAt > now);
    }
}
