package io.warden.tickets;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TicketDao {

    public record StatusCount(TicketStatus status, int count) {}

    private final Database db;

    public TicketDao(Database db) { this.db = db; }

    public long create(Long categoryId, String discordId, String discordUsername,
                       String subject, String body, String mode) throws SQLException {
        long now = System.currentTimeMillis();
        String resolvedMode = TicketsConfig.MODE_CHANNEL.equalsIgnoreCase(mode)
                ? TicketsConfig.MODE_CHANNEL : TicketsConfig.MODE_DM;
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tickets (category_id, discord_id, discord_username, subject, body, " +
                             "status, mode, last_activity_at, created_at) " +
                             "VALUES (?, ?, ?, ?, ?, 'open', ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            if (categoryId == null) ps.setNull(1, Types.INTEGER); else ps.setLong(1, categoryId);
            ps.setString(2, discordId);
            ps.setString(3, discordUsername == null ? "" : discordUsername);
            ps.setString(4, subject);
            ps.setString(5, body);
            ps.setString(6, resolvedMode);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public Optional<Ticket> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM tickets WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Most recent non-terminal ticket for a Discord user, or empty. */
    public Optional<Ticket> findActiveForUser(String discordId) throws SQLException {
        if (discordId == null || discordId.isBlank()) return Optional.empty();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM tickets WHERE discord_id = ? AND status IN ('open','in_progress') " +
                             "ORDER BY last_activity_at DESC LIMIT 1")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Look up a ticket by its dedicated Discord channel id (channel-mode only). */
    public Optional<Ticket> findByChannelId(String channelId) throws SQLException {
        if (channelId == null || channelId.isBlank()) return Optional.empty();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM tickets WHERE channel_id = ?")) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Ticket> list(TicketStatus statusFilter, Long categoryFilter, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM tickets WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (statusFilter != null) {
            sql.append(" AND status = ?");
            args.add(statusFilter.wire());
        }
        if (categoryFilter != null) {
            sql.append(" AND category_id = ?");
            args.add(categoryFilter);
        }
        sql.append(" ORDER BY last_activity_at DESC LIMIT ?");
        args.add(limit <= 0 ? 200 : limit);

        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object a = args.get(i);
                if (a instanceof Long l) ps.setLong(i + 1, l);
                else if (a instanceof Integer in) ps.setInt(i + 1, in);
                else ps.setString(i + 1, String.valueOf(a));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Ticket> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<StatusCount> countsByStatus() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, COUNT(*) FROM tickets GROUP BY status");
             ResultSet rs = ps.executeQuery()) {
            List<StatusCount> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new StatusCount(TicketStatus.fromWire(rs.getString(1)), rs.getInt(2)));
            }
            return out;
        }
    }

    public void touch(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET last_activity_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void setStatus(long id, TicketStatus status) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET status = ?, last_activity_at = ?, " +
                             "closed_at = CASE WHEN ? IN ('resolved','closed') THEN ? ELSE NULL END " +
                             "WHERE id = ?")) {
            ps.setString(1, status.wire());
            ps.setLong(2, now);
            ps.setString(3, status.wire());
            ps.setLong(4, now);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public void setAssignee(long id, String assigneeId, String assigneeName) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET assignee_id = ?, assignee_name = ?, last_activity_at = ? WHERE id = ?")) {
            ps.setString(1, assigneeId == null ? "" : assigneeId);
            ps.setString(2, assigneeName == null ? "" : assigneeName);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void setStaffMessage(long id, String channelId, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET staff_channel_id = ?, staff_message_id = ? WHERE id = ?")) {
            ps.setString(1, channelId == null ? "" : channelId);
            ps.setString(2, messageId == null ? "" : messageId);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void setTicketChannel(long id, String channelId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET channel_id = ? WHERE id = ?")) {
            ps.setString(1, channelId == null ? "" : channelId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void setMode(long id, String mode) throws SQLException {
        String resolved = TicketsConfig.MODE_CHANNEL.equalsIgnoreCase(mode)
                ? TicketsConfig.MODE_CHANNEL : TicketsConfig.MODE_DM;
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET mode = ? WHERE id = ?")) {
            ps.setString(1, resolved);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public long appendMessage(long ticketId, String authorKind, String authorId,
                              String authorName, String body) throws SQLException {
        return appendMessage(ticketId, authorKind, authorId, authorName, body, null);
    }

    public long appendMessage(long ticketId, String authorKind, String authorId,
                              String authorName, String body,
                              List<TicketMessage.Attachment> attachments) throws SQLException {
        long now = System.currentTimeMillis();
        String json = TicketMessage.encodeAttachments(attachments);
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO ticket_messages (ticket_id, author_kind, author_id, author_name, " +
                             "body, attachments, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ticketId);
            ps.setString(2, authorKind);
            ps.setString(3, authorId == null ? "" : authorId);
            ps.setString(4, authorName == null ? "" : authorName);
            ps.setString(5, body == null ? "" : body);
            ps.setString(6, json);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public List<TicketMessage> messages(long ticketId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM ticket_messages WHERE ticket_id = ? ORDER BY id ASC")) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TicketMessage> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new TicketMessage(
                            rs.getLong("id"),
                            rs.getLong("ticket_id"),
                            rs.getString("author_kind"),
                            rs.getString("author_id"),
                            rs.getString("author_name"),
                            rs.getString("body"),
                            rs.getString("attachments"),
                            rs.getLong("created_at")));
                }
                return out;
            }
        }
    }

    /** Look up a ticket by its transcript URL token. */
    public Optional<Ticket> findByTranscriptToken(String token) throws SQLException {
        if (token == null || token.isBlank()) return Optional.empty();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM tickets WHERE transcript_token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /** Closed/resolved tickets that have a transcript, newest first. */
    public List<Ticket> listClosedWithTranscript(int limit) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM tickets " +
                             "WHERE status IN ('resolved','closed') AND transcript_token != '' " +
                             "ORDER BY COALESCE(closed_at, last_activity_at) DESC LIMIT ?")) {
            ps.setInt(1, limit <= 0 ? 200 : limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Ticket> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public void setTranscript(long id, String path, String token, long generatedAt) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET transcript_path = ?, transcript_token = ?, " +
                             "transcript_generated_at = ? WHERE id = ?")) {
            ps.setString(1, path == null ? "" : path);
            ps.setString(2, token == null ? "" : token);
            ps.setLong(3, generatedAt);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void setMirror(long id, String channelId, String webhookId, String webhookToken) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET mirror_channel_id = ?, mirror_webhook_id = ?, " +
                             "mirror_webhook_token = ? WHERE id = ?")) {
            ps.setString(1, channelId == null ? "" : channelId);
            ps.setString(2, webhookId == null ? "" : webhookId);
            ps.setString(3, webhookToken == null ? "" : webhookToken);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public long latestMessageId(long ticketId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT MAX(id) FROM ticket_messages WHERE ticket_id = ?")) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public List<TicketMessage> messagesSince(long ticketId, long afterMessageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM ticket_messages WHERE ticket_id = ? AND id > ? ORDER BY id ASC")) {
            ps.setLong(1, ticketId);
            ps.setLong(2, afterMessageId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TicketMessage> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new TicketMessage(
                            rs.getLong("id"),
                            rs.getLong("ticket_id"),
                            rs.getString("author_kind"),
                            rs.getString("author_id"),
                            rs.getString("author_name"),
                            rs.getString("body"),
                            rs.getString("attachments"),
                            rs.getLong("created_at")));
                }
                return out;
            }
        }
    }

    private static Ticket map(ResultSet rs) throws SQLException {
        long catId = rs.getLong("category_id");
        Long categoryId = rs.wasNull() ? null : catId;
        long closed = rs.getLong("closed_at");
        Long closedAt = rs.wasNull() ? null : closed;
        String transcriptPath = optString(rs, "transcript_path");
        String transcriptToken = optString(rs, "transcript_token");
        Long transcriptGeneratedAt = null;
        try {
            long g = rs.getLong("transcript_generated_at");
            if (!rs.wasNull()) transcriptGeneratedAt = g;
        } catch (SQLException ignored) {}
        return new Ticket(
                rs.getLong("id"),
                categoryId,
                rs.getString("discord_id"),
                rs.getString("discord_username"),
                rs.getString("subject"),
                rs.getString("body"),
                TicketStatus.fromWire(rs.getString("status")),
                rs.getString("assignee_id"),
                rs.getString("assignee_name"),
                rs.getString("staff_channel_id"),
                rs.getString("staff_message_id"),
                rs.getString("mode"),
                rs.getString("channel_id"),
                rs.getLong("last_activity_at"),
                rs.getLong("created_at"),
                closedAt,
                transcriptPath,
                transcriptToken,
                transcriptGeneratedAt,
                optString(rs, "mirror_channel_id"),
                optString(rs, "mirror_webhook_id"),
                optString(rs, "mirror_webhook_token"));
    }

    private static String optString(ResultSet rs, String col) {
        try {
            String v = rs.getString(col);
            return v == null ? "" : v;
        } catch (SQLException e) {
            return "";
        }
    }
}
