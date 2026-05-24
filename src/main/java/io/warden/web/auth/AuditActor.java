package io.warden.web.auth;

import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves "who is performing this dashboard action" so audit writes can record
 * the moderator's identity instead of an opaque "web-mod" label.
 *
 * Returns a small bundle of fields meant to be merged into the audit payload.
 * If the request was authed via the legacy session-secret token (no Discord
 * session), the bundle just records that.
 */
public final class AuditActor {

    private AuditActor() {}

    /** Build a payload Map with caller info merged in. Keeps the caller's keys; adds modId/modName/via. */
    public static Map<String, Object> payload(Context ctx, Map<String, ?> base) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (base != null) out.putAll(base);
        Optional<SessionCookie.Session> sess = DashAuth.sessionOf(ctx);
        if (sess.isPresent()) {
            out.put("modId", sess.get().discordId());
            String name = sess.get().username();
            out.put("modName", (name == null || name.isBlank()) ? "(unknown)" : name);
            out.putIfAbsent("source", "dashboard");
        } else {
            out.putIfAbsent("source", "session_secret_token");
        }
        return out;
    }

    /** Discord id of the acting moderator, if known. */
    public static String modDiscordId(Context ctx) {
        return DashAuth.sessionOf(ctx).map(SessionCookie.Session::discordId).orElse(null);
    }

    /** Display name of the acting moderator, or null if unknown. */
    public static String modUsername(Context ctx) {
        return DashAuth.sessionOf(ctx).map(SessionCookie.Session::username).orElse(null);
    }
}
