package io.warden.tickets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny client for posting messages to a Discord webhook URL. Used by the ticket
 * mirror feature so messages appear in the channel under each author's original
 * name/avatar instead of all being attributed to the bot.
 */
public final class WebhookPoster {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private WebhookPoster() {}

    public static String webhookUrl(String webhookId, String webhookToken) {
        return "https://discord.com/api/v10/webhooks/" + webhookId + "/" + webhookToken;
    }

    /** Returns true if the post returned a 2xx, else false (logged). */
    public static boolean send(String webhookUrl, String username, String avatarUrl,
                               String content, Logger log) {
        if (webhookUrl == null || webhookUrl.isBlank()) return false;
        String trimmedContent = content;
        if (trimmedContent != null && trimmedContent.length() > 1990) {
            trimmedContent = trimmedContent.substring(0, 1990) + "...";
        }
        try {
            ObjectNode body = JSON.createObjectNode();
            if (username != null && !username.isBlank()) {
                body.put("username", clampUsername(username));
            }
            if (avatarUrl != null && !avatarUrl.isBlank()) {
                body.put("avatar_url", avatarUrl);
            }
            body.put("content", trimmedContent == null || trimmedContent.isBlank() ? "(no text)" : trimmedContent);
            ObjectNode allowed = JSON.createObjectNode();
            allowed.putArray("parse");
            body.set("allowed_mentions", allowed);

            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl + "?wait=true"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return true;
            log.warning("webhook post non-2xx (" + code + "): " + resp.body());
            return false;
        } catch (Exception e) {
            log.log(Level.WARNING, "webhook post failed", e);
            return false;
        }
    }

    public record FilePart(String name, Path path) {}

    /**
     * Multipart variant: posts {@code content} plus one or more local files as
     * Discord attachments. Files larger than {@code maxBytesPerFile} are
     * skipped (with their names appended to content as a fallback).
     */
    public static boolean sendWithFiles(String webhookUrl, String username, String avatarUrl,
                                        String content, List<FilePart> files,
                                        long maxBytesPerFile, Logger log) {
        if (webhookUrl == null || webhookUrl.isBlank()) return false;
        if (files == null || files.isEmpty()) {
            return send(webhookUrl, username, avatarUrl, content, log);
        }
        try {
            StringBuilder fallback = new StringBuilder(content == null ? "" : content);
            java.util.List<FilePart> accepted = new java.util.ArrayList<>();
            for (FilePart fp : files) {
                if (fp == null || fp.path() == null) continue;
                if (!Files.exists(fp.path())) {
                    if (fallback.length() > 0) fallback.append("\n");
                    fallback.append("*[missing attachment: ").append(fp.name()).append("]*");
                    continue;
                }
                long size;
                try { size = Files.size(fp.path()); } catch (IOException ioe) { size = -1; }
                if (maxBytesPerFile > 0 && size > maxBytesPerFile) {
                    if (fallback.length() > 0) fallback.append("\n");
                    fallback.append("*[oversize attachment: ").append(fp.name()).append("]*");
                    continue;
                }
                accepted.add(fp);
            }
            if (accepted.isEmpty()) {
                return send(webhookUrl, username, avatarUrl, fallback.toString(), log);
            }

            String trimmedContent = fallback.toString();
            if (trimmedContent.length() > 1990) {
                trimmedContent = trimmedContent.substring(0, 1990) + "...";
            }

            ObjectNode payload = JSON.createObjectNode();
            if (username != null && !username.isBlank()) {
                payload.put("username", clampUsername(username));
            }
            if (avatarUrl != null && !avatarUrl.isBlank()) {
                payload.put("avatar_url", avatarUrl);
            }
            if (trimmedContent.isBlank()) trimmedContent = "";
            payload.put("content", trimmedContent);
            ObjectNode allowed = JSON.createObjectNode();
            allowed.putArray("parse");
            payload.set("allowed_mentions", allowed);
            ArrayNode attachmentsMeta = JSON.createArrayNode();
            for (int i = 0; i < accepted.size(); i++) {
                ObjectNode entry = JSON.createObjectNode();
                entry.put("id", i);
                entry.put("filename", accepted.get(i).name());
                attachmentsMeta.add(entry);
            }
            payload.set("attachments", attachmentsMeta);

            String boundary = "----warden-" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeBoundary(body, boundary, false);
            body.write(("Content-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(JSON.writeValueAsBytes(payload));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < accepted.size(); i++) {
                FilePart fp = accepted.get(i);
                writeBoundary(body, boundary, false);
                String disp = "Content-Disposition: form-data; name=\"files[" + i + "]\"; filename=\""
                        + escapeQuotes(fp.name()) + "\"\r\n"
                        + "Content-Type: " + guessContentType(fp.name()) + "\r\n\r\n";
                body.write(disp.getBytes(StandardCharsets.UTF_8));
                body.write(Files.readAllBytes(fp.path()));
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            writeBoundary(body, boundary, true);

            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl + "?wait=true"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) return true;
            log.warning("webhook multipart post non-2xx (" + code + "): " + resp.body());
            return false;
        } catch (Exception e) {
            log.log(Level.WARNING, "webhook multipart post failed", e);
            return false;
        }
    }

    private static void writeBoundary(ByteArrayOutputStream out, String boundary, boolean closing) throws IOException {
        String line = "--" + boundary + (closing ? "--\r\n" : "\r\n");
        out.write(line.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeQuotes(String s) {
        return s == null ? "file" : s.replace("\"", "\\\"");
    }

    private static String guessContentType(String name) {
        String guess = java.net.URLConnection.guessContentTypeFromName(name == null ? "" : name);
        return guess == null ? "application/octet-stream" : guess;
    }

    /** Discord usernames are capped at 80 chars and must not contain certain substrings. */
    private static String clampUsername(String s) {
        String trimmed = s.trim();
        if (trimmed.isBlank()) trimmed = "Ticket";
        // Discord rejects names containing "discord", "@", "#", ":", "```", "everyone", "here".
        trimmed = trimmed.replace("@", "(at)")
                .replace("#", "(hash)")
                .replace(":", "(colon)")
                .replace("```", "'''");
        if (trimmed.equalsIgnoreCase("discord") || trimmed.toLowerCase().contains("discord")) {
            trimmed = trimmed.replaceAll("(?i)discord", "Disc0rd");
        }
        if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80);
        return trimmed;
    }
}
