package io.warden.levels;

import io.warden.data.Database;
import io.warden.data.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class LevelConfigDao {

    private final Database db;

    public LevelConfigDao(Database db) { this.db = db; }

    public LevelConfig get() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT enabled, xp_per_message_min, xp_per_message_max, cooldown_seconds, " +
                             "levelup_announce, levelup_channel_id, levelup_message_template, " +
                             "leaderboard_public, no_xp_role_ids_json, no_xp_channel_ids_json, " +
                             "rank_card_accent, rank_card_background, mc_xp_enabled FROM level_config WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO level_config (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                return LevelConfig.blank();
            }
            return new LevelConfig(
                    rs.getInt("enabled") != 0,
                    rs.getInt("xp_per_message_min"),
                    rs.getInt("xp_per_message_max"),
                    rs.getInt("cooldown_seconds"),
                    rs.getInt("levelup_announce") != 0,
                    rs.getString("levelup_channel_id"),
                    rs.getString("levelup_message_template"),
                    rs.getInt("leaderboard_public") != 0,
                    Json.readStringList(rs.getString("no_xp_role_ids_json")),
                    Json.readStringList(rs.getString("no_xp_channel_ids_json")),
                    rs.getString("rank_card_accent"),
                    rs.getString("rank_card_background"),
                    rs.getInt("mc_xp_enabled") != 0);
        }
    }

    public void save(LevelConfig cfg) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE level_config SET enabled = ?, xp_per_message_min = ?, xp_per_message_max = ?, " +
                             "cooldown_seconds = ?, levelup_announce = ?, levelup_channel_id = ?, " +
                             "levelup_message_template = ?, leaderboard_public = ?, " +
                             "no_xp_role_ids_json = ?, no_xp_channel_ids_json = ?, " +
                             "rank_card_accent = ?, rank_card_background = ?, mc_xp_enabled = ? WHERE id = 1")) {
            int i = 1;
            ps.setInt(i++, cfg.enabled() ? 1 : 0);
            ps.setInt(i++, cfg.xpPerMessageMin());
            ps.setInt(i++, cfg.xpPerMessageMax());
            ps.setInt(i++, cfg.cooldownSeconds());
            ps.setInt(i++, cfg.levelupAnnounce() ? 1 : 0);
            ps.setString(i++, cfg.levelupChannelId() == null ? "" : cfg.levelupChannelId());
            ps.setString(i++, cfg.levelupMessageTemplate() == null ? "" : cfg.levelupMessageTemplate());
            ps.setInt(i++, cfg.leaderboardPublic() ? 1 : 0);
            ps.setString(i++, Json.writeStringList(cfg.noXpRoleIds()));
            ps.setString(i++, Json.writeStringList(cfg.noXpChannelIds()));
            ps.setString(i++, cfg.rankCardAccent() == null ? "#5865F2" : cfg.rankCardAccent());
            ps.setString(i++, cfg.rankCardBackground() == null ? "" : cfg.rankCardBackground());
            ps.setInt(i++, cfg.mcXpEnabled() ? 1 : 0);
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO level_config (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                save(cfg);
            }
        }
    }
}
