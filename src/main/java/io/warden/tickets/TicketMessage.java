package io.warden.tickets;

import java.util.ArrayList;
import java.util.List;

public record TicketMessage(
        long id,
        long ticketId,
        String authorKind,
        String authorId,
        String authorName,
        String body,
        String attachmentsJson,
        long createdAt
) {
    public static final String KIND_USER = "user";
    public static final String KIND_STAFF = "staff";
    public static final String KIND_SYSTEM = "system";
    /** Staff-only commentary. Never delivered to the reporter and excluded from transcripts. */
    public static final String KIND_INTERNAL = "internal";

    public boolean isInternal() {
        return KIND_INTERNAL.equals(authorKind);
    }

    /** A single attachment stored alongside a ticket message. */
    public record Attachment(String name, String storedPath, String discordUrl, long size) {}

    /** Parse the JSON-encoded attachments column into a usable list. Tolerates malformed input. */
    public List<Attachment> attachments() {
        return parseAttachments(attachmentsJson);
    }

    public static List<Attachment> parseAttachments(String json) {
        List<Attachment> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);
            if (!root.isArray()) return out;
            for (com.fasterxml.jackson.databind.JsonNode n : root) {
                out.add(new Attachment(
                        n.path("name").asText(""),
                        n.path("storedPath").asText(""),
                        n.path("discordUrl").asText(""),
                        n.path("size").asLong(0)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static String encodeAttachments(List<Attachment> atts) {
        if (atts == null || atts.isEmpty()) return "";
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
            for (Attachment a : atts) {
                com.fasterxml.jackson.databind.node.ObjectNode o = om.createObjectNode();
                o.put("name", a.name() == null ? "" : a.name());
                o.put("storedPath", a.storedPath() == null ? "" : a.storedPath());
                o.put("discordUrl", a.discordUrl() == null ? "" : a.discordUrl());
                o.put("size", a.size());
                arr.add(o);
            }
            return om.writeValueAsString(arr);
        } catch (Exception e) {
            return "";
        }
    }
}
