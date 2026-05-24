package io.warden.moderation;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public final class RaidProtectionDao {

    public record Config(
            boolean enabled,
            int joinsThreshold,
            int joinsWindowSeconds,
            int accountAgeMinDays,
            String lockdownAction,
            Long lockdownUntil,
            String logChannelId,
            int autoDisableMinutes
    ) {
        public static Config blank() {
            return new Config(false, 10, 30, 0, "kick", null, "", 15);
        }
    }

    private final Database db;

    public RaidProtectionDao(Database db) { this.db = db; }

    public Config get() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT enabled, joins_threshold, joins_window_seconds, account_age_min_days, " +
                             "lockdown_action, lockdown_until, log_channel_id, auto_disable_minutes " +
                             "FROM raid_protection WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO raid_protection (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                return Config.blank();
            }
            long lu = rs.getLong("lockdown_until");
            Long lockdownUntil = rs.wasNull() ? null : lu;
            return new Config(
                    rs.getInt("enabled") != 0,
                    rs.getInt("joins_threshold"),
                    rs.getInt("joins_window_seconds"),
                    rs.getInt("account_age_min_days"),
                    rs.getString("lockdown_action"),
                    lockdownUntil,
                    rs.getString("log_channel_id"),
                    rs.getInt("auto_disable_minutes"));
        }
    }

    public void save(Config cfg) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE raid_protection SET enabled = ?, joins_threshold = ?, joins_window_seconds = ?, " +
                             "account_age_min_days = ?, lockdown_action = ?, lockdown_until = ?, " +
                             "log_channel_id = ?, auto_disable_minutes = ? WHERE id = 1")) {
            ps.setInt(1, cfg.enabled() ? 1 : 0);
            ps.setInt(2, cfg.joinsThreshold());
            ps.setInt(3, cfg.joinsWindowSeconds());
            ps.setInt(4, cfg.accountAgeMinDays());
            ps.setString(5, cfg.lockdownAction() == null ? "kick" : cfg.lockdownAction());
            if (cfg.lockdownUntil() == null) ps.setNull(6, Types.INTEGER);
            else ps.setLong(6, cfg.lockdownUntil());
            ps.setString(7, cfg.logChannelId() == null ? "" : cfg.logChannelId());
            ps.setInt(8, cfg.autoDisableMinutes());
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO raid_protection (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                save(cfg);
            }
        }
    }

    public void setLockdownUntil(Long lockdownUntil) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE raid_protection SET lockdown_until = ? WHERE id = 1")) {
            if (lockdownUntil == null) ps.setNull(1, Types.INTEGER);
            else ps.setLong(1, lockdownUntil);
            ps.executeUpdate();
        }
    }
}
