package io.warden.feedback;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FeedbackDao {

    public record VoteTally(int up, int down) {
        public int net() { return up - down; }
    }

    public record StatusCount(FeedbackStatus status, int count) {}

    private final Database db;

    public FeedbackDao(Database db) { this.db = db; }

    /* --------------------------- feedback rows --------------------------- */

    public long create(String discordId, String username, String title, String body) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO feedback (discord_id, discord_username, title, body, " +
                             "status, created_at, updated_at) VALUES (?, ?, ?, ?, 'open', ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, discordId);
            ps.setString(2, username == null ? "" : username);
            ps.setString(3, title);
            ps.setString(4, body);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public Optional<Feedback> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM feedback WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Feedback> findByMessageId(String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM feedback WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public boolean userHasOpenFeedback(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM feedback WHERE discord_id = ? AND status = 'open' LIMIT 1")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public enum Sort { VOTES_DESC, RECENT, OLDEST }

    public List<Feedback> list(FeedbackStatus statusFilter, Sort sort, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT f.* FROM feedback f WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (statusFilter != null) {
            sql.append(" AND f.status = ?");
            args.add(statusFilter.wire());
        }
        switch (sort == null ? Sort.RECENT : sort) {
            case VOTES_DESC -> sql.append(
                    " ORDER BY (SELECT COALESCE(SUM(vote), 0) FROM feedback_votes v WHERE v.feedback_id = f.id) DESC, " +
                    " f.updated_at DESC");
            case OLDEST -> sql.append(" ORDER BY f.created_at ASC");
            default -> sql.append(" ORDER BY f.updated_at DESC");
        }
        sql.append(" LIMIT ?");
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
                List<Feedback> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public List<StatusCount> countsByStatus() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, COUNT(*) FROM feedback GROUP BY status");
             ResultSet rs = ps.executeQuery()) {
            List<StatusCount> out = new ArrayList<>();
            while (rs.next()) out.add(new StatusCount(FeedbackStatus.fromWire(rs.getString(1)), rs.getInt(2)));
            return out;
        }
    }

    public void setMessage(long id, String channelId, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE feedback SET channel_id = ?, message_id = ? WHERE id = ?")) {
            ps.setString(1, channelId == null ? "" : channelId);
            ps.setString(2, messageId == null ? "" : messageId);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void touch(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE feedback SET updated_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void setStatus(long id, FeedbackStatus status) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE feedback SET status = ?, updated_at = ?, " +
                             "closed_at = CASE WHEN ? IN ('done','declined','duplicate') THEN ? ELSE NULL END " +
                             "WHERE id = ?")) {
            ps.setString(1, status.wire());
            ps.setLong(2, now);
            ps.setString(3, status.wire());
            ps.setLong(4, now);
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    public void setStaffResponse(long id, String response) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE feedback SET staff_response = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, response == null ? "" : response);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM feedback WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /* --------------------------- votes --------------------------- */

    /**
     * Toggle a vote: returns the new tally. Same direction click removes the
     * vote; opposite direction switches it.
     */
    public VoteTally toggleVote(long feedbackId, String discordId, int direction) throws SQLException {
        if (direction != 1 && direction != -1) throw new IllegalArgumentException("direction must be +/-1");
        try (Connection c = db.connection()) {
            int existing = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT vote FROM feedback_votes WHERE feedback_id = ? AND discord_id = ?")) {
                ps.setLong(1, feedbackId);
                ps.setString(2, discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) existing = rs.getInt(1);
                }
            }
            if (existing == direction) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM feedback_votes WHERE feedback_id = ? AND discord_id = ?")) {
                    del.setLong(1, feedbackId);
                    del.setString(2, discordId);
                    del.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR REPLACE INTO feedback_votes (feedback_id, discord_id, vote, voted_at) " +
                                "VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, feedbackId);
                    ps.setString(2, discordId);
                    ps.setInt(3, direction);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
        }
        return tally(feedbackId);
    }

    public VoteTally tally(long feedbackId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT vote, COUNT(*) FROM feedback_votes WHERE feedback_id = ? GROUP BY vote")) {
            ps.setLong(1, feedbackId);
            try (ResultSet rs = ps.executeQuery()) {
                int up = 0, down = 0;
                while (rs.next()) {
                    int v = rs.getInt(1);
                    int n = rs.getInt(2);
                    if (v == 1) up = n; else if (v == -1) down = n;
                }
                return new VoteTally(up, down);
            }
        }
    }

    public Map<Long, VoteTally> tallyMany(List<Long> ids) throws SQLException {
        Map<Long, VoteTally> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        StringBuilder sql = new StringBuilder(
                "SELECT feedback_id, vote, COUNT(*) FROM feedback_votes WHERE feedback_id IN (");
        for (int i = 0; i < ids.size(); i++) sql.append(i == 0 ? "?" : ",?");
        sql.append(") GROUP BY feedback_id, vote");
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                Map<Long, int[]> acc = new HashMap<>();
                while (rs.next()) {
                    long fid = rs.getLong(1);
                    int vote = rs.getInt(2);
                    int n = rs.getInt(3);
                    int[] row = acc.computeIfAbsent(fid, k -> new int[]{0, 0});
                    if (vote == 1) row[0] += n; else if (vote == -1) row[1] += n;
                }
                for (var e : acc.entrySet()) out.put(e.getKey(), new VoteTally(e.getValue()[0], e.getValue()[1]));
            }
        }
        for (Long id : ids) out.putIfAbsent(id, new VoteTally(0, 0));
        return out;
    }

    /* --------------------------- notes --------------------------- */

    public long appendNote(long feedbackId, String authorKind, String authorId,
                           String authorName, String body) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO feedback_notes (feedback_id, author_kind, author_id, author_name, " +
                             "body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, feedbackId);
            ps.setString(2, authorKind);
            ps.setString(3, authorId == null ? "" : authorId);
            ps.setString(4, authorName == null ? "" : authorName);
            ps.setString(5, body);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public List<FeedbackNote> notes(long feedbackId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM feedback_notes WHERE feedback_id = ? ORDER BY id ASC")) {
            ps.setLong(1, feedbackId);
            try (ResultSet rs = ps.executeQuery()) {
                List<FeedbackNote> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new FeedbackNote(
                            rs.getLong("id"),
                            rs.getLong("feedback_id"),
                            rs.getString("author_kind"),
                            rs.getString("author_id"),
                            rs.getString("author_name"),
                            rs.getString("body"),
                            rs.getLong("created_at")));
                }
                return out;
            }
        }
    }

    private static Feedback map(ResultSet rs) throws SQLException {
        long closed = rs.getLong("closed_at");
        Long closedAt = rs.wasNull() ? null : closed;
        return new Feedback(
                rs.getLong("id"),
                rs.getString("discord_id"),
                rs.getString("discord_username"),
                rs.getString("title"),
                rs.getString("body"),
                FeedbackStatus.fromWire(rs.getString("status")),
                rs.getString("staff_response"),
                rs.getString("channel_id"),
                rs.getString("message_id"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                closedAt);
    }
}
