package io.warden.timezone;

import io.warden.analytics.GeoIpService;
import io.warden.audit.AuditService;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin facade that bundles the timezone DAOs, GeoIP guess logic, and a few
 * conveniences used by both /tz and the scheduler page. Validation is done
 * here so handlers stay focused on rendering.
 */
public final class TimezoneService {

    private final UserTimezoneDao userDao;
    private final TimezoneConfigDao configDao;
    private final ScheduledEventDao eventDao;
    private final EventRsvpDao rsvpDao;
    private final GeoIpService geoip;
    private final AuditService audit;
    private final Logger log;

    public TimezoneService(
            UserTimezoneDao userDao,
            TimezoneConfigDao configDao,
            ScheduledEventDao eventDao,
            EventRsvpDao rsvpDao,
            GeoIpService geoip,
            AuditService audit,
            Logger log) {
        this.userDao = userDao;
        this.configDao = configDao;
        this.eventDao = eventDao;
        this.rsvpDao = rsvpDao;
        this.geoip = geoip;
        this.audit = audit;
        this.log = log;
    }

    public UserTimezoneDao users() { return userDao; }
    public TimezoneConfigDao configs() { return configDao; }
    public ScheduledEventDao events() { return eventDao; }
    public EventRsvpDao rsvps() { return rsvpDao; }

    public TimezoneConfig config() { return configDao.get(); }

    public Optional<UserTimezone> findUser(String discordId) {
        try {
            return userDao.find(discordId);
        } catch (Exception e) {
            log.log(Level.WARNING, "tz: lookup failed for " + discordId, e);
            return Optional.empty();
        }
    }

    /**
     * Suggest a starting timezone for the picker, in order of preference:
     *   1. Already-saved value for this user, if any
     *   2. GeoIP country to primary zone, when geoip is on and gives a hit
     *   3. UTC, as a neutral fallback
     */
    public String suggest(String discordId, String requestIp) {
        Optional<UserTimezone> existing = findUser(discordId);
        if (existing.isPresent() && isValid(existing.get().tzId())) return existing.get().tzId();

        TimezoneConfig cfg = config();
        if (cfg.geoipEnabled() && requestIp != null && !requestIp.isBlank() && geoip != null) {
            Optional<String> iso = geoip.lookupCountryIso(requestIp);
            if (iso.isPresent()) {
                Optional<String> primary = CountryTimezone.primaryFor(iso.get());
                if (primary.isPresent() && isValid(primary.get())) return primary.get();
            }
        }
        return "UTC";
    }

    public boolean isValid(String tzId) {
        if (tzId == null || tzId.isBlank()) return false;
        try {
            ZoneId.of(tzId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void saveUser(String discordId, String tzId, String source, String actor) {
        if (discordId == null || discordId.isBlank()) return;
        if (!isValid(tzId)) return;
        try {
            userDao.save(discordId, tzId, source);
            audit.write(actor == null ? "web" : actor, "timezone_set", discordId,
                    Map.of("tz", tzId, "source", source == null ? "manual" : source));
        } catch (Exception e) {
            log.log(Level.WARNING, "tz: save failed for " + discordId, e);
        }
    }

    public void clearUser(String discordId, String actor) {
        if (discordId == null || discordId.isBlank()) return;
        try {
            userDao.delete(discordId);
            audit.write(actor == null ? "web" : actor, "timezone_clear", discordId, Map.of());
        } catch (Exception e) {
            log.log(Level.WARNING, "tz: delete failed for " + discordId, e);
        }
    }
}
