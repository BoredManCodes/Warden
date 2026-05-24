package io.warden.web.auth;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-page allowed-role lists for /dash/* pages. An empty list means "fall
 * back to the legacy mod role"; non-empty means "any of these role IDs is
 * sufficient to view the page". Owner + Config admin always pass regardless.
 */
public final class PageAccessDao {

    private final Database db;

    public PageAccessDao(Database db) { this.db = db; }

    public Map<String, List<String>> loadAll() throws SQLException {
        Map<String, List<String>> out = new HashMap<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT page_key, role_ids_json FROM page_access");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), Json.readStringList(rs.getString(2)));
            }
        }
        return out;
    }

    public List<String> rolesFor(String pageKey) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT role_ids_json FROM page_access WHERE page_key = ?")) {
            ps.setString(1, pageKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                return Json.readStringList(rs.getString(1));
            }
        }
    }

    public void save(String pageKey, List<String> roleIds) throws SQLException {
        String json = Json.writeStringList(roleIds == null ? List.of() : roleIds);
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO page_access (page_key, role_ids_json) VALUES (?, ?) " +
                             "ON CONFLICT(page_key) DO UPDATE SET role_ids_json = excluded.role_ids_json")) {
            ps.setString(1, pageKey);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }
}
