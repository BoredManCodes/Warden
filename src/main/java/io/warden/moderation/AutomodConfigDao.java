package io.warden.moderation;

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

public final class AutomodConfigDao {

    private static final String SELECT_SQL =
            "SELECT enabled, spam_enabled, spam_threshold, spam_window_seconds, " +
                    "caps_enabled, caps_min_length, caps_percent, " +
                    "bad_words_enabled, bad_words_list, " +
                    "links_enabled, links_allowlist, " +
                    "invites_enabled, mass_mention_enabled, mass_mention_threshold, " +
                    "emoji_flood_enabled, emoji_flood_threshold, zalgo_enabled, " +
                    "action_default, exempt_role_ids_json, exempt_channel_ids_json, " +
                    "log_channel_id, warn_thresholds_json " +
                    "FROM automod_config WHERE id = 1";

    private static final String UPDATE_SQL =
            "UPDATE automod_config SET " +
                    "enabled = ?, spam_enabled = ?, spam_threshold = ?, spam_window_seconds = ?, " +
                    "caps_enabled = ?, caps_min_length = ?, caps_percent = ?, " +
                    "bad_words_enabled = ?, bad_words_list = ?, " +
                    "links_enabled = ?, links_allowlist = ?, " +
                    "invites_enabled = ?, mass_mention_enabled = ?, mass_mention_threshold = ?, " +
                    "emoji_flood_enabled = ?, emoji_flood_threshold = ?, zalgo_enabled = ?, " +
                    "action_default = ?, exempt_role_ids_json = ?, exempt_channel_ids_json = ?, " +
                    "log_channel_id = ?, warn_thresholds_json = ? " +
                    "WHERE id = 1";

    private final Database db;

    public AutomodConfigDao(Database db) { this.db = db; }

    public AutomodConfig get() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(SELECT_SQL);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO automod_config (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                return AutomodConfig.blank();
            }
            return new AutomodConfig(
                    rs.getInt("enabled") != 0,
                    rs.getInt("spam_enabled") != 0,
                    rs.getInt("spam_threshold"),
                    rs.getInt("spam_window_seconds"),
                    rs.getInt("caps_enabled") != 0,
                    rs.getInt("caps_min_length"),
                    rs.getInt("caps_percent"),
                    rs.getInt("bad_words_enabled") != 0,
                    rs.getString("bad_words_list"),
                    rs.getInt("links_enabled") != 0,
                    rs.getString("links_allowlist"),
                    rs.getInt("invites_enabled") != 0,
                    rs.getInt("mass_mention_enabled") != 0,
                    rs.getInt("mass_mention_threshold"),
                    rs.getInt("emoji_flood_enabled") != 0,
                    rs.getInt("emoji_flood_threshold"),
                    rs.getInt("zalgo_enabled") != 0,
                    rs.getString("action_default"),
                    Json.readStringList(rs.getString("exempt_role_ids_json")),
                    Json.readStringList(rs.getString("exempt_channel_ids_json")),
                    rs.getString("log_channel_id"),
                    readThresholds(rs.getString("warn_thresholds_json"))
            );
        }
    }

    public void save(AutomodConfig cfg) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(UPDATE_SQL)) {
            int i = 1;
            ps.setInt(i++, cfg.enabled() ? 1 : 0);
            ps.setInt(i++, cfg.spamEnabled() ? 1 : 0);
            ps.setInt(i++, cfg.spamThreshold());
            ps.setInt(i++, cfg.spamWindowSeconds());
            ps.setInt(i++, cfg.capsEnabled() ? 1 : 0);
            ps.setInt(i++, cfg.capsMinLength());
            ps.setInt(i++, cfg.capsPercent());
            ps.setInt(i++, cfg.badWordsEnabled() ? 1 : 0);
            ps.setString(i++, cfg.badWordsList() == null ? "" : cfg.badWordsList());
            ps.setInt(i++, cfg.linksEnabled() ? 1 : 0);
            ps.setString(i++, cfg.linksAllowlist() == null ? "" : cfg.linksAllowlist());
            ps.setInt(i++, cfg.invitesEnabled() ? 1 : 0);
            ps.setInt(i++, cfg.massMentionEnabled() ? 1 : 0);
            ps.setInt(i++, cfg.massMentionThreshold());
            ps.setInt(i++, cfg.emojiFloodEnabled() ? 1 : 0);
            ps.setInt(i++, cfg.emojiFloodThreshold());
            ps.setInt(i++, cfg.zalgoEnabled() ? 1 : 0);
            ps.setString(i++, cfg.actionDefault() == null ? "delete" : cfg.actionDefault());
            ps.setString(i++, Json.writeStringList(cfg.exemptRoleIds()));
            ps.setString(i++, Json.writeStringList(cfg.exemptChannelIds()));
            ps.setString(i++, cfg.logChannelId() == null ? "" : cfg.logChannelId());
            ps.setString(i++, writeThresholds(cfg.warnThresholds()));
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO automod_config (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                save(cfg);
            }
        }
    }

    private static List<AutomodConfig.WarnThreshold> readThresholds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = Json.MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<AutomodConfig.WarnThreshold> out = new ArrayList<>(n.size());
            for (JsonNode el : n) {
                if (!el.isObject()) continue;
                out.add(new AutomodConfig.WarnThreshold(
                        el.path("count").asInt(1),
                        el.path("action").asText("mute"),
                        el.path("durationSeconds").asInt(0)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String writeThresholds(List<AutomodConfig.WarnThreshold> list) {
        ArrayNode arr = Json.MAPPER.createArrayNode();
        if (list != null) {
            for (AutomodConfig.WarnThreshold t : list) {
                if (t == null) continue;
                ObjectNode o = arr.addObject();
                o.put("count", t.count());
                o.put("action", t.action() == null ? "mute" : t.action());
                o.put("durationSeconds", t.durationSeconds());
            }
        }
        return arr.toString();
    }
}
