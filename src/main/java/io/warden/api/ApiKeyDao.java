package io.warden.api;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ApiKeyDao {

    private final Database db;

    public ApiKeyDao(Database db) { this.db = db; }

    public long create(String label, String prefix, String tokenHash,
                       List<String> scopes, String createdBy) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO api_keys (label, prefix, token_hash, scopes_json, created_by, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label == null ? "" : label);
            ps.setString(2, prefix);
            ps.setString(3, tokenHash);
            ps.setString(4, Json.writeStringList(scopes == null ? List.of() : scopes));
            ps.setString(5, createdBy == null ? "" : createdBy);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Optional<ApiKey> findByHash(String tokenHash) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM api_keys WHERE token_hash = ?")) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        }
    }

    public Optional<ApiKey> findById(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM api_keys WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        }
    }

    public List<ApiKey> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM api_keys ORDER BY revoked_at IS NULL DESC, created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            List<ApiKey> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public void touchLastUsed(long id, long now) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE api_keys SET last_used_at = ? WHERE id = ?")) {
            ps.setLong(1, now);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void revoke(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE api_keys SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM api_keys WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static ApiKey map(ResultSet rs) throws SQLException {
        long lu = rs.getLong("last_used_at");
        Long lastUsed = rs.wasNull() ? null : lu;
        long rv = rs.getLong("revoked_at");
        Long revoked = rs.wasNull() ? null : rv;
        return new ApiKey(
                rs.getLong("id"),
                rs.getString("label"),
                rs.getString("prefix"),
                rs.getString("token_hash"),
                Json.readStringList(rs.getString("scopes_json")),
                rs.getString("created_by"),
                rs.getLong("created_at"),
                lastUsed,
                revoked
        );
    }
}
