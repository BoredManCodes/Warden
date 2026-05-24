package io.warden.levels;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class LevelUserDao {

    public record LevelUser(
            String discordId, long xp, int level, long messages, long lastGrantAt, long updatedAt
    ) {}

    private final Database db;

    public LevelUserDao(Database db) { this.db = db; }

    public Optional<LevelUser> find(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM level_users WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<LevelUser> top(int limit) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM level_users ORDER BY xp DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<LevelUser> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public int rank(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM level_users WHERE xp > (SELECT xp FROM level_users WHERE discord_id = ?)")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) + 1 : -1;
            }
        }
    }

    public void grant(String discordId, long xpDelta) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO level_users (discord_id, xp, level, messages, last_grant_at, updated_at) " +
                             "VALUES (?, ?, 0, 1, ?, ?) ON CONFLICT(discord_id) DO UPDATE SET " +
                             "xp = xp + excluded.xp, messages = messages + 1, " +
                             "last_grant_at = excluded.last_grant_at, updated_at = excluded.updated_at")) {
            ps.setString(1, discordId);
            ps.setLong(2, xpDelta);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public void setLevel(String discordId, int level) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE level_users SET level = ?, updated_at = ? WHERE discord_id = ?")) {
            ps.setInt(1, level);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, discordId);
            ps.executeUpdate();
        }
    }

    public void reset(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE level_users SET xp = 0, level = 0, updated_at = ? WHERE discord_id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, discordId);
            ps.executeUpdate();
        }
    }

    public void resetAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE level_users SET xp = 0, level = 0, updated_at = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public int totalUsers() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM level_users");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static LevelUser map(ResultSet rs) throws SQLException {
        return new LevelUser(
                rs.getString("discord_id"),
                rs.getLong("xp"),
                rs.getInt("level"),
                rs.getLong("messages"),
                rs.getLong("last_grant_at"),
                rs.getLong("updated_at"));
    }
}
