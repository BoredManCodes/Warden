package io.warden.tickets;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TicketCategoryDao {

    private final Database db;

    public TicketCategoryDao(Database db) { this.db = db; }

    public List<TicketCategory> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM ticket_categories ORDER BY sort_order ASC, id ASC");
             ResultSet rs = ps.executeQuery()) {
            List<TicketCategory> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public List<TicketCategory> listEnabled() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM ticket_categories WHERE enabled = 1 ORDER BY sort_order ASC, id ASC");
             ResultSet rs = ps.executeQuery()) {
            List<TicketCategory> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public Optional<TicketCategory> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM ticket_categories WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<TicketCategory> findBySlug(String slug) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM ticket_categories WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public long create(String slug, String name, String description, String emoji,
                       String buttonStyle, int sortOrder, boolean enabled,
                       String deliveryMode, String channelCategoryId) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ticket_categories (slug, name, description, emoji, button_style, " +
                             "sort_order, enabled, delivery_mode, channel_category_id, " +
                             "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.setString(2, name);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, emoji == null ? "" : emoji);
            ps.setString(5, buttonStyle == null ? "SECONDARY" : buttonStyle);
            ps.setInt(6, sortOrder);
            ps.setInt(7, enabled ? 1 : 0);
            ps.setString(8, deliveryMode == null ? TicketCategory.MODE_INHERIT : deliveryMode);
            ps.setString(9, channelCategoryId == null ? "" : channelCategoryId);
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void update(long id, String name, String description, String emoji,
                       String buttonStyle, int sortOrder, boolean enabled,
                       String deliveryMode, String channelCategoryId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ticket_categories SET name = ?, description = ?, emoji = ?, " +
                             "button_style = ?, sort_order = ?, enabled = ?, " +
                             "delivery_mode = ?, channel_category_id = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, description == null ? "" : description);
            ps.setString(3, emoji == null ? "" : emoji);
            ps.setString(4, buttonStyle == null ? "SECONDARY" : buttonStyle);
            ps.setInt(5, sortOrder);
            ps.setInt(6, enabled ? 1 : 0);
            ps.setString(7, deliveryMode == null ? TicketCategory.MODE_INHERIT : deliveryMode);
            ps.setString(8, channelCategoryId == null ? "" : channelCategoryId);
            ps.setLong(9, System.currentTimeMillis());
            ps.setLong(10, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ticket_categories WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static TicketCategory map(ResultSet rs) throws SQLException {
        return new TicketCategory(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("emoji"),
                rs.getString("button_style"),
                rs.getInt("sort_order"),
                rs.getInt("enabled") != 0,
                rs.getString("delivery_mode"),
                rs.getString("channel_category_id"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
