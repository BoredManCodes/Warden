package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read + upsert for the daily_metrics rollup table. One row per
 * (day, metric, dimension) tuple; the rollup job writes here and the dashboard
 * reads from here so chart queries are O(days_shown).
 *
 * Common metric keys live in this class for discoverability:
 *   - {@link #M_DISCORD_DAU}      distinct discord users who sent a message that day
 *   - {@link #M_DISCORD_MSGS}     total Discord messages that day
 *   - {@link #M_DISCORD_JOINS}    discord member join events that day
 *   - {@link #M_DISCORD_LEAVES}   discord member leave events that day
 *   - {@link #M_MC_DAU}           distinct mc UUIDs with at least one session that day
 *   - {@link #M_MC_SESSIONS}      session count that day
 *   - {@link #M_APPS_SUBMITTED}   applications submitted that day
 *   - {@link #M_APPS_APPROVED}    final_decision='approve' decided that day
 *   - {@link #M_APPS_DENIED}      final_decision='deny' decided that day
 */
public final class DailyMetricsDao {

    public static final String M_DISCORD_DAU           = "discord_dau";
    public static final String M_DISCORD_MSGS          = "discord_msgs";
    /** Per-channel daily message counts. dimension = channel_id. */
    public static final String M_DISCORD_MSGS_CHANNEL  = "discord_msgs_channel";
    public static final String M_DISCORD_JOINS         = "discord_joins";
    public static final String M_DISCORD_LEAVES        = "discord_leaves";
    public static final String M_MC_DAU                = "mc_dau";
    public static final String M_MC_SESSIONS           = "mc_sessions";
    public static final String M_APPS_SUBMITTED        = "apps_submitted";
    public static final String M_APPS_APPROVED         = "apps_approved";
    public static final String M_APPS_DENIED           = "apps_denied";

    /** Cohort size (count) per ISO week. day = "YYYY-Www", dimension = "discord" | "mc". */
    public static final String M_RETENTION_SIZE        = "retention_size";
    /** Retention rate * 1000 (integer). day = "YYYY-Www", dimension = source. */
    public static final String M_RETENTION_D1          = "retention_d1";
    public static final String M_RETENTION_D7          = "retention_d7";
    public static final String M_RETENTION_D30         = "retention_d30";

    public static final String DIM_DISCORD             = "discord";
    public static final String DIM_MC                  = "mc";

    private final Database db;

    public DailyMetricsDao(Database db) { this.db = db; }

    /** Upsert a single metric value. dimension may be empty but never null. */
    public void put(String day, String metric, String dimension, long value) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO daily_metrics (day, metric, dimension, value) VALUES (?, ?, ?, ?) "
                             + "ON CONFLICT(day, metric, dimension) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, day);
            ps.setString(2, metric);
            ps.setString(3, dimension == null ? "" : dimension);
            ps.setLong(4, value);
            ps.executeUpdate();
        }
    }

    /**
     * Range read for a single (metric, dimension="") series. Returns rows in
     * ascending day order. Callers gap-fill zeros for missing days.
     */
    public List<Point> rangeUnscoped(String metric, String fromDayInclusive, String toDayInclusive) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT day, value FROM daily_metrics "
                             + "WHERE metric = ? AND dimension = '' AND day >= ? AND day <= ? "
                             + "ORDER BY day ASC")) {
            ps.setString(1, metric);
            ps.setString(2, fromDayInclusive);
            ps.setString(3, toDayInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                List<Point> out = new ArrayList<>();
                while (rs.next()) out.add(new Point(rs.getString(1), rs.getLong(2)));
                return out;
            }
        }
    }

    /**
     * Sum of a single-dimension metric over a date range. Used for headline
     * stat cards (eg "messages in the last 30 days").
     */
    public long sumUnscoped(String metric, String fromDayInclusive, String toDayInclusive) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(value), 0) FROM daily_metrics "
                             + "WHERE metric = ? AND dimension = '' AND day >= ? AND day <= ?")) {
            ps.setString(1, metric);
            ps.setString(2, fromDayInclusive);
            ps.setString(3, toDayInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Read every (day, dimension, value) row for the given metric over the
     * inclusive day range. Used by stacked charts (eg per-channel messages).
     */
    public List<DimPoint> rangeDimensioned(String metric, String fromDayInclusive, String toDayInclusive)
            throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT day, dimension, value FROM daily_metrics "
                             + "WHERE metric = ? AND day >= ? AND day <= ? "
                             + "ORDER BY day ASC")) {
            ps.setString(1, metric);
            ps.setString(2, fromDayInclusive);
            ps.setString(3, toDayInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                List<DimPoint> out = new ArrayList<>();
                while (rs.next()) out.add(new DimPoint(rs.getString(1), rs.getString(2), rs.getLong(3)));
                return out;
            }
        }
    }

    /**
     * Drop all rows for a (day, metric). Useful when re-rolling a dimensioned
     * metric so old dimensions don't linger as zero-valued ghosts (eg a channel
     * that became silent).
     */
    public int clearDay(String day, String metric) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM daily_metrics WHERE day = ? AND metric = ?")) {
            ps.setString(1, day);
            ps.setString(2, metric);
            return ps.executeUpdate();
        }
    }

    public record Point(String day, long value) {}

    public record DimPoint(String day, String dimension, long value) {}
}
