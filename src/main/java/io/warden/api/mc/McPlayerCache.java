package io.warden.api.mc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.data.Json;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the plugins/Warden/playerdata/ directory. One JSON file per username,
 * written on PlayerQuitEvent and read back when a request lands for an
 * offline player. The on-disk format is the same payload produced by
 * {@link McPlayerSnapshot}.
 */
public final class McPlayerCache {

    private final Path dir;
    private final Logger log;

    public McPlayerCache(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    public Path dir() { return dir; }

    public void saveOnQuit(Player player) {
        ensureDir();
        ObjectNode obj = McPlayerSnapshot.forQuittingPlayer(player);
        Path file = dir.resolve(safeFileName(player.getName()) + ".json");
        try {
            Files.writeString(file, obj.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to write cached player data for "
                    + player.getName() + ": " + e.getMessage());
        }
    }

    public Optional<JsonNode> read(String username) {
        Path file = dir.resolve(safeFileName(username) + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(Json.MAPPER.readTree(body));
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to read cached player data for "
                    + username + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private void ensureDir() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not create playerdata dir at " + dir + ": " + e.getMessage());
        }
    }

    private static String safeFileName(String raw) {
        if (raw == null) return "_";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }
}
