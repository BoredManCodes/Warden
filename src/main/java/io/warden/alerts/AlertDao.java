package io.warden.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AlertDao {

    private final Database db;

    public AlertDao(Database db) { this.db = db; }

    public List<Alert> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM alerts ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            List<Alert> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public List<Alert> listEnabledForEvent(String eventKey) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM alerts WHERE enabled = 1 AND event = ? ORDER BY id ASC")) {
            ps.setString(1, eventKey);
            try (ResultSet rs = ps.executeQuery()) {
                List<Alert> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    public Optional<Alert> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM alerts WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public long create(Alert a) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO alerts (name, enabled, event, channel_id, message_content, "
                             + "embed_enabled, embed_title, embed_description, embed_color_hex, "
                             + "embed_thumbnail, embed_image, embed_footer, "
                             + "embed_author_name, embed_author_icon_url, embed_fields_json, "
                             + "console_commands, asplayer_commands, papi_player_uuid, "
                             + "trigger_class, conditions, expressions_enabled, async_dispatch, "
                             + "created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            bind(ps, a, now, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public void update(Alert a) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE alerts SET name = ?, enabled = ?, event = ?, channel_id = ?, "
                             + "message_content = ?, embed_enabled = ?, embed_title = ?, embed_description = ?, "
                             + "embed_color_hex = ?, embed_thumbnail = ?, embed_image = ?, embed_footer = ?, "
                             + "embed_author_name = ?, embed_author_icon_url = ?, "
                             + "embed_fields_json = ?, console_commands = ?, asplayer_commands = ?, "
                             + "papi_player_uuid = ?, trigger_class = ?, conditions = ?, "
                             + "expressions_enabled = ?, async_dispatch = ?, updated_at = ? WHERE id = ?")) {
            int i = 1;
            ps.setString(i++, a.name());
            ps.setInt(i++, a.enabled() ? 1 : 0);
            ps.setString(i++, a.event());
            ps.setString(i++, a.channelId());
            ps.setString(i++, a.messageContent());
            ps.setInt(i++, a.embedEnabled() ? 1 : 0);
            ps.setString(i++, a.embedTitle());
            ps.setString(i++, a.embedDescription());
            ps.setString(i++, a.embedColorHex());
            ps.setString(i++, a.embedThumbnail());
            ps.setString(i++, a.embedImage());
            ps.setString(i++, a.embedFooter());
            ps.setString(i++, a.embedAuthorName());
            ps.setString(i++, a.embedAuthorIconUrl());
            ps.setString(i++, writeFields(a.embedFields()));
            ps.setString(i++, a.consoleCommands());
            ps.setString(i++, a.asPlayerCommands());
            ps.setString(i++, a.papiPlayerUuid());
            ps.setString(i++, a.triggerClass());
            ps.setString(i++, a.conditions());
            ps.setInt(i++, a.expressionsEnabled() ? 1 : 0);
            ps.setInt(i++, a.asyncDispatch() ? 1 : 0);
            ps.setLong(i++, System.currentTimeMillis());
            ps.setLong(i, a.id());
            ps.executeUpdate();
        }
    }

    public void setEnabled(long id, boolean enabled) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE alerts SET enabled = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM alerts WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, Alert a, long createdAt, long updatedAt) throws SQLException {
        int i = 1;
        ps.setString(i++, a.name());
        ps.setInt(i++, a.enabled() ? 1 : 0);
        ps.setString(i++, a.event());
        ps.setString(i++, a.channelId());
        ps.setString(i++, a.messageContent());
        ps.setInt(i++, a.embedEnabled() ? 1 : 0);
        ps.setString(i++, a.embedTitle());
        ps.setString(i++, a.embedDescription());
        ps.setString(i++, a.embedColorHex());
        ps.setString(i++, a.embedThumbnail());
        ps.setString(i++, a.embedImage());
        ps.setString(i++, a.embedFooter());
        ps.setString(i++, a.embedAuthorName());
        ps.setString(i++, a.embedAuthorIconUrl());
        ps.setString(i++, writeFields(a.embedFields()));
        ps.setString(i++, a.consoleCommands());
        ps.setString(i++, a.asPlayerCommands());
        ps.setString(i++, a.papiPlayerUuid());
        ps.setString(i++, a.triggerClass());
        ps.setString(i++, a.conditions());
        ps.setInt(i++, a.expressionsEnabled() ? 1 : 0);
        ps.setInt(i++, a.asyncDispatch() ? 1 : 0);
        ps.setLong(i++, createdAt);
        ps.setLong(i, updatedAt);
    }

    private static Alert map(ResultSet rs) throws SQLException {
        return new Alert(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("enabled") != 0,
                rs.getString("event"),
                rs.getString("channel_id"),
                rs.getString("message_content"),
                rs.getInt("embed_enabled") != 0,
                rs.getString("embed_title"),
                rs.getString("embed_description"),
                rs.getString("embed_color_hex"),
                rs.getString("embed_thumbnail"),
                rs.getString("embed_image"),
                rs.getString("embed_footer"),
                rs.getString("embed_author_name"),
                rs.getString("embed_author_icon_url"),
                readFields(rs.getString("embed_fields_json")),
                rs.getString("console_commands"),
                rs.getString("asplayer_commands"),
                rs.getString("papi_player_uuid"),
                rs.getString("trigger_class"),
                rs.getString("conditions"),
                rs.getInt("expressions_enabled") != 0,
                rs.getInt("async_dispatch") != 0,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    public static List<AlertEmbedField> readFields(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = Json.MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<AlertEmbedField> out = new ArrayList<>(n.size());
            for (JsonNode el : n) {
                if (!el.isObject()) continue;
                out.add(new AlertEmbedField(
                        textOrEmpty(el, "name"),
                        textOrEmpty(el, "value"),
                        el.path("inline").asBoolean(false)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String writeFields(List<AlertEmbedField> fields) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        if (fields != null) {
            for (AlertEmbedField f : fields) {
                if (f == null) continue;
                ObjectNode o = arr.addObject();
                o.put("name", f.name() == null ? "" : f.name());
                o.put("value", f.value() == null ? "" : f.value());
                o.put("inline", f.inline());
            }
        }
        return arr.toString();
    }

    private static String textOrEmpty(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }
}
