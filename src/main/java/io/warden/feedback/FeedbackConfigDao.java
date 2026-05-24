package io.warden.feedback;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class FeedbackConfigDao {

    private final Database db;

    public FeedbackConfigDao(Database db) { this.db = db; }

    public FeedbackConfig get() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT channel_id, open_via_command, dm_reporter_on_status, " +
                             "dm_reporter_on_response, require_unique_per_user, locked_when_resolved " +
                             "FROM feedback_config WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO feedback_config (id) VALUES (1)")) {
                        ins.executeUpdate();
                    }
                    return FeedbackConfig.blank();
                }
                return new FeedbackConfig(
                        rs.getString("channel_id"),
                        rs.getInt("open_via_command") != 0,
                        rs.getInt("dm_reporter_on_status") != 0,
                        rs.getInt("dm_reporter_on_response") != 0,
                        rs.getInt("require_unique_per_user") != 0,
                        rs.getInt("locked_when_resolved") != 0);
            }
        }
    }

    public void save(FeedbackConfig cfg) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE feedback_config SET channel_id = ?, open_via_command = ?, " +
                             "dm_reporter_on_status = ?, dm_reporter_on_response = ?, " +
                             "require_unique_per_user = ?, locked_when_resolved = ? WHERE id = 1")) {
            ps.setString(1, cfg.channelId() == null ? "" : cfg.channelId());
            ps.setInt(2, cfg.openViaCommand() ? 1 : 0);
            ps.setInt(3, cfg.dmReporterOnStatus() ? 1 : 0);
            ps.setInt(4, cfg.dmReporterOnResponse() ? 1 : 0);
            ps.setInt(5, cfg.requireUniquePerUser() ? 1 : 0);
            ps.setInt(6, cfg.lockedWhenResolved() ? 1 : 0);
            int n = ps.executeUpdate();
            if (n == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO feedback_config (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                save(cfg);
            }
        }
    }
}
