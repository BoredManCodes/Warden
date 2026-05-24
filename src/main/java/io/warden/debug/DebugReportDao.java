package io.warden.debug;

import io.warden.data.Database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class DebugReportDao {

    private final Database db;

    public DebugReportDao(Database db) {
        this.db = db;
    }

    public void insert(DebugReport r) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO debug_reports (id, created_at, label, encrypted_payload, decrypt_key, analysis_status) "
                     + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, r.id());
            ps.setLong(2, r.createdAt());
            ps.setString(3, r.label() == null ? "" : r.label());
            ps.setString(4, r.encryptedPayload());
            ps.setString(5, r.decryptKey());
            ps.setString(6, r.analysisStatus());
            ps.executeUpdate();
        }
    }

    public void updatePayloadAndStatus(String id, String encryptedPayload, String analysisStatus)
            throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE debug_reports SET encrypted_payload = ?, analysis_status = ? WHERE id = ?")) {
            ps.setString(1, encryptedPayload);
            ps.setString(2, analysisStatus);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public DebugReport findById(String id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, created_at, label, encrypted_payload, decrypt_key, analysis_status "
                     + "FROM debug_reports WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return fromRow(rs);
            }
        }
        return null;
    }

    /** Returns all reports ordered newest-first, without the large encrypted_payload blob. */
    public List<DebugReport> listAll() throws SQLException {
        List<DebugReport> result = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, created_at, label, '' AS encrypted_payload, decrypt_key, analysis_status "
                     + "FROM debug_reports ORDER BY created_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(fromRow(rs));
            }
        }
        return result;
    }

    public void delete(String id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM debug_reports WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private static DebugReport fromRow(ResultSet rs) throws SQLException {
        return new DebugReport(
                rs.getString("id"),
                rs.getLong("created_at"),
                rs.getString("label"),
                rs.getString("encrypted_payload"),
                rs.getString("decrypt_key"),
                rs.getString("analysis_status")
        );
    }
}
