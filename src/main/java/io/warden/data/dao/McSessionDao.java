package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class McSessionDao {

    private final Database db;

    public McSessionDao(Database db) { this.db = db; }

    public void open(String uuid, String name, long startedAt, String ipHash, String country, String clientBrand)
            throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mc_sessions (mc_uuid, mc_name, started_at, ip_hash, country, client_brand) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, startedAt);
            ps.setString(4, ipHash);
            ps.setString(5, country);
            ps.setString(6, clientBrand);
            ps.executeUpdate();
        }
    }

    /** Close every still-open session for this UUID. Returns the count closed. */
    public int closeOpenFor(String uuid, long endedAt) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE mc_sessions SET ended_at = ? WHERE mc_uuid = ? AND ended_at IS NULL")) {
            ps.setLong(1, endedAt);
            ps.setString(2, uuid);
            return ps.executeUpdate();
        }
    }

    /** Startup janitor: close every open row, capping each at started_at + maxHours. */
    public int closeAllOpen(long endedAt, long maxSessionMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE mc_sessions "
                             + "SET ended_at = MIN(?, started_at + ?) "
                             + "WHERE ended_at IS NULL")) {
            ps.setLong(1, endedAt);
            ps.setLong(2, maxSessionMs);
            return ps.executeUpdate();
        }
    }

    /**
     * Country distribution over a time window. Returns one row per ISO country
     * code with distinct-UUID and session counts. Sessions without a country
     * tag (GeoIP disabled, lookup miss, or pre-M6 data) are returned under the
     * synthetic "??" code so the geo page can surface the "unknown" share.
     */
    public List<CountryCount> countryCountsBetween(long startMs, long endMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(NULLIF(country, ''), '??') AS cc, "
                             + "COUNT(DISTINCT mc_uuid) AS players, "
                             + "COUNT(*) AS sessions "
                             + "FROM mc_sessions "
                             + "WHERE started_at >= ? AND started_at < ? "
                             + "GROUP BY cc "
                             + "ORDER BY players DESC, sessions DESC")) {
            ps.setLong(1, startMs);
            ps.setLong(2, endMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<CountryCount> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new CountryCount(rs.getString(1), rs.getInt(2), rs.getLong(3)));
                }
                return out;
            }
        }
    }

    public record CountryCount(String country, int players, long sessions) {}
}
