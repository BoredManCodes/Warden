package io.warden.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.config.WardenConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Discord OAuth2 code-grant flow. No external library - straight Java HttpClient.
 *
 * Scopes used: identify + guilds.members.read (so we can read the user's role
 * list in our configured guild to determine mod-ness).
 */
public final class DiscordOAuth {

    public static final String SCOPES = "identify guilds.members.read";
    public static final String CALLBACK_PATH = "/auth/discord/callback";
    public static final long STATE_MAX_AGE_SECONDS = 600;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTH_BASE  = "https://discord.com/api/oauth2/authorize";
    private static final String TOKEN_URL  = "https://discord.com/api/oauth2/token";
    private static final String USER_URL   = "https://discord.com/api/users/@me";
    private static final String GUILD_MEMBER_URL = "https://discord.com/api/users/@me/guilds/%s/member";

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final SecureRandom RAND = new SecureRandom();

    private final WardenConfig config;
    private final HttpClient http;
    private final byte[] signingKey;

    public DiscordOAuth(WardenConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String secret = config.webSessionSecret();
        this.signingKey = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build a self-signed OAuth state value that embeds the `next` redirect
     * target and an HMAC over it. No cookie needed on the way to Discord, no
     * cookie needed on the way back. The value is opaque to the user.
     *
     * Layout: base64url({nonce, iat, next}) + "." + base64url(hmac-sha256(payload))
     */
    public String signedState(String next) {
        ObjectNode body = MAPPER.createObjectNode();
        byte[] nonceBytes = new byte[12];
        RAND.nextBytes(nonceBytes);
        body.put("n", ENC.encodeToString(nonceBytes));
        body.put("iat", System.currentTimeMillis() / 1000);
        body.put("next", next == null ? "" : next);
        String payload;
        try { payload = MAPPER.writeValueAsString(body); }
        catch (Exception e) { throw new RuntimeException("state encode failed", e); }
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENC.encodeToString(payloadBytes) + "." + ENC.encodeToString(hmac(payloadBytes));
    }

    /**
     * Verify a state value coming back from Discord. Returns the `next` target
     * if the signature checks out and the state is within its max age, else
     * empty. Never throws.
     */
    public Optional<String> verifyState(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        int dot = raw.indexOf('.');
        if (dot < 1 || dot == raw.length() - 1) return Optional.empty();
        try {
            byte[] payloadBytes = DEC.decode(raw.substring(0, dot));
            byte[] sigBytes = DEC.decode(raw.substring(dot + 1));
            byte[] expected = hmac(payloadBytes);
            if (!constantTimeEquals(sigBytes, expected)) return Optional.empty();
            JsonNode n = MAPPER.readTree(payloadBytes);
            long iat = n.path("iat").asLong(0);
            long ageSec = (System.currentTimeMillis() / 1000) - iat;
            if (ageSec < 0 || ageSec > STATE_MAX_AGE_SECONDS) return Optional.empty();
            String next = n.path("next").asText("/dash");
            if (!next.startsWith("/")) next = "/dash";
            return Optional.of(next);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
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

    public String authorizeUrl(String state) {
        String redirect = config.webPublicUrl() + CALLBACK_PATH;
        return AUTH_BASE
                + "?response_type=code"
                + "&client_id=" + enc(config.discordClientId())
                + "&scope=" + enc(SCOPES)
                + "&state=" + enc(state)
                + "&redirect_uri=" + enc(redirect)
                + "&prompt=consent";
    }

    public record AuthedUser(String discordId, String username, String displayName,
                             String avatar, boolean mod,
                             boolean configAdmin, boolean webManager,
                             List<String> roleIds) {
        public AuthedUser(String discordId, String username, String displayName,
                          String avatar, boolean mod) {
            this(discordId, username, displayName, avatar, mod, false, false, List.of());
        }
        public AuthedUser(String discordId, String username, String displayName,
                          String avatar, boolean mod,
                          boolean configAdmin, boolean webManager) {
            this(discordId, username, displayName, avatar, mod, configAdmin, webManager, List.of());
        }
    }

    /**
     * Three-role variant. {@code modRoleId} gates mod surfaces; {@code configAdminRoleId}
     * grants full /dash/config access; {@code webManagerRoleId} grants only the landing
     * sub-section of config. All three are looked up against the user's guild roles.
     */
    public AuthedUser completeOAuth(String code, String modRoleId,
                                    String configAdminRoleId, String webManagerRoleId) throws Exception {
        return completeOAuthInternal(code, modRoleId, configAdminRoleId, webManagerRoleId);
    }

    /** Back-compat single-role variant: only the mod role is honoured. */
    public AuthedUser completeOAuth(String code, String modRoleId) throws Exception {
        return completeOAuthInternal(code, modRoleId, "", "");
    }

    private AuthedUser completeOAuthInternal(String code, String modRoleId,
                                             String configAdminRoleId, String webManagerRoleId) throws Exception {
        String redirect = config.webPublicUrl() + CALLBACK_PATH;
        String form = "client_id=" + enc(config.discordClientId())
                + "&client_secret=" + enc(config.discordClientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirect);

        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        if (tokenRes.statusCode() < 200 || tokenRes.statusCode() >= 300) {
            throw new RuntimeException("token exchange failed " + tokenRes.statusCode() + ": " + tokenRes.body());
        }
        JsonNode tokenJson = MAPPER.readTree(tokenRes.body());
        String accessToken = tokenJson.path("access_token").asText("");
        if (accessToken.isBlank()) throw new RuntimeException("no access_token in token response");

        HttpRequest userReq = HttpRequest.newBuilder()
                .uri(URI.create(USER_URL))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> userRes = http.send(userReq, HttpResponse.BodyHandlers.ofString());
        if (userRes.statusCode() != 200) {
            throw new RuntimeException("users/@me failed " + userRes.statusCode() + ": " + userRes.body());
        }
        JsonNode userJson = MAPPER.readTree(userRes.body());
        String discordId = userJson.path("id").asText("");
        String username = userJson.path("username").asText("");
        // Discord's "global_name" is the display name with original casing the
        // user picked. Older accounts may not have one - fall back to username.
        String displayName = userJson.path("global_name").asText("");
        if (displayName == null || displayName.isBlank()) displayName = username;
        String avatar = userJson.path("avatar").asText("");
        if (discordId.isBlank()) throw new RuntimeException("no user id in response");

        boolean mod = false;
        boolean configAdmin = false;
        boolean webManager = false;
        List<String> roleIds = List.of();
        if (!config.discordGuildId().isBlank()) {
            HttpRequest memberReq = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(GUILD_MEMBER_URL, config.discordGuildId())))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> memberRes = http.send(memberReq, HttpResponse.BodyHandlers.ofString());
            if (memberRes.statusCode() == 200) {
                JsonNode memberJson = MAPPER.readTree(memberRes.body());
                JsonNode rolesNode = memberJson.path("roles");
                List<String> roles = new java.util.ArrayList<>();
                if (rolesNode.isArray()) for (JsonNode r : rolesNode) roles.add(r.asText());
                roleIds = java.util.Collections.unmodifiableList(roles);
                String effectiveModRole = modRoleId == null || modRoleId.isBlank()
                        ? config.bootstrapModRoleId() : modRoleId;
                if (effectiveModRole != null && !effectiveModRole.isBlank()
                        && roles.contains(effectiveModRole)) {
                    mod = true;
                }
                if (configAdminRoleId != null && !configAdminRoleId.isBlank()
                        && roles.contains(configAdminRoleId)) {
                    configAdmin = true;
                }
                if (webManagerRoleId != null && !webManagerRoleId.isBlank()
                        && roles.contains(webManagerRoleId)) {
                    webManager = true;
                }
                // Note: Discord doesn't return permissions on this endpoint; ADMINISTRATOR check
                // happens server-side in JDA for the in-Discord button path (ModButtonListener).
            }
            // 404 → user not in the guild; all flags stay false.
        }
        return new AuthedUser(discordId, username, displayName, avatar, mod, configAdmin, webManager, roleIds);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
