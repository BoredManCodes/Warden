package io.warden.analytics;

import io.warden.data.Database;
import io.warden.data.dao.AnalyticsMetaDao;
import io.warden.data.dao.DailyMetricsDao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computes weekly retention cohorts: of the users whose first activity
 * landed in ISO week W, what fraction were still active on day 1, 7, and 30?
 *
 * Two sources are bucketed independently:
 *   - "discord" - first message in {@code discord_messages}, retention = any
 *     subsequent message in the (N, N+1) day window.
 *   - "mc" - first session in {@code mc_sessions}, retention = any subsequent
 *     session started in the (N, N+1) day window.
 *
 * Results are persisted to {@code daily_metrics} with synthetic metric keys
 * (see {@link DailyMetricsDao#M_RETENTION_SIZE} etc.) and {@code day = "YYYY-Www"}.
 * That keeps reads O(weeks_shown) without scanning raw events on every page load.
 *
 * Runs on a single-thread scheduler at Sunday 04:00 server-local time, plus
 * once on plugin enable if the last run was {@code > FRESH_MS}.
 */
public final class CohortJob {

    /** How many ISO weeks back we recompute each pass. Older cohorts are stable; no need to revisit. */
    private static final int LOOKBACK_WEEKS = 26;

    /** "Fresh enough" threshold for the on-enable catch-up. */
    private static final long FRESH_MS = TimeUnit.DAYS.toMillis(8);

    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
    private static final LocalTime WEEKLY_AT = LocalTime.of(4, 0);

    private final Database database;
    private final DailyMetricsDao metrics;
    private final AnalyticsMetaDao meta;
    private final Logger log;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> recurring;

    public CohortJob(Database database, DailyMetricsDao metrics, AnalyticsMetaDao meta, Logger log) {
        this.database = database;
        this.metrics = metrics;
        this.meta = meta;
        this.log = log;
    }

    public synchronized void start() {
        if (scheduler != null) return;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "warden-cohorts");
            t.setDaemon(true);
            return t;
        });

        long lastRun = readLastRunMillis();
        long now = System.currentTimeMillis();
        if (lastRun <= 0 || (now - lastRun) > FRESH_MS) {
            scheduler.submit(this::runOnce);
        }

        long delaySec = secondsUntilNextWeekly();
        this.recurring = scheduler.scheduleAtFixedRate(
                this::runOnce, delaySec, TimeUnit.DAYS.toSeconds(7), TimeUnit.SECONDS);
        log.info("cohorts: next weekly pass in " + Duration.ofSeconds(delaySec).toDays()
                + "d " + (Duration.ofSeconds(delaySec).toHours() % 24) + "h");
    }

    public synchronized void stop() {
        if (recurring != null) recurring.cancel(false);
        if (scheduler != null) scheduler.shutdownNow();
        recurring = null;
        scheduler = null;
    }

    /** Public for tests + admin trigger. */
    public void runOnce() {
        try {
            long now = System.currentTimeMillis();
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            // Window starts on the Monday LOOKBACK_WEEKS weeks ago and ends today.
            LocalDate fromMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .minusWeeks(LOOKBACK_WEEKS - 1L);
            long fromMs = fromMonday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

            int discord = computeAndStore(
                    "discord_messages", "discord_id", "at",
                    DailyMetricsDao.DIM_DISCORD, fromMs, now);
            int mc = computeAndStore(
                    "mc_sessions", "mc_uuid", "started_at",
                    DailyMetricsDao.DIM_MC, fromMs, now);

            meta.put(AnalyticsMetaDao.KEY_LAST_COHORT_AT, Long.toString(now));
            log.info("cohorts: recomputed " + discord + " discord + " + mc
                    + " mc cohort week(s) over last " + LOOKBACK_WEEKS + " weeks");
        } catch (Exception e) {
            log.log(Level.WARNING, "cohorts: pass failed", e);
        }
    }

    /**
     * Pull every (user, first_at, d1, d7, d30) row whose first_at falls in
     * [fromMs, nowMs), bucket by ISO week, and write the size + retention rates
     * into daily_metrics.
     *
     * @return number of distinct cohort weeks written
     */
    private int computeAndStore(String table, String userCol, String timeCol,
                                String sourceDim, long fromMs, long nowMs) throws SQLException {
        // Per-cohort tallies: size, retained-on-Dn count (or null when no users in cohort
        // have had a chance to be measured at Dn yet - we still record size).
        Map<String, long[]> tallies = new HashMap<>();
        // index 0 = size, 1 = D1 eligible, 2 = D1 retained, 3 = D7 eligible, 4 = D7 retained,
        // index 5 = D30 eligible, 6 = D30 retained.

        String sql = "WITH fs AS ("
                + "  SELECT " + userCol + " AS uid, MIN(" + timeCol + ") AS first_at "
                + "  FROM " + table
                + "  GROUP BY " + userCol
                + ") "
                + "SELECT "
                + "  fs.uid, fs.first_at, "
                + "  EXISTS (SELECT 1 FROM " + table + " t WHERE t." + userCol + " = fs.uid "
                + "          AND t." + timeCol + " >= fs.first_at + ? AND t." + timeCol + " < fs.first_at + ?) AS d1, "
                + "  EXISTS (SELECT 1 FROM " + table + " t WHERE t." + userCol + " = fs.uid "
                + "          AND t." + timeCol + " >= fs.first_at + ? AND t." + timeCol + " < fs.first_at + ?) AS d7, "
                + "  EXISTS (SELECT 1 FROM " + table + " t WHERE t." + userCol + " = fs.uid "
                + "          AND t." + timeCol + " >= fs.first_at + ? AND t." + timeCol + " < fs.first_at + ?) AS d30 "
                + "FROM fs "
                + "WHERE fs.first_at >= ? AND fs.first_at < ?";

        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int p = 0;
            ps.setLong(++p, 1 * DAY_MS); ps.setLong(++p, 2 * DAY_MS);
            ps.setLong(++p, 7 * DAY_MS); ps.setLong(++p, 8 * DAY_MS);
            ps.setLong(++p, 30 * DAY_MS); ps.setLong(++p, 31 * DAY_MS);
            ps.setLong(++p, fromMs);
            ps.setLong(++p, nowMs);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long firstAt = rs.getLong("first_at");
                    String cohort = isoWeekKey(firstAt);
                    long[] tally = tallies.computeIfAbsent(cohort, k -> new long[7]);
                    tally[0]++; // size

                    long ageMs = nowMs - firstAt;
                    if (ageMs >= 2 * DAY_MS) {
                        tally[1]++;
                        if (rs.getInt("d1") == 1) tally[2]++;
                    }
                    if (ageMs >= 8 * DAY_MS) {
                        tally[3]++;
                        if (rs.getInt("d7") == 1) tally[4]++;
                    }
                    if (ageMs >= 31 * DAY_MS) {
                        tally[5]++;
                        if (rs.getInt("d30") == 1) tally[6]++;
                    }
                }
            }
        }

        // Write rates × 1000. Rate is undefined when the eligible count is 0;
        // we omit those rows so the API can distinguish "no data yet" from "0%".
        for (var entry : tallies.entrySet()) {
            String cohort = entry.getKey();
            long[] t = entry.getValue();
            metrics.put(cohort, DailyMetricsDao.M_RETENTION_SIZE, sourceDim, t[0]);
            writeRate(cohort, DailyMetricsDao.M_RETENTION_D1,  sourceDim, t[2], t[1]);
            writeRate(cohort, DailyMetricsDao.M_RETENTION_D7,  sourceDim, t[4], t[3]);
            writeRate(cohort, DailyMetricsDao.M_RETENTION_D30, sourceDim, t[6], t[5]);
        }
        return tallies.size();
    }

    private void writeRate(String cohort, String metric, String dim, long retained, long eligible) throws SQLException {
        if (eligible <= 0) return;
        long rateX1000 = Math.round(1000.0 * retained / eligible);
        metrics.put(cohort, metric, dim, rateX1000);
    }

    private static String isoWeekKey(long epochMs) {
        LocalDate d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate();
        WeekFields iso = WeekFields.ISO;
        int week = d.get(iso.weekOfWeekBasedYear());
        int year = d.get(iso.weekBasedYear());
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    private long readLastRunMillis() {
        try {
            return meta.get(AnalyticsMetaDao.KEY_LAST_COHORT_AT)
                    .map(Long::parseLong).orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long secondsUntilNextWeekly() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime nextRun = now.toLocalDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .atTime(WEEKLY_AT);
        if (!nextRun.isAfter(now)) nextRun = nextRun.plusWeeks(1);
        return Duration.between(now, nextRun).getSeconds();
    }
}
