package io.warden.timezone;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class TimezoneConfigDao {

    private final Database db;

    public TimezoneConfigDao(Database db) { this.db = db; }

    public TimezoneConfig get() {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT onboarding_required, geoip_enabled, scheduler_enabled "
                             + "FROM timezone_config WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO timezone_config (id) VALUES (1)")) {
                        ins.executeUpdate();
                    }
                    return TimezoneConfig.defaults();
                }
                return new TimezoneConfig(
                        rs.getInt(1) != 0,
                        rs.getInt(2) != 0,
                        rs.getInt(3) != 0
                );
            }
        } catch (SQLException e) {
            return TimezoneConfig.defaults();
        }
    }

    public void save(TimezoneConfig cfg) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE timezone_config SET onboarding_required = ?, geoip_enabled = ?, "
                             + "scheduler_enabled = ? WHERE id = 1")) {
            ps.setInt(1, cfg.onboardingRequired() ? 1 : 0);
            ps.setInt(2, cfg.geoipEnabled() ? 1 : 0);
            ps.setInt(3, cfg.schedulerEnabled() ? 1 : 0);
            int n = ps.executeUpdate();
            if (n == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO timezone_config (id, onboarding_required, geoip_enabled, scheduler_enabled) "
                                + "VALUES (1, ?, ?, ?)")) {
                    ins.setInt(1, cfg.onboardingRequired() ? 1 : 0);
                    ins.setInt(2, cfg.geoipEnabled() ? 1 : 0);
                    ins.setInt(3, cfg.schedulerEnabled() ? 1 : 0);
                    ins.executeUpdate();
                }
            }
        }
    }
}
