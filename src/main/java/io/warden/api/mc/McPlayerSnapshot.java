package io.warden.api.mc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.data.Json;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * Builds the JSON payload for a Minecraft player snapshot. Same key set used
 * live (for online players) and persisted on quit (for the cached fallback
 * served when the player is offline).
 *
 * Keys: username, uuid, health, food, world, experience, level, deaths, kills,
 * jumps, gamemode, bed (when set), time, death, address, lastJoined, online,
 * location. Numeric Bukkit values are emitted as strings for backward
 * compatibility with existing consumers.
 */
public final class McPlayerSnapshot {

    private McPlayerSnapshot() {}

    public static ObjectNode forOnlinePlayer(Player player) {
        ObjectNode obj = Json.MAPPER.createObjectNode();
        obj.put("username", player.getName());
        obj.put("uuid", player.getUniqueId().toString());
        obj.put("health", String.valueOf(player.getHealth()));
        obj.put("food", String.valueOf(player.getFoodLevel()));
        obj.put("world", player.getWorld() == null ? "" : player.getWorld().getName());
        obj.put("experience", String.valueOf(player.getExp()));
        obj.put("level", String.valueOf(player.getLevel()));
        obj.put("deaths", String.valueOf(safeStat(player, Statistic.DEATHS)));
        obj.put("kills", String.valueOf(safeStat(player, Statistic.MOB_KILLS)));
        obj.put("jumps", String.valueOf(safeStat(player, Statistic.JUMP)));
        obj.put("gamemode", player.getGameMode() == null ? "" : player.getGameMode().toString());

        Location bed = readBedSpawn(player);
        if (bed != null) {
            obj.put("bed", "x=" + bed.getX() + ",y=" + bed.getY() + ",z=" + bed.getZ());
        }

        obj.put("time", String.valueOf(safeStat(player, Statistic.PLAY_ONE_MINUTE) / 20));
        obj.put("death", String.valueOf(safeStat(player, Statistic.TIME_SINCE_DEATH) / 20));
        obj.put("address", readAddress(player));
        @SuppressWarnings("deprecation")
        long lastJoined = player.getLastPlayed();
        obj.put("lastJoined", lastJoined);
        obj.put("online", true);

        Location loc = player.getLocation();
        if (loc != null) {
            obj.put("location", "x=" + loc.getX() + ",y=" + loc.getY() + ",z=" + loc.getZ());
        }
        return obj;
    }

    public static ObjectNode forQuittingPlayer(Player player) {
        ObjectNode obj = forOnlinePlayer(player);
        obj.put("online", false);
        obj.put("lastJoined", System.currentTimeMillis());
        return obj;
    }

    private static int safeStat(Player player, Statistic stat) {
        try {
            return player.getStatistic(stat);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Location readBedSpawn(Player player) {
        try {
            return player.getRespawnLocation();
        } catch (Throwable t) {
            try {
                @SuppressWarnings("deprecation")
                Location legacy = player.getBedSpawnLocation();
                return legacy;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static String readAddress(Player player) {
        try {
            if (player.getAddress() == null || player.getAddress().getAddress() == null) return "";
            return player.getAddress().getAddress().getHostAddress();
        } catch (Throwable t) {
            return "";
        }
    }
}
