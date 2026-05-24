package io.warden.timezone;

/**
 * One row per (event, user) RSVP. {@code response} is one of {@code going},
 * {@code maybe}, or {@code no}. Users overwrite their previous response by
 * posting again; staff see the aggregated counts on /dash/scheduler.
 */
public record EventRsvp(
        long id,
        long eventId,
        String discordId,
        String response,
        long respondedAt
) {
    public static final String GOING = "going";
    public static final String MAYBE = "maybe";
    public static final String NO    = "no";

    public static boolean isValid(String s) {
        return GOING.equals(s) || MAYBE.equals(s) || NO.equals(s);
    }
}
