package io.warden.data.dao;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AuditDao {

    private final Database db;

    public AuditDao(Database db) { this.db = db; }

    public void write(String actor, String action, String targetDiscordId, Map<String, ?> payload) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log (actor, action, target_discord_id, payload_json, at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, targetDiscordId);
            ps.setString(4, payload == null ? "{}" : Json.writeObject(payload));
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public record Entry(long id, String actor, String action, String targetDiscordId, String payloadJson, long at) {}

    /**
     * Count audit rows for an exact action over the inclusive ms window.
     * When {@code distinctByTarget} is true, dedupes by target_discord_id so
     * "37 people accepted rules" doesn't get inflated by retries.
     */
    public long countAction(String action, long fromMs, long toMs, boolean distinctByTarget) throws SQLException {
        String sql = distinctByTarget
                ? "SELECT COUNT(DISTINCT target_discord_id) FROM audit_log "
                        + "WHERE action = ? AND at >= ? AND at <= ? AND target_discord_id IS NOT NULL"
                : "SELECT COUNT(*) FROM audit_log WHERE action = ? AND at >= ? AND at <= ?";
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, action);
            ps.setLong(2, fromMs);
            ps.setLong(3, toMs);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public List<Entry> listRecent(int limit) throws SQLException {
        return listFiltered(null, null, null, limit);
    }

    /** Filter by optional actor / action substring / target discord id. Pass null/blank to skip. */
    public List<Entry> listFiltered(String actor, String actionContains, String targetDiscordId, int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, actor, action, target_discord_id, payload_json, at FROM audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = ?");
            params.add(actor);
        }
        if (actionContains != null && !actionContains.isBlank()) {
            sql.append(" AND action LIKE ?");
            params.add("%" + actionContains + "%");
        }
        if (targetDiscordId != null && !targetDiscordId.isBlank()) {
            sql.append(" AND target_discord_id = ?");
            params.add(targetDiscordId);
        }
        sql.append(" ORDER BY at DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 1000)));

        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer integer) ps.setInt(i + 1, integer);
                else ps.setString(i + 1, String.valueOf(p));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Entry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Entry(
                            rs.getLong("id"),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("target_discord_id"),
                            rs.getString("payload_json"),
                            rs.getLong("at")));
                }
                return out;
            }
        }
    }
}
