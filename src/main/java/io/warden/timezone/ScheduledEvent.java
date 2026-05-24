package io.warden.timezone;

import java.util.List;

public record ScheduledEvent(
        long id,
        String title,
        String description,
        long startsAtUtc,
        int durationMinutes,
        String creatorId,
        String creatorName,
        List<String> targetRoles,
        String discordAnnounceChannelId,
        String discordAnnounceMessageId,
        long createdAt,
        Long cancelledAt
) {
    public ScheduledEvent {
        targetRoles = targetRoles == null ? List.of() : List.copyOf(targetRoles);
    }

    public boolean upcoming(long now) {
        return cancelledAt == null && startsAtUtc + durationMinutes * 60_000L > now;
    }

    public long endsAtUtc() {
        return startsAtUtc + durationMinutes * 60_000L;
    }
}
