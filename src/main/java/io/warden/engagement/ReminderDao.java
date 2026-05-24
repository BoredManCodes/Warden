package io.warden.engagement;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ReminderDao {

    public record Reminder(
            long id, String discordId, String channelId, String message,
            long firesAt, Long deliveredAt, Long cancelledAt, long createdAt
    ) {}

    private final Database db;

    public ReminderDao(Database db) { this.db = db; }

    public long create(String discordId, String channelId, String message, long firesAt) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO reminders (discord_id, channel_id, message, fires_at, created_at) " +
                             "VALUES (?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, discordId);
            ps.setString(2, channelId == null ? "" : channelId);
            ps.setString(3, message);
            ps.setLong(4, firesAt);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public List<Reminder> dueFor(long nowMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM reminders WHERE delivered_at IS NULL AND cancelled_at IS NULL AND fires_at <= ?")) {
            ps.setLong(1, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<Reminder> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<Reminder> listFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM reminders WHERE discord_id = ? AND delivered_at IS NULL AND cancelled_at IS NULL " +
                             "ORDER BY fires_at ASC")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Reminder> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<Reminder> listAllPending() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM reminders WHERE delivered_at IS NULL AND cancelled_at IS NULL " +
                             "ORDER BY fires_at ASC");
             ResultSet rs = ps.executeQuery()) {
            List<Reminder> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public void markDelivered(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE reminders SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void cancel(long id, String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE reminders SET cancelled_at = ? WHERE id = ? AND discord_id = ? AND cancelled_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.setString(3, discordId);
            ps.executeUpdate();
        }
    }

    private static Reminder map(ResultSet rs) throws SQLException {
        long del = rs.getLong("delivered_at");
        Long deliveredAt = rs.wasNull() ? null : del;
        long can = rs.getLong("cancelled_at");
        Long cancelledAt = rs.wasNull() ? null : can;
        return new Reminder(
                rs.getLong("id"),
                rs.getString("discord_id"),
                rs.getString("channel_id"),
                rs.getString("message"),
                rs.getLong("fires_at"),
                deliveredAt,
                cancelledAt,
                rs.getLong("created_at"));
    }
}
