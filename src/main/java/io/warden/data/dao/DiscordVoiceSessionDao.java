package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * One row per continuous stretch in one voice channel. A channel switch
 * closes the previous open row (if any) and opens a new one.
 */
public final class DiscordVoiceSessionDao {

    private final Database db;

    public DiscordVoiceSessionDao(Database db) { this.db = db; }

    public void open(String discordId, String channelId, String guildId, long startedAt) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO discord_voice_sessions (discord_id, channel_id, guild_id, started_at) "
                             + "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, discordId);
            ps.setString(2, channelId);
            ps.setString(3, guildId);
            ps.setLong(4, startedAt);
            ps.executeUpdate();
        }
    }

    /** Close every still-open row for this user. Returns the count closed. */
    public int closeOpenFor(String discordId, long endedAt) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE discord_voice_sessions SET ended_at = ? "
                             + "WHERE discord_id = ? AND ended_at IS NULL")) {
            ps.setLong(1, endedAt);
            ps.setString(2, discordId);
            return ps.executeUpdate();
        }
    }

    /** Startup janitor: close every open row, capping at startedAt + maxHours. */
    public int closeAllOpen(long endedAt, long maxSessionMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE discord_voice_sessions "
                             + "SET ended_at = MIN(?, started_at + ?) "
                             + "WHERE ended_at IS NULL")) {
            ps.setLong(1, endedAt);
            ps.setLong(2, maxSessionMs);
            return ps.executeUpdate();
        }
    }
}
