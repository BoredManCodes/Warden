package io.warden.timezone;

/**
 * A user's chosen IANA timezone. {@code source} explains where the value
 * came from for the audit log; one of {@code manual} (the user clicked the
 * map or used search), {@code geoip} (auto-suggested then accepted), or
 * {@code admin} (set by a staff member on the user's behalf).
 */
public record UserTimezone(
        String discordId,
        String tzId,
        String source,
        long updatedAt
) {}
