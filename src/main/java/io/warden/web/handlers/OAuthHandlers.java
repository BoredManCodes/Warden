package io.warden.web.handlers;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.warden.Services;
import io.warden.discord.DiscordService;
import io.warden.web.auth.DiscordOAuth;
import io.warden.web.auth.SessionCookie;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OAuthHandlers {

    private final Services services;
    private final DiscordService discord;
    private final DiscordOAuth oauth;
    private final SessionCookie session;
    private final Logger log;

    public OAuthHandlers(Services services, DiscordService discord, DiscordOAuth oauth,
                         SessionCookie session, Logger log) {
        this.services = services;
        this.discord = discord;
        this.oauth = oauth;
        this.session = session;
        this.log = log;
    }

    /**
     * GET /auth/discord/start?next=&lt;path&gt;
     * Embeds `next` into a signed state value so we don't rely on cookies
     * surviving the cross-origin redirect.
     */
    public void start(Context ctx) {
        if (!services.config.oauthConfigured()) {
            ctx.status(500).html("OAuth isn't configured. Set the Discord client id, client secret, and web session secret in <code>plugins/Warden/config.yml</code>.");
            return;
        }
        String next = ctx.queryParam("next");
        // Default to the role-dispatch redirector at /dash so mods land on Stats,
        // landing-only editors land on Config, etc.
        if (next == null || !next.startsWith("/")) next = "/dash";

        String state = oauth.signedState(next);
        ctx.redirect(oauth.authorizeUrl(state));
    }

    /** GET /auth/discord/callback?code=...&state=... */
    public void callback(Context ctx) {
        String code = ctx.queryParam("code");
        String state = ctx.queryParam("state");
        String error = ctx.queryParam("error");
        String errorDescription = ctx.queryParam("error_description");

        // The state cookie/param round-trips the original `next`; pull it out
        // early so error pages can preserve where the user was trying to go.
        Optional<String> nextOpt = oauth.verifyState(state);
        String retryNext = nextOpt.orElse(null);

        // Discord sends us here with ?error=... when the user denies consent or
        // something goes wrong on their end. Show a friendly retry page instead
        // of a blank 400.
        if (error != null && !error.isBlank()) {
            boolean denied = "access_denied".equals(error);
            String headline = denied
                    ? "Login cancelled."
                    : "Discord wouldn't authorise the login.";
            String detail = denied
                    ? "You denied the Discord permission request, so we couldn't sign you in."
                    : (errorDescription != null && !errorDescription.isBlank()
                            ? "Discord said: " + escape(errorDescription)
                            : "Discord returned <code>" + escape(error) + "</code> without a description.");
            ctx.status(400).html(errorPage(headline, detail, retryNext));
            return;
        }

        if (code == null || code.isBlank()) {
            ctx.status(400).html(errorPage(
                    "Login didn't complete.",
                    "Discord redirected back without an authorisation code. That usually means the tab was reloaded or the URL was opened directly.",
                    retryNext));
            return;
        }
        if (nextOpt.isEmpty()) {
            ctx.status(400).html(errorPage(
                    "OAuth state invalid or expired.",
                    "This usually means more than 10 minutes passed between starting login and returning, or the bot was restarted.",
                    null));
            return;
        }
        String next = nextOpt.get();

        String modRoleId = "";
        String configAdminRoleId = "";
        String webManagerRoleId = "";
        try {
            var current = services.settingsDao.get();
            modRoleId = current.modRoleId();
            configAdminRoleId = current.configAdminRoleId();
            webManagerRoleId  = current.webManagerRoleId();
        } catch (Exception ignored) {}

        try {
            DiscordOAuth.AuthedUser user = oauth.completeOAuth(code, modRoleId, configAdminRoleId, webManagerRoleId);

            // The OAuth call only knows about role membership. Owner / ADMINISTRATOR
            // are looked up via JDA so the server operator can always get in even on
            // a fresh install where mod_role_id isn't set yet.
            boolean isOwnerOrAdmin = isOwnerOrAdmin(user.discordId());
            boolean isMod = user.mod() || isOwnerOrAdmin;
            // Owners implicitly count as config admin so they can always reach /dash/config.
            boolean isConfigAdmin = user.configAdmin() || isOwnerOrAdmin;
            // Web managers are not implied by mod; they need the explicit role (or to be owner).
            boolean isWebManager  = user.webManager()  || isOwnerOrAdmin;

            services.userDao.upsert(user.discordId(), user.username());
            SessionCookie.Session s = new SessionCookie.Session(
                    user.discordId(), user.username(),
                    user.displayName(), user.avatar(),
                    isMod, isConfigAdmin, isWebManager, isOwnerOrAdmin,
                    user.roleIds() == null ? java.util.List.of() : user.roleIds(),
                    System.currentTimeMillis() / 1000);
            ctx.cookie(buildSessionCookie(session.encode(s)));
            services.audit.write("web", "oauth_login", user.discordId(),
                    java.util.Map.of(
                            "mod", isMod,
                            "configAdmin", isConfigAdmin,
                            "webManager", isWebManager,
                            "modByRole", user.mod(),
                            "ownerOrAdmin", isOwnerOrAdmin));

            // First-run bootstrap: if mod_role_id isn't set yet and the operator is
            // owner/admin, drop them on the setup wizard so they can pick one.
            if (isOwnerOrAdmin && (modRoleId == null || modRoleId.isBlank())) {
                ctx.redirect("/setup/mod-role");
                return;
            }
            ctx.redirect(next);
        } catch (Exception e) {
            log.log(Level.WARNING, "OAuth callback failed", e);
            ctx.status(500).html(errorPage(
                    "Something went wrong finishing the login.",
                    "We couldn't exchange the Discord code for a session. Give it another go in a moment.",
                    next));
        }
    }

    /**
     * Renders the shared OAuth-error page. {@code retryNext} is the path the
     * user was originally heading to; when present it's threaded back into the
     * retry link so the next attempt lands in the same place.
     */
    private static String errorPage(String headline, String detail, String retryNext) {
        String retryHref = "/auth/discord/start";
        if (retryNext != null && retryNext.startsWith("/")) {
            retryHref += "?next=" + URLEncoder.encode(retryNext, StandardCharsets.UTF_8);
        }
        return """
                <!doctype html><html><head><meta charset=utf-8><title>Sign-in didn't finish · Warden</title>
                <style>
                  body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;padding:3rem 1.5rem;max-width:560px;margin:0 auto;color:#1f2328;line-height:1.5}
                  h1{font-size:1.5rem;margin:0 0 .75rem}
                  p{margin:0 0 1.25rem;color:#3a4150}
                  .btn{display:inline-block;background:#5865F2;color:#fff;text-decoration:none;padding:.65rem 1.1rem;border-radius:.4rem;font-weight:600}
                  .btn:hover{background:#4752c4}
                  .alt{display:inline-block;margin-left:.75rem;color:#57606a;text-decoration:none}
                  .alt:hover{text-decoration:underline}
                  code{background:#f4f5f7;padding:.05rem .3rem;border-radius:.25rem;font-size:.9em}
                </style>
                </head><body>
                <h1>%s</h1>
                <p>%s</p>
                <p><a class="btn" href="%s">Try again</a><a class="alt" href="/">Back to home</a></p>
                </body></html>
                """.formatted(escape(headline), detail, escape(retryHref));
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default  -> out.append(c);
            }
        }
        return out.toString();
    }

    /** POST /auth/logout */
    public void logout(Context ctx) {
        ctx.removeCookie(SessionCookie.COOKIE_NAME, "/");
        ctx.redirect("/");
    }

    /**
     * Returns true if the given Discord user is the server owner of our
     * configured guild, OR has the Discord ADMINISTRATOR permission in it.
     * Best-effort: returns false if Discord isn't connected or the member
     * can't be fetched.
     */
    private boolean isOwnerOrAdmin(String discordId) {
        if (discord == null || discord.jda() == null) return false;
        JDA jda = discord.jda();
        Guild g = jda.getGuildById(services.config.discordGuildId());
        if (g == null) return false;
        if (discordId.equals(g.getOwnerId())) return true;
        Member cached = g.getMemberById(discordId);
        if (cached != null) {
            return cached.hasPermission(Permission.ADMINISTRATOR);
        }
        // Not cached - block briefly to fetch. We're in an HTTP handler thread, so
        // it's OK to wait; the call is short and only happens on login.
        try {
            Member m = g.retrieveMemberById(discordId).complete();
            return m != null && m.hasPermission(Permission.ADMINISTRATOR);
        } catch (Exception e) {
            log.log(Level.FINE, "owner/admin lookup failed for " + discordId + ": " + e.getMessage());
            return false;
        }
    }

    private static Cookie buildSessionCookie(String value) {
        Cookie c = new Cookie(SessionCookie.COOKIE_NAME, value);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(SessionCookie.MAX_AGE_SECONDS);
        c.setSameSite(io.javalin.http.SameSite.LAX);
        return c;
    }
}
