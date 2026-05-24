package io.warden.timezone;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class UserTimezoneDao {

    private final Database db;

    public UserTimezoneDao(Database db) { this.db = db; }

    public Optional<UserTimezone> find(String discordId) throws SQLException {
        if (discordId == null || discordId.isBlank()) return Optional.empty();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT discord_id, tz_id, source, updated_at FROM user_timezones WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new UserTimezone(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
            }
        }
    }

    public void save(String discordId, String tzId, String source) throws SQLException {
        if (discordId == null || discordId.isBlank() || tzId == null || tzId.isBlank()) return;
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO user_timezones (discord_id, tz_id, source, updated_at) VALUES (?, ?, ?, ?) "
                             + "ON CONFLICT(discord_id) DO UPDATE SET tz_id = excluded.tz_id, "
                             + "source = excluded.source, updated_at = excluded.updated_at")) {
            ps.setString(1, discordId);
            ps.setString(2, tzId);
            ps.setString(3, source == null ? "manual" : source);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public void delete(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM user_timezones WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            ps.executeUpdate();
        }
    }

    public Map<String, String> loadMany(Iterable<String> discordIds) throws SQLException {
        Map<String, String> out = new HashMap<>();
        StringBuilder placeholders = new StringBuilder();
        int count = 0;
        for (String id : discordIds) {
            if (id == null || id.isBlank()) continue;
            if (count > 0) placeholders.append(',');
            placeholders.append('?');
            count++;
        }
        if (count == 0) return out;
        String sql = "SELECT discord_id, tz_id FROM user_timezones WHERE discord_id IN (" + placeholders + ")";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (String id : discordIds) {
                if (id == null || id.isBlank()) continue;
                ps.setString(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }
}
