package io.warden.engagement;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PollDao {

    public record PollResult(int optionIndex, int votes) {}

    private final Database db;

    public PollDao(Database db) { this.db = db; }

    public long create(String channelId, String creatorId, String question,
                       List<String> options, boolean anonymous, boolean multiChoice,
                       Long endsAt) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO polls (channel_id, creator_id, question, options_json, " +
                             "anonymous, multi_choice, ends_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, channelId);
            ps.setString(2, creatorId);
            ps.setString(3, question);
            ps.setString(4, Json.writeStringList(options));
            ps.setInt(5, anonymous ? 1 : 0);
            ps.setInt(6, multiChoice ? 1 : 0);
            if (endsAt == null) ps.setNull(7, Types.INTEGER); else ps.setLong(7, endsAt);
            ps.setLong(8, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void setMessageId(long pollId, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE polls SET message_id = ? WHERE id = ?")) {
            ps.setString(1, messageId);
            ps.setLong(2, pollId);
            ps.executeUpdate();
        }
    }

    public Optional<Poll> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM polls WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public List<Poll> listOpen() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM polls WHERE closed_at IS NULL ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            List<Poll> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public List<Poll> dueForClose(long nowMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM polls WHERE closed_at IS NULL AND ends_at IS NOT NULL AND ends_at <= ?")) {
            ps.setLong(1, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                List<Poll> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public void close(long pollId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE polls SET closed_at = ? WHERE id = ? AND closed_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, pollId);
            ps.executeUpdate();
        }
    }

    public void recordVote(long pollId, String discordId, int optionIndex, boolean multiChoice) throws SQLException {
        try (Connection c = db.connection()) {
            if (!multiChoice) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM poll_votes WHERE poll_id = ? AND discord_id = ?")) {
                    del.setLong(1, pollId);
                    del.setString(2, discordId);
                    del.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO poll_votes (poll_id, discord_id, option_index, voted_at) " +
                            "VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, pollId);
                ps.setString(2, discordId);
                ps.setInt(3, optionIndex);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    public void removeVote(long pollId, String discordId, int optionIndex) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM poll_votes WHERE poll_id = ? AND discord_id = ? AND option_index = ?")) {
            ps.setLong(1, pollId);
            ps.setString(2, discordId);
            ps.setInt(3, optionIndex);
            ps.executeUpdate();
        }
    }

    public Map<Integer, Integer> tally(long pollId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT option_index, COUNT(*) FROM poll_votes WHERE poll_id = ? GROUP BY option_index")) {
            ps.setLong(1, pollId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<Integer, Integer> out = new HashMap<>();
                while (rs.next()) out.put(rs.getInt(1), rs.getInt(2));
                return out;
            }
        }
    }

    public boolean hasVoted(long pollId, String discordId, int optionIndex) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM poll_votes WHERE poll_id = ? AND discord_id = ? AND option_index = ?")) {
            ps.setLong(1, pollId);
            ps.setString(2, discordId);
            ps.setInt(3, optionIndex);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Poll map(ResultSet rs) throws SQLException {
        long ends = rs.getLong("ends_at");
        Long endsAt = rs.wasNull() ? null : ends;
        long closed = rs.getLong("closed_at");
        Long closedAt = rs.wasNull() ? null : closed;
        return new Poll(
                rs.getLong("id"),
                rs.getString("channel_id"),
                rs.getString("message_id"),
                rs.getString("creator_id"),
                rs.getString("question"),
                Json.readStringList(rs.getString("options_json")),
                rs.getInt("anonymous") != 0,
                rs.getInt("multi_choice") != 0,
                endsAt,
                closedAt,
                rs.getLong("created_at"));
    }
}
