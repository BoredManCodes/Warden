package io.warden.timezone;

/**
 * Admin-controlled toggles for the timezone feature. Stored in a singleton
 * row keyed at id=1, separate from the main settings blob so we don't have
 * to widen Settings for every new sub-feature.
 *
 * - {@code onboardingRequired}: when true, any logged-in user without a
 *   recorded timezone is bounced to /tz before reaching the rest of the
 *   dashboard.
 * - {@code geoipEnabled}: when true, /tz pre-selects a guess derived from
 *   the visitor's IP via the existing analytics GeoIP lookup. The guess is
 *   only a hint; the user still has to confirm.
 * - {@code schedulerEnabled}: hides the event scheduler entirely when off.
 */
public record TimezoneConfig(
        boolean onboardingRequired,
        boolean geoipEnabled,
        boolean schedulerEnabled
) {
    public static TimezoneConfig defaults() {
        return new TimezoneConfig(false, false, true);
    }
}
