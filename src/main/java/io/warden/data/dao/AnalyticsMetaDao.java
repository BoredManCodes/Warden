package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Small KV store on the analytics_meta table. */
public final class AnalyticsMetaDao {

    public static final String KEY_IP_SALT = "ip_salt";
    public static final String KEY_LAST_ROLLUP_AT = "last_rollup_at";
    public static final String KEY_LAST_COHORT_AT = "last_cohort_at";

    private final Database db;

    public AnalyticsMetaDao(Database db) { this.db = db; }

    public Optional<String> get(String key) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM analytics_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rs.getString(1));
            }
        }
    }

    public void put(String key, String value) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO analytics_meta (key, value) VALUES (?, ?) "
                             + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
