package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class DiscordMemberEventDao {

    public static final String KIND_JOIN   = "join";
    public static final String KIND_LEAVE  = "leave";
    public static final String KIND_BAN    = "ban";
    public static final String KIND_UNBAN  = "unban";
    public static final String KIND_KICK   = "kick";

    private final Database db;

    public DiscordMemberEventDao(Database db) { this.db = db; }

    public void insert(String discordId, String kind, long atMs, String reason) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO discord_member_events (discord_id, kind, at, reason) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, discordId);
            ps.setString(2, kind);
            ps.setLong(3, atMs);
            ps.setString(4, reason);
            ps.executeUpdate();
        }
    }
}
