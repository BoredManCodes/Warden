package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class DiscordMessageDao {

    private final Database db;

    public DiscordMessageDao(Database db) { this.db = db; }

    public void insert(String discordId, String channelId, String guildId, long atMs,
                       int length, boolean hasAttachment, boolean isReply) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO discord_messages (discord_id, channel_id, guild_id, at, length, has_attachment, is_reply) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, discordId);
            ps.setString(2, channelId);
            ps.setString(3, guildId);
            ps.setLong(4, atMs);
            ps.setInt(5, length);
            ps.setInt(6, hasAttachment ? 1 : 0);
            ps.setInt(7, isReply ? 1 : 0);
            ps.executeUpdate();
        }
    }
}
