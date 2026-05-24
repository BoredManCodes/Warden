package io.warden.data.dao;

import io.warden.data.Database;
import io.warden.onboarding.OnboardingState;
import io.warden.onboarding.model.UserRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class UserDao {

    private final Database db;

    public UserDao(Database db) { this.db = db; }

    public Optional<UserRecord> findByDiscordId(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT discord_id, username, joined_at, state, web_session_id, updated_at " +
                             "FROM users WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public Optional<UserRecord> findByWebSession(String sessionId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT discord_id, username, joined_at, state, web_session_id, updated_at " +
                             "FROM users WHERE web_session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public void upsert(String discordId, String username) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (discord_id, username, joined_at, state, updated_at) " +
                             "VALUES (?, ?, ?, 'pending_link', ?) " +
                             "ON CONFLICT(discord_id) DO UPDATE SET username=excluded.username, updated_at=excluded.updated_at")) {
            ps.setString(1, discordId);
            ps.setString(2, username);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public void setState(String discordId, OnboardingState state) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET state = ?, updated_at = ? WHERE discord_id = ?")) {
            ps.setString(1, state.wireName());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, discordId);
            ps.executeUpdate();
        }
    }

    public void setWebSession(String discordId, String sessionId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE users SET web_session_id = ?, updated_at = ? WHERE discord_id = ?")) {
            ps.setString(1, sessionId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, discordId);
            ps.executeUpdate();
        }
    }

    public List<UserRecord> listAll(int limit) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT discord_id, username, joined_at, state, web_session_id, updated_at " +
                             "FROM users ORDER BY joined_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 5000)));
            try (ResultSet rs = ps.executeQuery()) {
                List<UserRecord> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        }
    }

    public int countAll() throws SQLException {
        try (Connection c = db.connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private UserRecord mapRow(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getString("discord_id"),
                rs.getString("username"),
                rs.getLong("joined_at"),
                OnboardingState.fromWire(rs.getString("state")),
                rs.getString("web_session_id"),
                rs.getLong("updated_at")
        );
    }
}
