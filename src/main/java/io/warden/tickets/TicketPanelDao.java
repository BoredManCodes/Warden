package io.warden.tickets;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TicketPanelDao {

    private final Database db;

    public TicketPanelDao(Database db) { this.db = db; }

    public List<TicketPanel> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM ticket_panels ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            List<TicketPanel> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public Optional<TicketPanel> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM ticket_panels WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public long create(String channelId, String title, String description, String colorHex) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ticket_panels (channel_id, title, description, color_hex, " +
                             "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, channelId);
            ps.setString(2, title == null ? "Open a ticket" : title);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, colorHex == null ? "#5865F2" : colorHex);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void update(long id, String channelId, String title, String description, String colorHex) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ticket_panels SET channel_id = ?, title = ?, description = ?, " +
                             "color_hex = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, channelId);
            ps.setString(2, title == null ? "Open a ticket" : title);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, colorHex == null ? "#5865F2" : colorHex);
            ps.setLong(5, System.currentTimeMillis());
            ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    public void setMessageId(long id, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ticket_panels SET message_id = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, messageId == null ? "" : messageId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ticket_panels WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static TicketPanel map(ResultSet rs) throws SQLException {
        return new TicketPanel(
                rs.getLong("id"),
                rs.getString("channel_id"),
                rs.getString("message_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("color_hex"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
