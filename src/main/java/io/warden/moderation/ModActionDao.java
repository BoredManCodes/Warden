package io.warden.moderation;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class ModActionDao {

    public record ModAction(
            long id, String action, String targetDiscordId, String moderatorId,
            String reason, Integer durationSeconds, Long expiresAt, Long revokedAt, long createdAt
    ) {}

    private final Database db;

    public ModActionDao(Database db) { this.db = db; }

    public long record(String action, String targetDiscordId, String moderatorId,
                       String reason, Integer durationSeconds, Long expiresAt) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO mod_actions (action, target_discord_id, moderator_id, reason, " +
                             "duration_seconds, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, action);
            ps.setString(2, targetDiscordId);
            ps.setString(3, moderatorId);
            ps.setString(4, reason == null ? "" : reason);
            if (durationSeconds == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, durationSeconds);
            if (expiresAt == null) ps.setNull(6, Types.INTEGER); else ps.setLong(6, expiresAt);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public List<ModAction> dueForRevoke(long nowMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mod_actions WHERE revoked_at IS NULL AND expires_at IS NOT NULL " +
                             "AND expires_at <= ?")) {
            ps.setLong(1, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<ModAction> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public void markRevoked(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE mod_actions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public List<ModAction> listRecent(int limit) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mod_actions ORDER BY created_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<ModAction> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<ModAction> listFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM mod_actions WHERE target_discord_id = ? ORDER BY created_at DESC")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ModAction> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    private static ModAction map(ResultSet rs) throws SQLException {
        long dur = rs.getLong("duration_seconds");
        Integer durationSeconds = rs.wasNull() ? null : (int) dur;
        long exp = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : exp;
        long rev = rs.getLong("revoked_at");
        Long revokedAt = rs.wasNull() ? null : rev;
        return new ModAction(
                rs.getLong("id"),
                rs.getString("action"),
                rs.getString("target_discord_id"),
                rs.getString("moderator_id"),
                rs.getString("reason"),
                durationSeconds,
                expiresAt,
                revokedAt,
                rs.getLong("created_at"));
    }
}
