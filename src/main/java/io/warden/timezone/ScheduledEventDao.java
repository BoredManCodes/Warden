package io.warden.timezone;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ScheduledEventDao {

    private final Database db;

    public ScheduledEventDao(Database db) { this.db = db; }

    public long create(ScheduledEvent ev) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO scheduled_events ("
                             + "title, description, starts_at_utc, duration_minutes, "
                             + "creator_id, creator_name, target_roles_json, "
                             + "discord_announce_channel_id, discord_announce_message_id, "
                             + "created_at, cancelled_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ev.title());
            ps.setString(2, ev.description());
            ps.setLong(3, ev.startsAtUtc());
            ps.setInt(4, ev.durationMinutes());
            ps.setString(5, ev.creatorId());
            ps.setString(6, ev.creatorName());
            ps.setString(7, Json.writeStringList(ev.targetRoles()));
            ps.setString(8, ev.discordAnnounceChannelId());
            ps.setString(9, ev.discordAnnounceMessageId());
            ps.setLong(10, ev.createdAt());
            if (ev.cancelledAt() == null) ps.setNull(11, java.sql.Types.INTEGER);
            else ps.setLong(11, ev.cancelledAt());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public Optional<ScheduledEvent> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(SELECT_ALL + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(read(rs));
            }
        }
    }

    public List<ScheduledEvent> listUpcoming(long now) throws SQLException {
        List<ScheduledEvent> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(SELECT_ALL
                     + " WHERE cancelled_at IS NULL AND starts_at_utc + duration_minutes * 60000 > ? "
                     + "ORDER BY starts_at_utc ASC")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        }
        return out;
    }

    public List<ScheduledEvent> listAll(int limit) throws SQLException {
        List<ScheduledEvent> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(SELECT_ALL
                     + " ORDER BY starts_at_utc DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        }
        return out;
    }

    public void cancel(long id, long now) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE scheduled_events SET cancelled_at = ? WHERE id = ? AND cancelled_at IS NULL")) {
            ps.setLong(1, now);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM scheduled_events WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void updateAnnouncement(long id, String channelId, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE scheduled_events SET discord_announce_channel_id = ?, "
                             + "discord_announce_message_id = ? WHERE id = ?")) {
            ps.setString(1, channelId == null ? "" : channelId);
            ps.setString(2, messageId == null ? "" : messageId);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private static final String SELECT_ALL =
            "SELECT id, title, description, starts_at_utc, duration_minutes, "
                    + "creator_id, creator_name, target_roles_json, "
                    + "discord_announce_channel_id, discord_announce_message_id, "
                    + "created_at, cancelled_at FROM scheduled_events";

    private static ScheduledEvent read(ResultSet rs) throws SQLException {
        long cancelledRaw = rs.getLong(12);
        Long cancelled = rs.wasNull() ? null : cancelledRaw;
        return new ScheduledEvent(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getInt(5),
                rs.getString(6),
                rs.getString(7),
                Json.readStringList(rs.getString(8)),
                rs.getString(9),
                rs.getString(10),
                rs.getLong(11),
                cancelled
        );
    }
}
