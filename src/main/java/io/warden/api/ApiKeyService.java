package io.warden.api;

import io.warden.audit.AuditService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mints and verifies bearer tokens for /api/v1/*.
 *
 * Token shape: {@code WRDN-<24 random chars>.<24 random chars>} - the half before
 * the dot is the public prefix kept in the DB, the half after it is the secret.
 * Only SHA-256(full token) is stored. The plaintext is shown to the operator
 * exactly once at creation time.
 */
public final class ApiKeyService {

    public static final String TOKEN_PREFIX = "WRDN-";
    /** Length of each half of the token's random portion. Base64URL-encoded. */
    private static final int RANDOM_BYTES = 18; // -> 24 base64url chars

    private final ApiKeyDao dao;
    private final AuditService audit;
    private final Logger log;
    private final SecureRandom rng = new SecureRandom();

    public ApiKeyService(ApiKeyDao dao, AuditService audit, Logger log) {
        this.dao = dao;
        this.audit = audit;
        this.log = log;
    }

    public record Created(ApiKey key, String plaintext) {}

    /**
     * Generate a new key. The returned record's plaintext is the only time the
     * full token will exist - callers must surface it to the operator
     * immediately, since the DB only retains the hash.
     */
    public Created create(String label, List<String> scopes, String createdBy) throws SQLException {
        String publicPart = TOKEN_PREFIX + randomPart();
        String secretPart = randomPart();
        String plaintext = publicPart + "." + secretPart;
        String hash = sha256(plaintext);
        long id = dao.create(label, publicPart, hash, scopes, createdBy);
        ApiKey row = dao.findById(id).orElseThrow();
        audit.write(actorOf(createdBy), "api_key_create", createdBy,
                java.util.Map.of(
                        "id", id,
                        "label", safeLabel(label),
                        "prefix", row.prefix(),
                        "scopes", scopes == null ? List.of() : scopes));
        return new Created(row, plaintext);
    }

    public void revoke(long id, String actorDiscordId) {
        try {
            dao.revoke(id);
            audit.write(actorOf(actorDiscordId), "api_key_revoke", actorDiscordId,
                    java.util.Map.of("id", id));
        } catch (SQLException e) {
            log.log(Level.WARNING, "api_key revoke failed for id=" + id + ": " + e.getMessage(), e);
        }
    }

    public void delete(long id, String actorDiscordId) {
        try {
            dao.delete(id);
            audit.write(actorOf(actorDiscordId), "api_key_delete", actorDiscordId,
                    java.util.Map.of("id", id));
        } catch (SQLException e) {
            log.log(Level.WARNING, "api_key delete failed for id=" + id + ": " + e.getMessage(), e);
        }
    }

    private static String actorOf(String discordId) {
        return (discordId == null || discordId.isBlank())
                ? AuditService.ACTOR_SYSTEM
                : AuditService.ACTOR_WEB;
    }

    public List<ApiKey> list() throws SQLException {
        return dao.listAll();
    }

    /**
     * Resolve a bearer token to its key row, or return empty if unknown,
     * revoked, or malformed. Updates {@code last_used_at} on hit. The check
     * is constant time on the hash comparison since SQLite indexes the
     * unique column lookup directly; we never compare raw secrets.
     */
    public Optional<ApiKey> verify(String bearerToken) {
        if (bearerToken == null) return Optional.empty();
        String token = bearerToken.trim();
        if (token.isEmpty() || !token.startsWith(TOKEN_PREFIX) || token.indexOf('.') < 0) {
            return Optional.empty();
        }
        try {
            String hash = sha256(token);
            Optional<ApiKey> hit = dao.findByHash(hash);
            if (hit.isEmpty()) return Optional.empty();
            ApiKey k = hit.get();
            if (k.isRevoked()) return Optional.empty();
            try {
                dao.touchLastUsed(k.id(), System.currentTimeMillis());
            } catch (SQLException e) {
                log.log(Level.FINE, "api_key touch failed (non-fatal): " + e.getMessage());
            }
            return Optional.of(k);
        } catch (SQLException e) {
            log.log(Level.WARNING, "api_key verify failed: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    private String randomPart() {
        byte[] buf = new byte[RANDOM_BYTES];
        rng.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                int v = b & 0xff;
                sb.append(Character.forDigit(v >>> 4, 16));
                sb.append(Character.forDigit(v & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String safeLabel(String s) {
        if (s == null || s.isBlank()) return "(unlabelled)";
        if (s.length() > 60) return s.substring(0, 60) + "...";
        return s;
    }
}
