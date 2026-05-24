package io.warden.data.dao;

import io.warden.data.Database;
import io.warden.data.Json;
import io.warden.onboarding.model.DenyAction;
import io.warden.onboarding.model.FlowConfig;
import io.warden.onboarding.model.LandingConfig;
import io.warden.onboarding.model.Settings;
import io.warden.onboarding.model.TriageMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class SettingsDao {

    private static final String SELECT_SQL =
            "SELECT rules_markdown, gated_role_id, full_role_id, mod_role_id, " +
                    "config_admin_role_id, web_manager_role_id, " +
                    "welcome_channel_id, mod_review_channel_id, llm_system_prompt, " +
                    "llm_auto_approve_threshold_x1000, llm_auto_deny_threshold_x1000, llm_auto_deny_enabled, " +
                    "llm_api_key, llm_base_url, llm_model, " +
                    "geoip_enabled, geoip_license_key, " +
                    "delivery_via_dm, delivery_via_channel, delivery_channel_id, delivery_message_template, " +
                    "entry_via_discord_button, entry_via_web_code, entry_via_web_oauth, " +
                    "gating_enabled, triage_mode, " +
                    "approve_dm_enabled, approve_dm_template, approve_channel_announce, approve_channel_template, approve_extra_roles_json, " +
                    "deny_dm_enabled, deny_dm_template, deny_action, " +
                    "landing_mode, landing_redirect_url, landing_server_name, landing_server_address, landing_tagline, landing_join_url, " +
                    "landing_map_enabled, landing_map_provider, landing_map_url, landing_map_label, " +
                    "landing_brand_image_url, landing_hero_image_url, " +
                    "landing_accent_color, landing_papi_players_online, landing_papi_discord_members, " +
                    "landing_stat_players_label, landing_stat_members_label, " +
                    "landing_google_analytics_id, landing_cookie_banner, " +
                    "landing_leaderboard_enabled, landing_leaderboard_title, landing_leaderboard_description, " +
                    "landing_leaderboard_top_n, landing_leaderboard_label, " +
                    "landing_promo_video_url, " +
                    "landing_features_json, landing_faqs_json " +
                    "FROM settings WHERE id = 1";

    private static final String UPDATE_SQL =
            "UPDATE settings SET " +
                    "rules_markdown = ?, gated_role_id = ?, full_role_id = ?, mod_role_id = ?, " +
                    "config_admin_role_id = ?, web_manager_role_id = ?, " +
                    "welcome_channel_id = ?, mod_review_channel_id = ?, llm_system_prompt = ?, " +
                    "llm_auto_approve_threshold_x1000 = ?, llm_auto_deny_threshold_x1000 = ?, llm_auto_deny_enabled = ?, " +
                    "llm_api_key = ?, llm_base_url = ?, llm_model = ?, " +
                    "geoip_enabled = ?, geoip_license_key = ?, " +
                    "delivery_via_dm = ?, delivery_via_channel = ?, delivery_channel_id = ?, delivery_message_template = ?, " +
                    "entry_via_discord_button = ?, entry_via_web_code = ?, entry_via_web_oauth = ?, " +
                    "gating_enabled = ?, triage_mode = ?, " +
                    "approve_dm_enabled = ?, approve_dm_template = ?, approve_channel_announce = ?, approve_channel_template = ?, approve_extra_roles_json = ?, " +
                    "deny_dm_enabled = ?, deny_dm_template = ?, deny_action = ?, " +
                    "landing_mode = ?, landing_redirect_url = ?, landing_server_name = ?, landing_server_address = ?, landing_tagline = ?, landing_join_url = ?, " +
                    "landing_map_enabled = ?, landing_map_provider = ?, landing_map_url = ?, landing_map_label = ?, " +
                    "landing_brand_image_url = ?, landing_hero_image_url = ?, " +
                    "landing_accent_color = ?, landing_papi_players_online = ?, landing_papi_discord_members = ?, " +
                    "landing_stat_players_label = ?, landing_stat_members_label = ?, " +
                    "landing_google_analytics_id = ?, landing_cookie_banner = ?, " +
                    "landing_leaderboard_enabled = ?, landing_leaderboard_title = ?, landing_leaderboard_description = ?, " +
                    "landing_leaderboard_top_n = ?, landing_leaderboard_label = ?, " +
                    "landing_promo_video_url = ?, " +
                    "landing_features_json = ?, landing_faqs_json = ? " +
                    "WHERE id = 1";

    private final Database db;

    public SettingsDao(Database db) { this.db = db; }

    public Settings get() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(SELECT_SQL)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Defensive: ensure the singleton row exists so subsequent updates work.
                    try (PreparedStatement ins = c.prepareStatement("INSERT INTO settings (id) VALUES (1)")) {
                        ins.executeUpdate();
                    }
                    return Settings.blank();
                }
                FlowConfig flow = new FlowConfig(
                        rs.getInt("delivery_via_dm") != 0,
                        rs.getInt("delivery_via_channel") != 0,
                        rs.getString("delivery_channel_id"),
                        rs.getString("delivery_message_template"),
                        rs.getInt("entry_via_discord_button") != 0,
                        rs.getInt("entry_via_web_code") != 0,
                        rs.getInt("entry_via_web_oauth") != 0,
                        rs.getInt("gating_enabled") != 0,
                        TriageMode.fromWire(rs.getString("triage_mode")),
                        rs.getInt("approve_dm_enabled") != 0,
                        rs.getString("approve_dm_template"),
                        rs.getInt("approve_channel_announce") != 0,
                        rs.getString("approve_channel_template"),
                        Json.readStringList(rs.getString("approve_extra_roles_json")),
                        rs.getInt("deny_dm_enabled") != 0,
                        rs.getString("deny_dm_template"),
                        DenyAction.fromWire(rs.getString("deny_action"))
                );
                LandingConfig landing = new LandingConfig(
                        rs.getString("landing_mode"),
                        rs.getString("landing_redirect_url"),
                        rs.getString("landing_server_name"),
                        rs.getString("landing_server_address"),
                        rs.getString("landing_tagline"),
                        rs.getString("landing_join_url"),
                        rs.getInt("landing_map_enabled") != 0,
                        rs.getString("landing_map_provider"),
                        rs.getString("landing_map_url"),
                        rs.getString("landing_map_label"),
                        rs.getString("landing_brand_image_url"),
                        rs.getString("landing_hero_image_url"),
                        rs.getString("landing_accent_color"),
                        rs.getString("landing_papi_players_online"),
                        rs.getString("landing_papi_discord_members"),
                        rs.getString("landing_stat_players_label"),
                        rs.getString("landing_stat_members_label"),
                        rs.getString("landing_google_analytics_id"),
                        rs.getInt("landing_cookie_banner") != 0,
                        rs.getInt("landing_leaderboard_enabled") != 0,
                        rs.getString("landing_leaderboard_title"),
                        rs.getString("landing_leaderboard_description"),
                        rs.getInt("landing_leaderboard_top_n"),
                        rs.getString("landing_leaderboard_label"),
                        rs.getString("landing_promo_video_url"),
                        Json.readFeatureList(rs.getString("landing_features_json")),
                        Json.readFaqList(rs.getString("landing_faqs_json"))
                );
                return new Settings(
                        rs.getString("rules_markdown"),
                        rs.getString("gated_role_id"),
                        rs.getString("full_role_id"),
                        rs.getString("mod_role_id"),
                        rs.getString("config_admin_role_id"),
                        rs.getString("web_manager_role_id"),
                        rs.getString("welcome_channel_id"),
                        rs.getString("mod_review_channel_id"),
                        rs.getString("llm_system_prompt"),
                        rs.getInt("llm_auto_approve_threshold_x1000") / 1000.0,
                        rs.getInt("llm_auto_deny_threshold_x1000") / 1000.0,
                        rs.getInt("llm_auto_deny_enabled") != 0,
                        rs.getString("llm_api_key"),
                        rs.getString("llm_base_url"),
                        rs.getString("llm_model"),
                        rs.getInt("geoip_enabled") != 0,
                        rs.getString("geoip_license_key"),
                        flow,
                        landing
                );
            }
        }
    }

    public void save(Settings s) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(UPDATE_SQL)) {
            int i = 1;
            ps.setString(i++, s.rulesMarkdown());
            ps.setString(i++, s.gatedRoleId());
            ps.setString(i++, s.fullRoleId());
            ps.setString(i++, s.modRoleId());
            ps.setString(i++, s.configAdminRoleId() == null ? "" : s.configAdminRoleId());
            ps.setString(i++, s.webManagerRoleId() == null ? "" : s.webManagerRoleId());
            ps.setString(i++, s.welcomeChannelId());
            ps.setString(i++, s.modReviewChannelId());
            ps.setString(i++, s.llmSystemPrompt());
            ps.setInt(i++, clamp1000(s.llmAutoApproveThreshold()));
            ps.setInt(i++, clamp1000(s.llmAutoDenyThreshold()));
            ps.setInt(i++, s.llmAutoDenyEnabled() ? 1 : 0);
            ps.setString(i++, s.llmApiKey() == null ? "" : s.llmApiKey());
            ps.setString(i++, s.llmBaseUrl() == null ? "" : s.llmBaseUrl());
            ps.setString(i++, s.llmModel() == null ? "" : s.llmModel());
            ps.setInt(i++, s.geoipEnabled() ? 1 : 0);
            ps.setString(i++, s.geoipLicenseKey() == null ? "" : s.geoipLicenseKey());
            FlowConfig f = s.flow();
            ps.setInt(i++, f.deliveryViaDm() ? 1 : 0);
            ps.setInt(i++, f.deliveryViaChannel() ? 1 : 0);
            ps.setString(i++, f.deliveryChannelId());
            ps.setString(i++, f.deliveryMessageTemplate());
            ps.setInt(i++, f.entryViaDiscordButton() ? 1 : 0);
            ps.setInt(i++, f.entryViaWebCode() ? 1 : 0);
            ps.setInt(i++, f.entryViaWebOauth() ? 1 : 0);
            ps.setInt(i++, f.gatingEnabled() ? 1 : 0);
            ps.setString(i++, f.triageMode().wire());
            ps.setInt(i++, f.approveDmEnabled() ? 1 : 0);
            ps.setString(i++, f.approveDmTemplate());
            ps.setInt(i++, f.approveChannelAnnounce() ? 1 : 0);
            ps.setString(i++, f.approveChannelTemplate());
            ps.setString(i++, Json.writeStringList(f.approveExtraRoles()));
            ps.setInt(i++, f.denyDmEnabled() ? 1 : 0);
            ps.setString(i++, f.denyDmTemplate());
            ps.setString(i++, f.denyAction().wire());
            LandingConfig l = s.landing();
            ps.setString(i++, l.mode() == null ? "enabled" : l.mode());
            ps.setString(i++, l.redirectUrl() == null ? "" : l.redirectUrl());
            ps.setString(i++, l.serverName() == null ? "" : l.serverName());
            ps.setString(i++, l.serverAddress() == null ? "" : l.serverAddress());
            ps.setString(i++, l.tagline() == null ? "" : l.tagline());
            ps.setString(i++, l.joinUrl() == null ? "" : l.joinUrl());
            ps.setInt(i++, l.mapEnabled() ? 1 : 0);
            ps.setString(i++, l.mapProvider() == null ? "" : l.mapProvider());
            ps.setString(i++, l.mapUrl() == null ? "" : l.mapUrl());
            ps.setString(i++, l.mapLabel() == null ? "" : l.mapLabel());
            ps.setString(i++, l.brandImageUrl() == null ? "" : l.brandImageUrl());
            ps.setString(i++, l.heroImageUrl() == null ? "" : l.heroImageUrl());
            ps.setString(i++, l.accentColor() == null ? "" : l.accentColor());
            ps.setString(i++, l.papiPlayersOnline() == null ? "" : l.papiPlayersOnline());
            ps.setString(i++, l.papiDiscordMembers() == null ? "" : l.papiDiscordMembers());
            ps.setString(i++, l.statPlayersLabel() == null ? "" : l.statPlayersLabel());
            ps.setString(i++, l.statMembersLabel() == null ? "" : l.statMembersLabel());
            ps.setString(i++, l.googleAnalyticsId() == null ? "" : l.googleAnalyticsId());
            ps.setInt(i++, l.cookieBannerEnabled() ? 1 : 0);
            ps.setInt(i++, l.leaderboardEnabled() ? 1 : 0);
            ps.setString(i++, l.leaderboardTitle() == null ? "" : l.leaderboardTitle());
            ps.setString(i++, l.leaderboardDescription() == null ? "" : l.leaderboardDescription());
            ps.setInt(i++, l.leaderboardTopN() <= 0 ? 25 : Math.min(l.leaderboardTopN(), 200));
            ps.setString(i++, l.leaderboardLabel() == null ? "" : l.leaderboardLabel());
            ps.setString(i++, l.promoVideoUrl() == null ? "" : l.promoVideoUrl());
            ps.setString(i++, Json.writeFeatureList(l.features()));
            ps.setString(i++, Json.writeFaqList(l.faqs()));
            int n = ps.executeUpdate();
            if (n == 0) {
                // Row 1 missing - insert and retry.
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO settings (id) VALUES (1)")) {
                    ins.executeUpdate();
                }
                save(s);
            }
        }
    }

    private static int clamp1000(double v) {
        double clamped = Math.max(0.0, Math.min(1.0, v));
        return (int) Math.round(clamped * 1000);
    }
}
