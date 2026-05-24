package io.warden.moderation;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class WarningDao {

    public record Warning(
            long id, String discordId, String moderatorId,
            String reason, int severity, long createdAt, Long clearedAt
    ) {}

    private final Database db;

    public WarningDao(Database db) { this.db = db; }

    public long create(String discordId, String moderatorId, String reason, int severity) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO warnings (discord_id, moderator_id, reason, severity, created_at) " +
                             "VALUES (?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, discordId);
            ps.setString(2, moderatorId);
            ps.setString(3, reason == null ? "" : reason);
            ps.setInt(4, severity);
            ps.setLong(5, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
                return -1;
            }
        }
    }

    public int activeCount(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM warnings WHERE discord_id = ? AND cleared_at IS NULL")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Warning> listFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM warnings WHERE discord_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Warning> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<Warning> listRecent(int limit) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM warnings ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<Warning> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public void clear(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE warnings SET cleared_at = ? WHERE id = ? AND cleared_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void clearAll(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE warnings SET cleared_at = ? WHERE discord_id = ? AND cleared_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, discordId);
            ps.executeUpdate();
        }
    }

    private static Warning map(ResultSet rs) throws SQLException {
        long cleared = rs.getLong("cleared_at");
        Long clearedAt = rs.wasNull() ? null : cleared;
        return new Warning(
                rs.getLong("id"),
                rs.getString("discord_id"),
                rs.getString("moderator_id"),
                rs.getString("reason"),
                rs.getInt("severity"),
                rs.getLong("created_at"),
                clearedAt);
    }
}
