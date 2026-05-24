package io.warden.data.dao;

import io.warden.data.Database;
import io.warden.onboarding.model.Application;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ApplicationDao {

    private final Database db;

    public ApplicationDao(Database db) { this.db = db; }

    public long create(String discordId) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO applications (discord_id, submitted_at) VALUES (?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, discordId);
            ps.setLong(2, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void recordLlm(long applicationId, String decision, int confidenceX1000, String reasoning) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE applications SET llm_decision = ?, llm_confidence_x1000 = ?, llm_reasoning = ? WHERE id = ?")) {
            ps.setString(1, decision);
            ps.setInt(2, confidenceX1000);
            ps.setString(3, reasoning);
            ps.setLong(4, applicationId);
            ps.executeUpdate();
        }
    }

    public void recordFinal(long applicationId, String finalDecision, String decidedBy, String modNote) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE applications SET final_decision = ?, decided_by = ?, decided_at = ?, mod_note = ? WHERE id = ?")) {
            ps.setString(1, finalDecision);
            ps.setString(2, decidedBy);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, modNote);
            ps.setLong(5, applicationId);
            ps.executeUpdate();
        }
    }

    public void setModMessageId(long applicationId, String messageId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE applications SET mod_message_id = ? WHERE id = ?")) {
            ps.setString(1, messageId);
            ps.setLong(2, applicationId);
            ps.executeUpdate();
        }
    }

    public Optional<Application> findById(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM applications WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public List<Application> listFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM applications WHERE discord_id = ? ORDER BY submitted_at DESC")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Application> out = new ArrayList<>();
                while (rs.next()) out.add(mapRow(rs));
                return out;
            }
        }
    }

    public Optional<Application> latestFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM applications WHERE discord_id = ? ORDER BY submitted_at DESC LIMIT 1")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public List<Application> listPending() throws SQLException {
        try (Connection c = db.connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM applications WHERE final_decision IS NULL ORDER BY submitted_at DESC")) {
            List<Application> out = new ArrayList<>();
            while (rs.next()) out.add(mapRow(rs));
            return out;
        }
    }

    public int countPending() throws SQLException {
        try (Connection c = db.connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM applications WHERE final_decision IS NULL")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Aggregate (approved, denied, avg time-to-decision in ms) for applications
     * decided in the given inclusive epoch-ms window. Avg is null when there
     * were no decisions. Used by the dashboard headline cards.
     */
    public DecisionStats decisionStatsBetween(long fromMs, long toMs) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT "
                             + " SUM(CASE WHEN final_decision = 'approve' THEN 1 ELSE 0 END) AS approved, "
                             + " SUM(CASE WHEN final_decision = 'deny'    THEN 1 ELSE 0 END) AS denied, "
                             + " AVG(CASE WHEN final_decision IN ('approve','deny') "
                             + "          THEN (decided_at - submitted_at) END) AS avg_ms "
                             + "FROM applications "
                             + "WHERE decided_at IS NOT NULL AND decided_at >= ? AND decided_at <= ?")) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int approved = rs.getInt("approved");
                int denied = rs.getInt("denied");
                double avg = rs.getDouble("avg_ms");
                Long avgMs = rs.wasNull() ? null : (long) avg;
                return new DecisionStats(approved, denied, avgMs);
            }
        }
    }

    public record DecisionStats(int approved, int denied, Long avgDecisionMs) {}

    private Application mapRow(ResultSet rs) throws SQLException {
        Integer confidence = rs.getObject("llm_confidence_x1000") == null ? null : rs.getInt("llm_confidence_x1000");
        Long decidedAt = rs.getObject("decided_at") == null ? null : rs.getLong("decided_at");
        return new Application(
                rs.getLong("id"),
                rs.getString("discord_id"),
                rs.getLong("submitted_at"),
                rs.getString("llm_decision"),
                confidence,
                rs.getString("llm_reasoning"),
                rs.getString("final_decision"),
                rs.getString("decided_by"),
                decidedAt,
                rs.getString("mod_note"),
                rs.getString("mod_message_id")
        );
    }
}
