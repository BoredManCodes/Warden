package io.warden.engagement;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GiveawayDao {

    private final Database db;

    public GiveawayDao(Database db) { this.db = db; }

    public long create(String channelId, String creatorId, String prize, String description,
                       int winners, String requiredRole, long endsAt) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO giveaways (channel_id, creator_id, prize, description, winners, " +
                             "required_role, ends_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, channelId);
            ps.setString(2, creatorId);
            ps.setString(3, prize);
            ps.setString(4, description == null ? "" : description);
            ps.setInt(5, Math.max(1, winners));
            ps.setString(6, requiredRole == null ? "" : requiredRole);
            ps.setLong(7, endsAt);
            ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void setMessageId(long id, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE giveaways SET message_id = ? WHERE id = ?")) {
            ps.setString(1, messageId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public Optional<Giveaway> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM giveaways WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Giveaway> dueForDraw(long nowMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM giveaways WHERE drawn_at IS NULL AND cancelled_at IS NULL AND ends_at <= ?")) {
            ps.setLong(1, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<Giveaway> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<Giveaway> listOpen() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM giveaways WHERE drawn_at IS NULL AND cancelled_at IS NULL ORDER BY ends_at ASC");
             ResultSet rs = ps.executeQuery()) {
            List<Giveaway> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public boolean enter(long giveawayId, String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR IGNORE INTO giveaway_entries (giveaway_id, discord_id, entered_at) VALUES (?, ?, ?)")) {
            ps.setLong(1, giveawayId);
            ps.setString(2, discordId);
            ps.setLong(3, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean leave(long giveawayId, String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM giveaway_entries WHERE giveaway_id = ? AND discord_id = ?")) {
            ps.setLong(1, giveawayId);
            ps.setString(2, discordId);
            return ps.executeUpdate() == 1;
        }
    }

    public List<String> entries(long giveawayId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT discord_id FROM giveaway_entries WHERE giveaway_id = ?")) {
            ps.setLong(1, giveawayId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        }
    }

    public int entryCount(long giveawayId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM giveaway_entries WHERE giveaway_id = ?")) {
            ps.setLong(1, giveawayId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void markDrawn(long id, List<String> winnerIds) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE giveaways SET drawn_at = ?, winners_json = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, Json.writeStringList(winnerIds));
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void cancel(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE giveaways SET cancelled_at = ? WHERE id = ? AND cancelled_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static Giveaway map(ResultSet rs) throws SQLException {
        long drawn = rs.getLong("drawn_at");
        Long drawnAt = rs.wasNull() ? null : drawn;
        long cancelled = rs.getLong("cancelled_at");
        Long cancelledAt = rs.wasNull() ? null : cancelled;
        return new Giveaway(
                rs.getLong("id"),
                rs.getString("channel_id"),
                rs.getString("message_id"),
                rs.getString("creator_id"),
                rs.getString("prize"),
                rs.getString("description"),
                rs.getInt("winners"),
                rs.getString("required_role"),
                rs.getLong("ends_at"),
                drawnAt,
                cancelledAt,
                Json.readStringList(rs.getString("winners_json")),
                rs.getLong("created_at"));
    }
}
