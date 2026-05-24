package io.warden.discord;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflection-based glue to DiscordSRV's account link manager.
 *
 * DiscordSRV is treated as a soft-dependency (declared in plugin.yml) - we
 * never import its classes directly, so Warden still loads and runs cleanly
 * on servers without it.
 *
 * Looked up via reflection per call so install/uninstall during runtime
 * (e.g., {@code /reload}) doesn't poison a cached method handle.
 */
public final class DiscordSrvBridge {

    private static final String MAIN_CLASS = "github.scarsz.discordsrv.DiscordSRV";
    private static final String PLUGIN_NAME = "DiscordSRV";

    private final Logger log;

    public DiscordSrvBridge(Logger log) {
        this.log = log;
    }

    /** True if DiscordSRV is currently enabled on the server. */
    public boolean isPresent() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Discord snowflake id linked to this Minecraft UUID, if any. */
    public Optional<String> discordIdFor(UUID uuid) {
        if (uuid == null || !isPresent()) return Optional.empty();
        return invokeLink("getDiscordId", UUID.class, uuid)
                .map(o -> (o instanceof String s && !s.isBlank()) ? s : null);
    }

    /** Minecraft UUID linked to this Discord snowflake id, if any. */
    public Optional<UUID> uuidFor(String discordId) {
        if (discordId == null || discordId.isBlank() || !isPresent()) return Optional.empty();
        return invokeLink("getUuid", String.class, discordId)
                .map(o -> (o instanceof UUID u) ? u : null);
    }

    /**
     * Minecraft username linked to this Discord snowflake id, if any.
     * Resolves Discord id → UUID via DiscordSRV, then UUID → name via Bukkit's
     * offline-player cache. Returns empty when no link exists or DiscordSRV
     * isn't installed.
     */
    public Optional<String> mcNameFor(String discordId) {
        return uuidFor(discordId).flatMap(this::mcNameForUuid);
    }

