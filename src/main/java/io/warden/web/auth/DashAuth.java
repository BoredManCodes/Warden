package io.warden.web.auth;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.warden.config.WardenConfig;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Auth gate for /dash/* routes.
 *
 * Order of precedence:
 *  1. Valid SessionCookie (set by OAuth callback) with mod=true → allow.
 *  2. Valid session-secret token (legacy / break-glass for when OAuth isn't set up).
 *  3. Otherwise → redirect to /auth/discord/start?next=&lt;original&gt; if OAuth
 *     is configured, or show a "set session_secret" message if not.
 */
public final class DashAuth {

    private static final String LEGACY_COOKIE = "dash_token";

    /** ctx attribute key carrying the validated mod session (when auth succeeded via cookie). */
    public static final String CTX_SESSION = "warden.session";

    private final WardenConfig config;
    private final SessionCookie session; // may be null if session_secret isn't set
    private final Logger log;

    /** Read the validated mod session out of ctx, if the request was authed via session cookie. */
    public static Optional<SessionCookie.Session> sessionOf(Context ctx) {
        Object v = ctx.attribute(CTX_SESSION);
        return (v instanceof SessionCookie.Session s) ? Optional.of(s) : Optional.empty();
    }

    public DashAuth(WardenConfig config, Logger log) {
        this.config = config;
        this.log = log;
        this.session = (config.webSessionSecret() == null || config.webSessionSecret().isBlank())
                ? null : new SessionCookie(config.webSessionSecret());
    }

    public boolean check(Context ctx) {
        if (session == null) {
            ctx.status(404);
            ctx.html("""
                    <h1>404</h1>
                    <p>Set the web session secret (<code>web.session_secret</code> in
                    <code>plugins/Warden/config.yml</code>, 32+ random bytes hex) to enable the dashboard.</p>""");
            return false;
        }

        // 1. Real session cookie
        var maybeSession = readSession(ctx);
        if (maybeSession.isPresent() && maybeSession.get().anyDashAccess()) {
            ctx.attribute(CTX_SESSION, maybeSession.get());
            return true;
        }
        if (maybeSession.isPresent() && !maybeSession.get().anyDashAccess()) {
            // Logged in but no staff role - send them to /tz where they can
            // still configure their timezone and view scheduled events. This
            // is the players-with-no-perms entry point.
            String path = ctx.path();
            if (path == null || (!path.equals("/tz") && !path.startsWith("/tz/"))) {
                ctx.redirect("/tz");
                return false;
            }
            ctx.status(403).html(notModBody(maybeSession.get().username()));
            return false;
        }

        // 2. Legacy session-secret token (break-glass)
        if (legacyTokenOk(ctx)) return true;

        // 3. Redirect to OAuth if it's wired up; otherwise show the legacy-token instructions.
        if (config.oauthConfigured()) {
            ctx.redirect("/auth/discord/start?next=" + java.net.URLEncoder.encode(
                    ctx.path() + (ctx.queryString() == null ? "" : "?" + ctx.queryString()),
                    java.nio.charset.StandardCharsets.UTF_8));
            return false;
        }
        ctx.status(401);
        ctx.html("""
                <!doctype html><html><head><meta charset=utf-8>
                <title>Locked · Warden</title>
                <style>body{font-family:system-ui;padding:3rem;max-width:560px}code{background:#eee;padding:.1em .3em;border-radius:3px}</style>
                </head><body>
                <h1>Dashboard locked</h1>
                <p>Discord OAuth isn't configured yet. Append <code>?token=&lt;your session secret&gt;</code>
                to the URL once as a break-glass, or fill in the Discord client id and client secret in
                <code>plugins/Warden/config.yml</code> and add <code>{public_url}/auth/discord/callback</code>
                to your Discord app's redirect URIs.</p>
                </body></html>
                """.replace("{public_url}", config.webPublicUrl()));
        return false;
    }

    private Optional<SessionCookie.Session> readSession(Context ctx) {
        String raw = ctx.cookie(SessionCookie.COOKIE_NAME);
        return session.decode(raw);
    }

    private boolean legacyTokenOk(Context ctx) {
        String expected = config.webSessionSecret();
        String supplied = ctx.queryParam("token");
        if (supplied == null) supplied = ctx.cookie(LEGACY_COOKIE);
        if (supplied == null || !constantTimeEquals(supplied, expected)) return false;

        if (ctx.cookie(LEGACY_COOKIE) == null) {
            Cookie c = new Cookie(LEGACY_COOKIE, expected);
            c.setHttpOnly(true);
            c.setPath("/");
            c.setMaxAge(60 * 60 * 24 * 30);
            ctx.cookie(c);
        }
        return true;
    }

    private static String notModBody(String username) {
        return """
                <!doctype html><html><head><meta charset=utf-8>
                <title>Not a mod · Warden</title>
                <style>body{font-family:system-ui;padding:3rem;max-width:560px}</style>
                </head><body>
                <h1>You're signed in, but you're not a mod.</h1>
                <p>Signed in as <strong>%s</strong>. The dashboard needs the mod role configured in
                the Warden settings - ask an admin to grant it.</p>
                <p><form method=post action=/auth/logout><button>Log out</button></form></p>
                </body></html>
                """.formatted(username == null ? "(unknown)" : username);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aa = a.getBytes();
        byte[] bb = b.getBytes();
        if (aa.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
        return diff == 0;
    }
}
