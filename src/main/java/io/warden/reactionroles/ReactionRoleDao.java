package io.warden.reactionroles;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReactionRoleDao {

    private final Database db;

    public ReactionRoleDao(Database db) { this.db = db; }

    public List<ReactionRoleGroup> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM reaction_role_groups ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            List<ReactionRoleGroup> out = new ArrayList<>();
            while (rs.next()) out.add(mapGroup(rs, c));
            return out;
        }
    }

    public Optional<ReactionRoleGroup> findById(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM reaction_role_groups WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapGroup(rs, c)) : Optional.empty();
            }
        }
    }

    public Optional<ReactionRoleGroup> findByMessage(String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM reaction_role_groups WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapGroup(rs, c)) : Optional.empty();
            }
        }
    }

    public long createGroup(String name, String channelId, String mode, String style,
                            String title, String description, String colorHex,
                            int maxSelections, String requiredRole) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO reaction_role_groups (name, channel_id, mode, style, title, description, " +
                             "color_hex, max_selections, required_role, created_at, updated_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, channelId);
            ps.setString(3, mode);
            ps.setString(4, style);
            ps.setString(5, title);
            ps.setString(6, description);
            ps.setString(7, colorHex);
            ps.setInt(8, maxSelections);
            ps.setString(9, requiredRole == null ? "" : requiredRole);
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void updateGroup(long id, String name, String mode, String title, String description,
                            String colorHex, int maxSelections, String requiredRole) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE reaction_role_groups SET name = ?, mode = ?, title = ?, description = ?, " +
                             "color_hex = ?, max_selections = ?, required_role = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, mode);
            ps.setString(3, title);
            ps.setString(4, description);
            ps.setString(5, colorHex);
            ps.setInt(6, maxSelections);
            ps.setString(7, requiredRole == null ? "" : requiredRole);
            ps.setLong(8, System.currentTimeMillis());
            ps.setLong(9, id);
            ps.executeUpdate();
        }
    }

    public void setMessageId(long groupId, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE reaction_role_groups SET message_id = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, messageId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, groupId);
            ps.executeUpdate();
        }
    }

    public void deleteGroup(long id) throws SQLException {
        try (Connection c = db.connection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM reaction_role_options WHERE group_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM reaction_role_groups WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    public long addOption(long groupId, String roleId, String label, String emoji,
                          String description, int orderIndex) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO reaction_role_options (group_id, role_id, label, emoji, description, order_index) " +
                             "VALUES (?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, groupId);
            ps.setString(2, roleId);
            ps.setString(3, label);
            ps.setString(4, emoji == null ? "" : emoji);
            ps.setString(5, description == null ? "" : description);
            ps.setInt(6, orderIndex);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void deleteOption(long optionId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM reaction_role_options WHERE id = ?")) {
            ps.setLong(1, optionId);
            ps.executeUpdate();
        }
    }

    public void deleteOptions(long groupId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM reaction_role_options WHERE group_id = ?")) {
            ps.setLong(1, groupId);
            ps.executeUpdate();
        }
    }

    private ReactionRoleGroup mapGroup(ResultSet rs, Connection c) throws SQLException {
        long id = rs.getLong("id");
        List<ReactionRoleOption> options = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, role_id, label, emoji, description, order_index FROM reaction_role_options " +
                        "WHERE group_id = ? ORDER BY order_index ASC, id ASC")) {
            ps.setLong(1, id);
            try (ResultSet ors = ps.executeQuery()) {
                while (ors.next()) {
                    options.add(new ReactionRoleOption(
                            ors.getLong(1), id, ors.getString(2), ors.getString(3),
                            ors.getString(4), ors.getString(5), ors.getInt(6)));
                }
            }
        }
        return new ReactionRoleGroup(
                id,
                rs.getString("name"),
                rs.getString("channel_id"),
                rs.getString("message_id"),
                rs.getString("mode"),
                rs.getString("style"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("color_hex"),
                rs.getInt("max_selections"),
                rs.getString("required_role"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                options);
    }
}
