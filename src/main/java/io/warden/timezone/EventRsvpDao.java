package io.warden.timezone;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EventRsvpDao {

    private final Database db;

    public EventRsvpDao(Database db) { this.db = db; }

    public void upsert(long eventId, String discordId, String response) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO scheduled_event_rsvps (event_id, discord_id, rsvp, responded_at) "
                             + "VALUES (?, ?, ?, ?) "
                             + "ON CONFLICT(event_id, discord_id) DO UPDATE SET "
                             + "rsvp = excluded.rsvp, responded_at = excluded.responded_at")) {
            ps.setLong(1, eventId);
            ps.setString(2, discordId);
            ps.setString(3, response);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public Optional<EventRsvp> findFor(long eventId, String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, event_id, discord_id, rsvp, responded_at "
                             + "FROM scheduled_event_rsvps WHERE event_id = ? AND discord_id = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new EventRsvp(
                        rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5)));
            }
        }
    }

    public List<EventRsvp> listFor(long eventId) throws SQLException {
        List<EventRsvp> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, event_id, discord_id, rsvp, responded_at "
                             + "FROM scheduled_event_rsvps WHERE event_id = ? ORDER BY responded_at ASC")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EventRsvp(
                            rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)));
                }
            }
        }
        return out;
    }

    /** Returns a map of rsvp value to count for the given event. */
    public Map<String, Integer> countsFor(long eventId) throws SQLException {
        Map<String, Integer> out = new HashMap<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT rsvp, COUNT(*) FROM scheduled_event_rsvps WHERE event_id = ? GROUP BY rsvp")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
            }
        }
        return out;
    }

    public void deleteFor(long eventId, String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM scheduled_event_rsvps WHERE event_id = ? AND discord_id = ?")) {
            ps.setLong(1, eventId);
            ps.setString(2, discordId);
            ps.executeUpdate();
        }
    }
}
