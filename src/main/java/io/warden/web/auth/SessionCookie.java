package io.warden.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Tiny HMAC-signed session cookie. Payload is JSON
 * {did, name, gn, av, mod, cfg, web, own, rs, iat}.
 *
 * Layout: base64url(payload).base64url(hmac-sha256(payload))
 *
 * Cheap to encode/verify, no external dependency, opaque to the user.
 * {@code gn} is the Discord global (display) name; {@code av} is the avatar hash.
 * {@code cfg}/{@code web}/{@code own} are the access-control flags
 * (config admin / web manager / guild owner-or-administrator). {@code rs} is the
 * member's full list of role IDs (used by per-page permissions). All fields are
 * optional - cookies issued before they were added still decode fine.
 */
public final class SessionCookie {

    public static final String COOKIE_NAME = "warden_session";
    public static final int MAX_AGE_SECONDS = 60 * 60 * 24 * 7; // 1 week

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    public record Session(String discordId, String username, String displayName,
                          String avatar, boolean mod,
                          boolean configAdmin, boolean webManager, boolean owner,
                          List<String> roleIds,
                          long issuedAt) {
        public Session(String discordId, String username, boolean mod, long issuedAt) {
            this(discordId, username, "", "", mod, false, false, false, List.of(), issuedAt);
        }
        public Session(String discordId, String username, String displayName,
                       String avatar, boolean mod, long issuedAt) {
            this(discordId, username, displayName, avatar, mod, false, false, false, List.of(), issuedAt);
        }
        public Session(String discordId, String username, String displayName,
                       String avatar, boolean mod,
                       boolean configAdmin, boolean webManager, boolean owner,
                       long issuedAt) {
            this(discordId, username, displayName, avatar, mod,
                    configAdmin, webManager, owner, List.of(), issuedAt);
        }

        /** True if the user has any role that gates access to the dashboard. */
        public boolean anyDashAccess() { return mod || configAdmin || webManager || owner; }

        /** True if the user is allowed to view/edit the full Config tab set. */
        public boolean canEditConfig() { return owner || configAdmin; }

        /** True if the user is allowed to view/edit the Landing tab. */
        public boolean canEditLanding() { return owner || configAdmin || webManager; }

        /** Non-null role ID list; empty when no roles are tracked. */
        public List<String> roleIdsOrEmpty() {
            return roleIds == null ? List.of() : roleIds;
        }
    }

    private final byte[] secret;

    public SessionCookie(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("session_secret required for cookie signing");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(Session s) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("did", s.discordId());
        body.put("name", s.username() == null ? "" : s.username());
        body.put("gn", s.displayName() == null ? "" : s.displayName());
        body.put("av", s.avatar() == null ? "" : s.avatar());
        body.put("mod", s.mod());
        body.put("cfg", s.configAdmin());
        body.put("web", s.webManager());
        body.put("own", s.owner());
        ArrayNode rs = body.putArray("rs");
        for (String rid : s.roleIdsOrEmpty()) {
            if (rid != null && !rid.isBlank()) rs.add(rid);
        }
        body.put("iat", s.issuedAt());
        String payload;
        try { payload = MAPPER.writeValueAsString(body); }
        catch (Exception e) { throw new RuntimeException("cookie encode failed", e); }
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String pB64 = ENC.encodeToString(payloadBytes);
        String sigB64 = ENC.encodeToString(hmac(payloadBytes));
        return pB64 + "." + sigB64;
    }

    public Optional<Session> decode(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        int dot = raw.indexOf('.');
        if (dot < 1 || dot == raw.length() - 1) return Optional.empty();
        try {
            byte[] payloadBytes = DEC.decode(raw.substring(0, dot));
            byte[] sigBytes = DEC.decode(raw.substring(dot + 1));
            byte[] expectedSig = hmac(payloadBytes);
            if (!constantTimeEquals(sigBytes, expectedSig)) return Optional.empty();
            JsonNode n = MAPPER.readTree(payloadBytes);
            List<String> roles = new ArrayList<>();
            JsonNode rsNode = n.path("rs");
            if (rsNode.isArray()) {
                for (JsonNode r : rsNode) {
                    String rid = r.asText("");
                    if (!rid.isBlank()) roles.add(rid);
                }
            }
            return Optional.of(new Session(
                    n.path("did").asText(""),
                    n.path("name").asText(""),
                    n.path("gn").asText(""),
                    n.path("av").asText(""),
                    n.path("mod").asBoolean(false),
                    n.path("cfg").asBoolean(false),
                    n.path("web").asBoolean(false),
                    n.path("own").asBoolean(false),
                    Collections.unmodifiableList(roles),
                    n.path("iat").asLong(0L)
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("hmac failed", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