    private Optional<String> mcNameForUuid(UUID uuid) {
        if (uuid == null) return Optional.empty();
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (op == null) return Optional.empty();
            String name = op.getName();
            return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
        } catch (Throwable t) {
            log.log(Level.WARNING, "Bukkit uuid → name failed for " + uuid + ": " + t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolve a Minecraft player name to a linked Discord id.
     * Uses Bukkit's offline-player lookup for the name → UUID step; that hits
     * the local user cache and can fall back to Mojang for never-seen names.
     */
    public Optional<String> discordIdForPlayer(String playerName) {
        if (playerName == null || playerName.isBlank() || !isPresent()) return Optional.empty();
        try {
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
            if (op == null) return Optional.empty();
            return discordIdFor(op.getUniqueId());
        } catch (Throwable t) {
            log.log(Level.WARNING, "DiscordSRV name → discord id failed for " + playerName + ": " + t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Detects whether DiscordSRV is configured with the same bot token as
     * Warden. Two plugins sharing one token cannot coexist: Discord allows
     * only one gateway session per token, and slash-command registration is
     * per-application, so they would fight for the connection and overwrite
     * each other's commands.
     *
     * Reads DiscordSRV's config.yml via Bukkit's plugin API (no reflection
     * into DSrv internals, no dependency on its JDA being up yet). Tokens
     * are normalised by stripping any "Bot " prefix and trimming whitespace
     * before comparison.
     *
     * Returns false (no collision detected) when DiscordSRV isn't installed,
     * its config file can't be read, or its BotToken is blank or different.
     * Known limitation: if either side is using an env-var override that
     * differs from its config.yml, this check won't catch the collision -
     * the gateway fight at runtime will be the canary in that case.
     */
    public boolean tokenMatches(String wardenToken) {
        if (wardenToken == null || wardenToken.isBlank()) return false;
        if (!isPresent()) return false;
        try {
            Plugin dsrv = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (dsrv == null) return false;
            File configFile = new File(dsrv.getDataFolder(), "config.yml");
            if (!configFile.isFile()) return false;
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            String dsrvToken = cfg.getString("BotToken", "");
            String a = normaliseToken(dsrvToken);
            String b = normaliseToken(wardenToken);
            return !a.isEmpty() && a.equals(b);
        } catch (Throwable t) {
            log.log(Level.WARNING, "DiscordSRV token-collision check failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Resolve a DiscordSRV channel name (the keys under {@code Channels:} in
     * DSRV's config.yml) to a Discord channel snowflake id. Returns empty when
     * DiscordSRV isn't installed, its config can't be read, or the name isn't
     * mapped.
     *
     * DSRV writes the map as channelName -> channelId; the {@code global} key
     * is the conventional "everything else" channel. We do a case-insensitive
     * lookup so {@code Global}, {@code global}, and {@code GLOBAL} all match.
     */
    public Optional<String> channelIdByDsrvName(String name) {
        if (name == null || name.isBlank() || !isPresent()) return Optional.empty();
        try {
            Plugin dsrv = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (dsrv == null) return Optional.empty();
            File configFile = new File(dsrv.getDataFolder(), "config.yml");
            if (!configFile.isFile()) return Optional.empty();
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            var channels = cfg.getConfigurationSection("Channels");
            if (channels == null) return Optional.empty();
            String wanted = name.trim();
            for (String key : channels.getKeys(false)) {
                if (key.equalsIgnoreCase(wanted)) {
                    String id = channels.getString(key, "");
                    if (id != null && !id.isBlank()) return Optional.of(id.trim());
                }
            }
            return Optional.empty();
        } catch (Throwable t) {
            log.log(Level.WARNING, "DiscordSRV channel-name lookup failed for \"" + name + "\": " + t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Path to the DiscordSRV {@code plugins/DiscordSRV/alerts.yml} file. Returns
     * empty when DiscordSRV isn't installed; the returned path may or may not
     * exist on disk (caller checks).
     */
    public Optional<java.nio.file.Path> alertsFile() {
        try {
            Plugin dsrv = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (dsrv == null) return Optional.empty();
            return Optional.of(new File(dsrv.getDataFolder(), "alerts.yml").toPath());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Read the DiscordSRV alerts.yml, if it exists, as a single string. */
    public Optional<String> readAlertsFile() {
        Optional<java.nio.file.Path> p = alertsFile();
        if (p.isEmpty()) return Optional.empty();
        java.nio.file.Path path = p.get();
        if (!java.nio.file.Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            log.log(Level.WARNING, "Failed to read DiscordSRV alerts.yml: " + t.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Back up the DiscordSRV alerts.yml (rename to
     * {@code alerts.yml.warden-backup-<epochms>}) then write a minimal
     * {@code Alerts: []} placeholder over the original.
     *
     * The backup keeps the operator's original alerts recoverable. The
     * "{@code Alerts: []}" body is the canonical empty form DSRV accepts on
     * {@code /discord reload}, so DSRV will stop firing the old alerts as
     * soon as it re-reads the file.
     *
     * @return the absolute path of the backup file when the swap succeeded,
     *         empty when the file didn't exist, the bridge couldn't write,
     *         or DSRV wasn't installed.
     */
    public Optional<java.nio.file.Path> backupAndClearAlertsFile() {
        Optional<java.nio.file.Path> p = alertsFile();
        if (p.isEmpty()) return Optional.empty();
        java.nio.file.Path path = p.get();
        if (!java.nio.file.Files.isRegularFile(path)) return Optional.empty();
        java.nio.file.Path backup = path.resolveSibling(
                "alerts.yml.warden-backup-" + System.currentTimeMillis());
        try {
            java.nio.file.Files.move(path, backup,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            log.log(Level.WARNING, "Failed to back up DSRV alerts.yml to "
                    + backup + ": " + t.getMessage());
            return Optional.empty();
        }
        String body = "# DiscordSRV alerts cleared by Warden after import.\n"
                + "# Original kept at " + backup.getFileName() + ".\n"
                + "# Run /discord reload in console (or restart) so DSRV re-reads this file.\n"
                + "Alerts: []\n";
        try {
            java.nio.file.Files.writeString(path, body,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Throwable t) {
            log.log(Level.WARNING, "Failed to write empty Alerts: [] to "
                    + path + ": " + t.getMessage());
            // best-effort: try to put the backup back
            try {
                java.nio.file.Files.move(backup, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) {}
            return Optional.empty();
        }
        return Optional.of(backup);
    }

    private static String normaliseToken(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bot ", 0, 4)) t = t.substring(4).trim();
        return t;
    }

    private Optional<Object> invokeLink(String method, Class<?> argType, Object arg) {
        try {
            Class<?> mainClass = Class.forName(MAIN_CLASS);
            Object plugin = mainClass.getMethod("getPlugin").invoke(null);
            if (plugin == null) return Optional.empty();
            Object manager = plugin.getClass().getMethod("getAccountLinkManager").invoke(plugin);
            if (manager == null) return Optional.empty();
            Object result = manager.getClass().getMethod(method, argType).invoke(manager, arg);
            return Optional.ofNullable(result);
        } catch (ClassNotFoundException notLoaded) {
            return Optional.empty();
        } catch (Throwable t) {
            log.log(Level.WARNING, "DiscordSRV " + method + " call failed: " + t.getMessage());
            return Optional.empty();
        }
    }
}
