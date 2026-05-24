package io.warden.data.dao;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores the current state of guild invites + the operator-set human label
 * (Reddit, TikTok, ...). Joins are attributed to an invite by diffing the
 * {@code uses} counter against the previous snapshot when a member joins,
 * then stamping the matched code onto the member-join row.
 */
public final class InviteDao {

    private final Database db;

    public InviteDao(Database db) { this.db = db; }

    public record Invite(
            String code,
            String guildId,
            String channelId,
            String inviterId,
            String label,
            int uses,
            int maxUses,
            Long expiresAt,
            long createdAt,
            long updatedAt,
            Long deletedAt
    ) {
        public boolean active() { return deletedAt == null; }
    }

    public Optional<Invite> get(String code) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM invites WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<Invite> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM invites ORDER BY deleted_at IS NULL DESC, uses DESC, created_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Invite> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        }
    }

    /**
     * Insert-or-update an invite row from a live Discord snapshot. Preserves the
     * existing label since the operator owns that column; everything else is
     * authoritative from JDA.
     */
    public void upsertFromSnapshot(String code, String guildId, String channelId, String inviterId,
                                   int uses, int maxUses, Long expiresAt) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO invites (code, guild_id, channel_id, inviter_id, uses, max_uses, expires_at, "
                             + "created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                             + "ON CONFLICT(code) DO UPDATE SET "
                             + "  guild_id = excluded.guild_id, "
                             + "  channel_id = excluded.channel_id, "
                             + "  inviter_id = excluded.inviter_id, "
                             + "  uses = excluded.uses, "
                             + "  max_uses = excluded.max_uses, "
                             + "  expires_at = excluded.expires_at, "
                             + "  updated_at = excluded.updated_at, "
                             + "  deleted_at = NULL")) {
            ps.setString(1, code);
            ps.setString(2, guildId);
            ps.setString(3, channelId);
            ps.setString(4, inviterId);
            ps.setInt(5, uses);
            ps.setInt(6, maxUses);
            if (expiresAt == null) ps.setNull(7, Types.INTEGER); else ps.setLong(7, expiresAt);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
        }
    }

    public void setLabel(String code, String label) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE invites SET label = ?, updated_at = ? WHERE code = ?")) {
            if (label == null || label.isBlank()) ps.setNull(1, Types.VARCHAR);
            else ps.setString(1, label.trim());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, code);
            ps.executeUpdate();
        }
    }

    public void markDeleted(String code) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE invites SET deleted_at = ?, updated_at = ? WHERE code = ?")) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setString(3, code);
            ps.executeUpdate();
        }
    }

    /** Set invite_code on a previously-recorded discord_member_events join row. */
    public void attributeJoin(long memberEventId, String inviteCode) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE discord_member_events SET invite_code = ? WHERE id = ?")) {
            ps.setString(1, inviteCode);
            ps.setLong(2, memberEventId);
            ps.executeUpdate();
        }
    }

    /** Convenience for the listener: get the most recently-inserted member-event row id for this user. */
    public long latestJoinEventId(String discordId, long sinceMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM discord_member_events "
                             + "WHERE discord_id = ? AND kind = 'join' AND at >= ? "
                             + "ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, discordId);
            ps.setLong(2, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    /**
     * Joins per invite code in the given inclusive epoch-ms range. Returns a
     * row per code (only codes with at least one attributed join show up).
     */
    public List<JoinsRow> joinsByInvite(long fromMs, long toMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT e.invite_code AS code, i.label AS label, COUNT(*) AS n "
                             + "FROM discord_member_events e "
                             + "LEFT JOIN invites i ON i.code = e.invite_code "
                             + "WHERE e.kind = 'join' AND e.invite_code IS NOT NULL "
                             + "AND e.at >= ? AND e.at <= ? "
                             + "GROUP BY e.invite_code, i.label "
                             + "ORDER BY n DESC")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<JoinsRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new JoinsRow(rs.getString("code"), rs.getString("label"), rs.getLong("n")));
                }
                return out;
            }
        }
    }

    public record JoinsRow(String code, String label, long count) {}

    private static Invite mapRow(ResultSet rs) throws SQLException {
        long expiresRaw = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : expiresRaw;
        long deletedRaw = rs.getLong("deleted_at");
        Long deletedAt = rs.wasNull() ? null : deletedRaw;
        return new Invite(
                rs.getString("code"),
                rs.getString("guild_id"),
                rs.getString("channel_id"),
                rs.getString("inviter_id"),
                rs.getString("label"),
                rs.getInt("uses"),
                rs.getInt("max_uses"),
                expiresAt,
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                deletedAt);
    }
}
