package io.warden.grim;

import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view onto Grim's own {@code violations.sqlite} living at
 * {@code plugins/GrimAC/violations.sqlite}. Grim normalises check names,
 * server names, grim/client/server versions and brands into lookup tables;
 * this DAO joins them back together for the dashboard.
 *
 * <p>All connections are opened read-only so Warden never blocks Grim's
 * writer or accidentally mutates Grim's history.
 */
public final class GrimViolationDao {

    private final Path file;

    public GrimViolationDao(Path violationsFile) {
        this.file = violationsFile;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    public record Violation(
            long id,
            long ts,
            String uuid,
            String checkName,
            long vl,
            String verbose
    ) {}

    public record CheckCount(String checkName, long count) {}

    public List<Violation> recent(int limit) throws SQLException {
        int n = Math.max(1, Math.min(limit, 1000));
        String sql = "SELECT v.id, v.created_at, v.uuid, v.vl, v.verbose, cn.check_name_string "
                + "FROM grim_history_violations v "
                + "JOIN grim_history_check_names cn ON v.check_name_id = cn.id "
                + "ORDER BY v.id DESC LIMIT ?";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                List<Violation> out = new ArrayList<>(n);
                while (rs.next()) {
                    out.add(new Violation(
                            rs.getLong("id"),
                            rs.getLong("created_at"),
                            uuidFromBlob(rs.getBytes("uuid")),
                            rs.getString("check_name_string"),
                            rs.getLong("vl"),
                            rs.getString("verbose")));
                }
                return out;
            }
        }
    }

    public long totalCount() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM grim_history_violations");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public List<CheckCount> topChecks(int limit) throws SQLException {
        int n = Math.max(1, Math.min(limit, 50));
        String sql = "SELECT cn.check_name_string, COUNT(*) AS n "
                + "FROM grim_history_violations v "
                + "JOIN grim_history_check_names cn ON v.check_name_id = cn.id "
                + "GROUP BY cn.check_name_string ORDER BY n DESC LIMIT ?";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                List<CheckCount> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new CheckCount(rs.getString("check_name_string"), rs.getLong("n")));
                }
                return out;
            }
        }
    }

    private Connection open() throws SQLException {
        if (file == null) {
            throw new SQLException("Grim violations file not configured");
        }
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        cfg.setBusyTimeout(2000);
        return DriverManager.getConnection(
                "jdbc:sqlite:" + file.toAbsolutePath(),
                cfg.toProperties());
    }

    private static String uuidFromBlob(byte[] raw) {
        if (raw == null || raw.length != 16) return "";
        long msb = 0L, lsb = 0L;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (raw[i] & 0xff);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (raw[i] & 0xff);
        return new UUID(msb, lsb).toString();
    }
}
