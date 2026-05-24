package io.warden.data.dao;

import io.warden.data.Database;
import io.warden.data.Json;
import io.warden.onboarding.model.Answer;
import io.warden.onboarding.model.AnswerValue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class AnswerDao {

    private final Database db;

    public AnswerDao(Database db) { this.db = db; }

    public List<Answer> listFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, discord_id, question_id, value, submitted_at FROM answers WHERE discord_id = ? ORDER BY question_id ASC")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Answer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Answer(
                            rs.getLong("id"),
                            rs.getString("discord_id"),
                            rs.getLong("question_id"),
                            Json.readAnswer(rs.getString("value")),
                            rs.getLong("submitted_at")));
                }
                return out;
            }
        }
    }

    public void upsert(String discordId, long questionId, AnswerValue value) throws SQLException {
        long now = System.currentTimeMillis();
        String json = Json.writeAnswer(value);
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO answers (discord_id, question_id, value, submitted_at) VALUES (?, ?, ?, ?) " +
                             "ON CONFLICT(discord_id, question_id) DO UPDATE SET value = excluded.value, submitted_at = excluded.submitted_at")) {
            ps.setString(1, discordId);
            ps.setLong(2, questionId);
            ps.setString(3, json);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public void clearFor(String discordId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM answers WHERE discord_id = ?")) {
            ps.setString(1, discordId);
            ps.executeUpdate();
        }
    }
}
