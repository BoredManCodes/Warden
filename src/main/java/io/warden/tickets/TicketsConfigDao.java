package io.warden.tickets;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class TicketsConfigDao {

    private final Database db;

    public TicketsConfigDao(Database db) { this.db = db; }

    public TicketsConfig get() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT staff_channel_id, dm_reporter_on_open, dm_reporter_on_reply, " +
                             "dm_reporter_on_status, open_ack_message, closed_lock_replies, " +
                             "default_mode, channel_category_id " +
                             "FROM tickets_config WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO tickets_config (id) VALUES (1)")) {
                        ins.executeUpdate();
                    }
                    return TicketsConfig.blank();
                }
                return new TicketsConfig(
                        rs.getString("staff_channel_id"),
                        rs.getInt("dm_reporter_on_open") != 0,
                        rs.getInt("dm_reporter_on_reply") != 0,
                        rs.getInt("dm_reporter_on_status") != 0,
                        rs.getString("open_ack_message"),
                        rs.getInt("closed_lock_replies") != 0,
                        rs.getString("default_mode"),
                        rs.getString("channel_category_id"));
            }
        }
    }

    public void save(TicketsConfig cfg) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets_config SET staff_channel_id = ?, dm_reporter_on_open = ?, " +
                             "dm_reporter_on_reply = ?, dm_reporter_on_status = ?, " +
                             "open_ack_message = ?, closed_lock_replies = ?, " +
                             "default_mode = ?, channel_category_id = ? WHERE id = 1")) {
            ps.setString(1, cfg.staffChannelId() == null ? "" : cfg.staffChannelId());
            ps.setInt(2, cfg.dmReporterOnOpen() ? 1 : 0);
            ps.setInt(3, cfg.dmReporterOnReply() ? 1 : 0);
            ps.setInt(4, cfg.dmReporterOnStatus() ? 1 : 0);
            ps.setString(5, cfg.openAckMessage() == null ? "" : cfg.openAckMessage());
            ps.setInt(6, cfg.closedLockReplies() ? 1 : 0);
            ps.setString(7, cfg.normalisedDefaultMode());
            ps.setString(8, cfg.channelCategoryId() == null ? "" : cfg.channelCategoryId());
            int n = ps.executeUpdate();
            if (n == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO tickets_config (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                save(cfg);
            }
        }
    }
}
