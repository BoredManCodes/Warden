package io.warden.analytics;

import io.warden.data.Database;
import io.warden.data.dao.AnalyticsMetaDao;
import io.warden.data.dao.DailyMetricsDao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rolls raw event tables (discord_messages, discord_member_events, mc_sessions,
 * applications) into the daily_metrics summary table. Reads from the dashboard
 * are O(days_shown) instead of O(events_in_range) once these rollups are in.
 *
 * Runs on a dedicated single-thread scheduler:
 *   - Immediately on plugin enable if the last run was over 25h ago.
 *   - Then nightly at 03:00 server-local time.
 *
 * Each run recomputes the trailing {@link #LOOKBACK_DAYS} days using
 * INSERT...ON CONFLICT DO UPDATE so a missed run just gets caught up on the
 * next pass without any branching logic.
 */
public final class RollupJob {

    /** How many trailing days we recompute each pass. Generous enough to cover any restart gap. */
    private static final int LOOKBACK_DAYS = 7;

    /** A "fresh enough" last-run threshold: if we ran within this window, skip the on-enable run. */
    private static final long FRESH_MS = TimeUnit.HOURS.toMillis(25);

    /** Server time-of-day for the recurring nightly pass. */
    private static final LocalTime NIGHTLY_AT = LocalTime.of(3, 0);

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final Database database;
    private final DailyMetricsDao metrics;
    private final AnalyticsMetaDao meta;
    private final Logger log;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> nightly;

    public RollupJob(Database database, DailyMetricsDao metrics, AnalyticsMetaDao meta, Logger log) {
        this.database = database;
        this.metrics = metrics;
        this.meta = meta;
        this.log = log;
    }

    /** Boot the scheduler: catch-up run if needed, then schedule the nightly pass. */
    public synchronized void start() {
        if (scheduler != null) return;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "warden-rollup");
            t.setDaemon(true);
            return t;
        });

        long lastRun = readLastRunMillis();
        long now = System.currentTimeMillis();
        if (lastRun <= 0 || (now - lastRun) > FRESH_MS) {
            scheduler.submit(this::runOnce);
        } else {
            log.fine("rollup: last run was " + Duration.ofMillis(now - lastRun).toMinutes()
                    + " min ago; skipping catch-up");
        }

        long delaySec = secondsUntilNextNightly();
        this.nightly = scheduler.scheduleAtFixedRate(
                this::runOnce, delaySec, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
        log.info("rollup: next nightly pass in " + Duration.ofSeconds(delaySec).toHours()
                + "h " + ((delaySec / 60) % 60) + "m");
    }

    public synchronized void stop() {
        if (nightly != null) nightly.cancel(false);
        if (scheduler != null) scheduler.shutdownNow();
        nightly = null;
        scheduler = null;
    }

    /** Public for /warden rollup-now (admin command) and tests. */
    public void runOnce() {
        try {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            for (int back = LOOKBACK_DAYS - 1; back >= 0; back--) {
                LocalDate day = today.minusDays(back);
                rollupOneDay(day);
            }
            meta.put(AnalyticsMetaDao.KEY_LAST_ROLLUP_AT, Long.toString(System.currentTimeMillis()));
            log.info("rollup: recomputed last " + LOOKBACK_DAYS + " day(s) of metrics");
        } catch (Exception e) {
            log.log(Level.WARNING, "rollup: pass failed", e);
        }
    }

    private void rollupOneDay(LocalDate day) throws SQLException {
        String dayKey = DAY_FMT.format(day);
        long startMs = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

        metrics.put(dayKey, DailyMetricsDao.M_DISCORD_DAU, "",
                countDistinct("discord_messages", "discord_id", "at", startMs, endMs));
        metrics.put(dayKey, DailyMetricsDao.M_DISCORD_MSGS, "",
                count("discord_messages", "at", startMs, endMs, null, null));
        metrics.put(dayKey, DailyMetricsDao.M_DISCORD_JOINS, "",
                count("discord_member_events", "at", startMs, endMs, "kind", "join"));
        metrics.put(dayKey, DailyMetricsDao.M_DISCORD_LEAVES, "",
                countLeaves(startMs, endMs));

        metrics.put(dayKey, DailyMetricsDao.M_MC_DAU, "",
                countDistinct("mc_sessions", "mc_uuid", "started_at", startMs, endMs));
        metrics.put(dayKey, DailyMetricsDao.M_MC_SESSIONS, "",
                count("mc_sessions", "started_at", startMs, endMs, null, null));

        metrics.put(dayKey, DailyMetricsDao.M_APPS_SUBMITTED, "",
                count("applications", "submitted_at", startMs, endMs, null, null));
        metrics.put(dayKey, DailyMetricsDao.M_APPS_APPROVED, "",
                countAppsByDecision("approve", startMs, endMs));
        metrics.put(dayKey, DailyMetricsDao.M_APPS_DENIED, "",
                countAppsByDecision("deny", startMs, endMs));

        // Per-channel message volume. Wipe the previous slice for this day first
        // so a channel that fell silent doesn't keep its old non-zero number.
        metrics.clearDay(dayKey, DailyMetricsDao.M_DISCORD_MSGS_CHANNEL);
        for (var entry : countMessagesByChannel(startMs, endMs).entrySet()) {
            metrics.put(dayKey, DailyMetricsDao.M_DISCORD_MSGS_CHANNEL, entry.getKey(), entry.getValue());
        }
    }

    private java.util.Map<String, Long> countMessagesByChannel(long startMs, long endMs) throws SQLException {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT channel_id, COUNT(*) FROM discord_messages "
                             + "WHERE at >= ? AND at < ? GROUP BY channel_id")) {
            ps.setLong(1, startMs);
            ps.setLong(2, endMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String channelId = rs.getString(1);
                    if (channelId != null && !channelId.isEmpty()) out.put(channelId, rs.getLong(2));
                }
            }
        }
        return out;
    }

    private long countDistinct(String table, String column, String timeColumn, long startMs, long endMs) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT " + column + ") FROM " + table
                + " WHERE " + timeColumn + " >= ? AND " + timeColumn + " < ?";
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, startMs);
            ps.setLong(2, endMs);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long count(String table, String timeColumn, long startMs, long endMs,
                       String filterCol, String filterValue) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table
                + " WHERE " + timeColumn + " >= ? AND " + timeColumn + " < ?"
                + (filterCol == null ? "" : " AND " + filterCol + " = ?");
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, startMs);
            ps.setLong(2, endMs);
            if (filterCol != null) ps.setString(3, filterValue);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Discord "leaves" rolls up plain leaves + kicks + bans as a single attrition series. */
    private long countLeaves(long startMs, long endMs) throws SQLException {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM discord_member_events "
                             + "WHERE at >= ? AND at < ? AND kind IN ('leave', 'kick', 'ban')")) {
            ps.setLong(1, startMs);
            ps.setLong(2, endMs);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAppsByDecision(String decision, long startMs, long endMs) throws SQLException {
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM applications "
                             + "WHERE final_decision = ? AND decided_at >= ? AND decided_at < ?")) {
            ps.setString(1, decision);
            ps.setLong(2, startMs);
            ps.setLong(3, endMs);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long readLastRunMillis() {
        try {
            return meta.get(AnalyticsMetaDao.KEY_LAST_ROLLUP_AT)
                    .map(Long::parseLong).orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long secondsUntilNextNightly() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime nextRun = now.toLocalDate().atTime(NIGHTLY_AT);
        if (!nextRun.isAfter(now)) nextRun = nextRun.plusDays(1);
        return Duration.between(now, nextRun).getSeconds();
    }
}
