package io.warden.levels;

import io.warden.data.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class LevelRewardDao {

    public record Reward(int level, String roleId, boolean stack) {}

    public record Multiplier(String kind, String targetId, int multiplier) {}

    private final Database db;

    public LevelRewardDao(Database db) { this.db = db; }

    public List<Reward> listRewards() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT level, role_id, stack FROM level_role_rewards ORDER BY level ASC");
             ResultSet rs = ps.executeQuery()) {
            List<Reward> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Reward(rs.getInt(1), rs.getString(2), rs.getInt(3) != 0));
            }
            return out;
        }
    }

    public void addReward(int level, String roleId, boolean stack) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO level_role_rewards (level, role_id, stack) VALUES (?, ?, ?) " +
                             "ON CONFLICT(level, role_id) DO UPDATE SET stack = excluded.stack")) {
            ps.setInt(1, level);
            ps.setString(2, roleId);
            ps.setInt(3, stack ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void deleteReward(int level, String roleId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM level_role_rewards WHERE level = ? AND role_id = ?")) {
            ps.setInt(1, level);
            ps.setString(2, roleId);
            ps.executeUpdate();
        }
    }

    public List<Multiplier> listMultipliers() throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind, target_id, multiplier FROM level_multipliers ORDER BY kind, target_id");
             ResultSet rs = ps.executeQuery()) {
            List<Multiplier> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Multiplier(rs.getString(1), rs.getString(2), rs.getInt(3)));
            }
            return out;
        }
    }

    public void setMultiplier(String kind, String targetId, int multiplier) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO level_multipliers (kind, target_id, multiplier) VALUES (?, ?, ?) " +
                             "ON CONFLICT(kind, target_id) DO UPDATE SET multiplier = excluded.multiplier")) {
            ps.setString(1, kind);
            ps.setString(2, targetId);
            ps.setInt(3, multiplier);
            ps.executeUpdate();
        }
    }

    public void deleteMultiplier(String kind, String targetId) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM level_multipliers WHERE kind = ? AND target_id = ?")) {
            ps.setString(1, kind);
            ps.setString(2, targetId);
            ps.executeUpdate();
        }
    }
}
