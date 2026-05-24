package io.warden.data.dao;

import io.warden.data.Database;
import io.warden.data.Json;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.QuestionKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class QuestionDao {

    private final Database db;

    public QuestionDao(Database db) { this.db = db; }

    public List<Question> listAll() throws SQLException {
        return list(false);
    }

    public List<Question> listActive() throws SQLException {
        return list(true);
    }

    private List<Question> list(boolean activeOnly) throws SQLException {
        String sql = "SELECT id, order_index, prompt, kind, choices_json, required, active FROM questions";
        if (activeOnly) sql += " WHERE active = 1";
        sql += " ORDER BY order_index ASC, id ASC";
        try (Connection c = db.connection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<Question> out = new ArrayList<>();
            while (rs.next()) out.add(mapRow(rs));
            return out;
        }
    }

    public Optional<Question> findById(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, order_index, prompt, kind, choices_json, required, active FROM questions WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public long create(int order, String prompt, QuestionKind kind, List<String> choices, boolean required, boolean active) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO questions (order_index, prompt, kind, choices_json, required, active) VALUES (?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, order);
            ps.setString(2, prompt);
            ps.setString(3, kind.wire());
            ps.setString(4, Json.writeStringList(choices));
            ps.setInt(5, required ? 1 : 0);
            ps.setInt(6, active ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void update(long id, int order, String prompt, QuestionKind kind, List<String> choices, boolean required, boolean active) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE questions SET order_index = ?, prompt = ?, kind = ?, choices_json = ?, required = ?, active = ? WHERE id = ?")) {
            ps.setInt(1, order);
            ps.setString(2, prompt);
            ps.setString(3, kind.wire());
            ps.setString(4, Json.writeStringList(choices));
            ps.setInt(5, required ? 1 : 0);
            ps.setInt(6, active ? 1 : 0);
            ps.setLong(7, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM questions WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Reassign {@code order_index} so the questions appear in the given id order.
     * Uses gaps of 10 (i.e. 10, 20, 30, ...) to leave room for future single-row
     * moves without a full renumber. Ids not present in the table are silently
     * skipped. Atomic - all updates land or none do.
     */
    public void reorder(List<Long> idsInOrder) throws SQLException {
        if (idsInOrder == null || idsInOrder.isEmpty()) return;
        try (Connection c = db.connection()) {
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE questions SET order_index = ? WHERE id = ?")) {
                int order = 10;
                for (Long id : idsInOrder) {
                    if (id == null) continue;
                    ps.setInt(1, order);
                    ps.setLong(2, id);
                    ps.addBatch();
                    order += 10;
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAutoCommit);
            }
        }
    }

    private Question mapRow(ResultSet rs) throws SQLException {
        return new Question(
                rs.getLong("id"),
                rs.getInt("order_index"),
                rs.getString("prompt"),
                QuestionKind.fromWire(rs.getString("kind")),
                Json.readStringList(rs.getString("choices_json")),
                rs.getInt("required") != 0,
                rs.getInt("active") != 0
        );
    }
}
