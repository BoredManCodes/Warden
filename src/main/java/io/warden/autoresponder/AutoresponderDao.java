package io.warden.autoresponder;

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

public final class AutoresponderDao {

    private final Database db;

    public AutoresponderDao(Database db) { this.db = db; }

    private static final String SELECT_COLS =
            "id, name, enabled, match_mode, pattern, case_insensitive, response_mode, " +
            "content, embed_title, embed_description, embed_color, embed_image_url, " +
            "embed_thumbnail_url, embed_author_name, embed_author_icon, embed_footer_text, " +
            "embed_footer_icon, extra_image_urls, allow_channel_ids, deny_channel_ids, " +
            "allow_role_ids, deny_role_ids, cooldown_seconds, reply_to_trigger, delete_trigger, " +
            "mention_author, priority, created_at, updated_at";

    public List<Autoresponder> listAll() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + SELECT_COLS + " FROM autoresponders " +
                             "ORDER BY priority DESC, id ASC");
             ResultSet rs = ps.executeQuery()) {
            List<Autoresponder> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public Optional<Autoresponder> find(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + SELECT_COLS + " FROM autoresponders WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public long create(Autoresponder a) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO autoresponders (name, enabled, match_mode, pattern, " +
                             "case_insensitive, response_mode, content, embed_title, " +
                             "embed_description, embed_color, embed_image_url, embed_thumbnail_url, " +
                             "embed_author_name, embed_author_icon, embed_footer_text, embed_footer_icon, " +
                             "extra_image_urls, allow_channel_ids, deny_channel_ids, allow_role_ids, " +
                             "deny_role_ids, cooldown_seconds, reply_to_trigger, delete_trigger, " +
                             "mention_author, priority, created_at, updated_at) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            bindWritable(ps, a, now, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    public void update(Autoresponder a) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE autoresponders SET name = ?, enabled = ?, match_mode = ?, pattern = ?, " +
                             "case_insensitive = ?, response_mode = ?, content = ?, embed_title = ?, " +
                             "embed_description = ?, embed_color = ?, embed_image_url = ?, embed_thumbnail_url = ?, " +
                             "embed_author_name = ?, embed_author_icon = ?, embed_footer_text = ?, embed_footer_icon = ?, " +
                             "extra_image_urls = ?, allow_channel_ids = ?, deny_channel_ids = ?, allow_role_ids = ?, " +
                             "deny_role_ids = ?, cooldown_seconds = ?, reply_to_trigger = ?, delete_trigger = ?, " +
                             "mention_author = ?, priority = ?, updated_at = ? WHERE id = ?")) {
            int idx = bindFields(ps, a);
            ps.setLong(idx++, now);
            ps.setLong(idx, a.id());
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM autoresponders WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void setEnabled(long id, boolean enabled) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE autoresponders SET enabled = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private static void bindWritable(PreparedStatement ps, Autoresponder a, long createdAt, long updatedAt)
            throws SQLException {
        int idx = bindFields(ps, a);
        ps.setLong(idx++, createdAt);
        ps.setLong(idx, updatedAt);
    }

    /**
     * Bind every non-id, non-timestamp column in the canonical column order so
     * both INSERT and UPDATE can share the same setter sequence.
     * Returns the next free parameter index after the last bound field.
     */
    private static int bindFields(PreparedStatement ps, Autoresponder a) throws SQLException {
        int i = 1;
        ps.setString(i++, a.name() == null ? "" : a.name());
        ps.setInt(i++, a.enabled() ? 1 : 0);
        ps.setString(i++, a.matchMode());
        ps.setString(i++, a.pattern() == null ? "" : a.pattern());
        ps.setInt(i++, a.caseInsensitive() ? 1 : 0);
        ps.setString(i++, a.responseMode());
        ps.setString(i++, a.content() == null ? "" : a.content());
        ps.setString(i++, a.embedTitle() == null ? "" : a.embedTitle());
        ps.setString(i++, a.embedDescription() == null ? "" : a.embedDescription());
        ps.setString(i++, a.embedColor() == null ? "" : a.embedColor());
        ps.setString(i++, a.embedImageUrl() == null ? "" : a.embedImageUrl());
        ps.setString(i++, a.embedThumbnailUrl() == null ? "" : a.embedThumbnailUrl());
        ps.setString(i++, a.embedAuthorName() == null ? "" : a.embedAuthorName());
        ps.setString(i++, a.embedAuthorIcon() == null ? "" : a.embedAuthorIcon());
        ps.setString(i++, a.embedFooterText() == null ? "" : a.embedFooterText());
        ps.setString(i++, a.embedFooterIcon() == null ? "" : a.embedFooterIcon());
        ps.setString(i++, Json.writeStringList(a.extraImageUrls()));
        ps.setString(i++, Json.writeStringList(a.allowChannelIds()));
        ps.setString(i++, Json.writeStringList(a.denyChannelIds()));
        ps.setString(i++, Json.writeStringList(a.allowRoleIds()));
        ps.setString(i++, Json.writeStringList(a.denyRoleIds()));
        ps.setInt(i++, a.cooldownSeconds());
        ps.setInt(i++, a.replyToTrigger() ? 1 : 0);
        ps.setInt(i++, a.deleteTrigger() ? 1 : 0);
        ps.setInt(i++, a.mentionAuthor() ? 1 : 0);
        ps.setInt(i++, a.priority());
        return i;
    }

    private static Autoresponder map(ResultSet rs) throws SQLException {
        return new Autoresponder(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("enabled") != 0,
                rs.getString("match_mode"),
                rs.getString("pattern"),
                rs.getInt("case_insensitive") != 0,
                rs.getString("response_mode"),
                rs.getString("content"),
                rs.getString("embed_title"),
                rs.getString("embed_description"),
                rs.getString("embed_color"),
                rs.getString("embed_image_url"),
                rs.getString("embed_thumbnail_url"),
                rs.getString("embed_author_name"),
                rs.getString("embed_author_icon"),
                rs.getString("embed_footer_text"),
                rs.getString("embed_footer_icon"),
                Json.readStringList(rs.getString("extra_image_urls")),
                Json.readStringList(rs.getString("allow_channel_ids")),
                Json.readStringList(rs.getString("deny_channel_ids")),
                Json.readStringList(rs.getString("allow_role_ids")),
                Json.readStringList(rs.getString("deny_role_ids")),
                rs.getInt("cooldown_seconds"),
                rs.getInt("reply_to_trigger") != 0,
                rs.getInt("delete_trigger") != 0,
                rs.getInt("mention_author") != 0,
                rs.getInt("priority"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
