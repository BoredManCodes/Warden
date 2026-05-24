package io.warden.data.dao;

import io.warden.data.Database;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Codes used to pair a guest browser session with a Discord identity.
 *
 * Flow: the user opens /onboard, the site mints a code bound to their guest
 * cookie ({@code web_session_id}), and the user DMs that code to the bot. The
 * DM listener calls {@link #claim} which fills in {@code claimed_by} and
 * marks the code consumed; the landing page polls {@link #findClaimedFor} to
 * detect that the pairing finished and upgrade the cookie to a real session.
 */
public final class LinkCodeDao {

    /** Omits visually confusable I, O, 0, 1. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RAND = new SecureRandom();
    public static final long DEFAULT_TTL_MS = 15L * 60_000L;

    private final Database db;

    public LinkCodeDao(Database db) { this.db = db; }

    /** Issued code awaiting a DM from a Discord user. */
    public record IssuedCode(String code, long expiresAt) {}

    /** A claim result returned to the web poller. */
    public record ClaimedCode(String code, String discordId, long claimedAt) {}

    public IssuedCode issueForSession(String webSessionId) throws SQLException {
        return issueForSession(webSessionId, DEFAULT_TTL_MS);
    }

    /**
     * Generate a fresh code tied to the given guest browser session. Retries on
     * the (vanishingly rare) primary-key collision so callers don't have to.
     */
    public IssuedCode issueForSession(String webSessionId, long ttlMs) throws SQLException {
        if (webSessionId == null || webSessionId.isBlank()) {
            throw new IllegalArgumentException("webSessionId required");
        }
        long now = System.currentTimeMillis();
        long expires = now + ttlMs;
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = generate();
            try (Connection c = db.connection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO link_codes (code, web_session_id, expires_at, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, code);
                ps.setString(2, webSessionId);
                ps.setLong(3, expires);
                ps.setLong(4, now);
                ps.executeUpdate();
                return new IssuedCode(code, expires);
            } catch (SQLException e) {
                if (isUniqueViolation(e)) continue;
                throw e;
            }
        }
        throw new SQLException("Could not generate a unique link code after 8 attempts");
    }

    /**
     * Atomically claim a code on behalf of a Discord user (the DM sender).
     * Returns the web_session_id that originally requested the code, or empty
     * if the code is missing, already claimed, or expired.
     */
    public Optional<String> claim(String codeRaw, String discordId) throws SQLException {
        if (codeRaw == null || discordId == null || discordId.isBlank()) return Optional.empty();
        String code = codeRaw.trim().toUpperCase();
        if (code.length() != 8) return Optional.empty();
        long now = System.currentTimeMillis();
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                String webSessionId;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT web_session_id FROM link_codes "
                                + "WHERE code = ? AND claimed_by IS NULL AND expires_at > ?")) {
                    sel.setString(1, code);
                    sel.setLong(2, now);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return Optional.empty();
                        }
                        webSessionId = rs.getString(1);
                    }
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE link_codes SET claimed_by = ?, consumed_at = ? "
                                + "WHERE code = ? AND claimed_by IS NULL")) {
                    upd.setString(1, discordId);
                    upd.setLong(2, now);
                    upd.setString(3, code);
                    int n = upd.executeUpdate();
                    if (n == 0) {
                        c.rollback();
                        return Optional.empty();
                    }
                }
                c.commit();
                return Optional.of(webSessionId);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /**
     * Look up the most recent claimed code for a guest browser session.
     * Returns empty if no code for this session has been claimed yet, or if
     * the only matches were issued more than {@code lookbackMs} ago.
     */
    public Optional<ClaimedCode> findClaimedFor(String webSessionId, long lookbackMs) throws SQLException {
        if (webSessionId == null || webSessionId.isBlank()) return Optional.empty();
        long cutoff = System.currentTimeMillis() - lookbackMs;
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT code, claimed_by, consumed_at FROM link_codes "
                             + "WHERE web_session_id = ? AND claimed_by IS NOT NULL "
                             + "AND created_at >= ? ORDER BY consumed_at DESC LIMIT 1")) {
            ps.setString(1, webSessionId);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ClaimedCode(
                        rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
        }
    }

    private static String generate() {
        char[] out = new char[8];
        for (int i = 0; i < 8; i++) out[i] = ALPHABET[RAND.nextInt(ALPHABET.length)];
        return new String(out);
    }

    private static boolean isUniqueViolation(SQLException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("unique");
    }
}
