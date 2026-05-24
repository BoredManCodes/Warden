package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.onboarding.model.Settings;
import io.warden.web.auth.AuditActor;
import io.warden.web.auth.SessionCookie;

import java.util.List;
import java.util.Optional;

/**
 * First-run bootstrap pages. The dashboard is gated by mod_role_id, but
 * mod_role_id doesn't exist on a fresh install. The OAuth callback treats
 * server owners and Discord admins as mods so they can reach this page and
 * pick the role that future mods will use.
 */
public final class SetupHandlers {

    private final Services services;
    private final GuildLookup lookup;
    private final SessionCookie session;

    public SetupHandlers(Services services, GuildLookup lookup, SessionCookie session) {
        this.services = services;
        this.lookup = lookup;
        this.session = session;
    }

    /** GET /setup/mod-role - role picker for first-run bootstrap. */
    public void modRoleForm(Context ctx) throws Exception {
        Settings s = services.settingsDao.get();
        List<GuildLookup.Option> roles = lookup.roles();
        Optional<SessionCookie.Session> sess = session == null
                ? Optional.empty()
                : session.decode(ctx.cookie(SessionCookie.COOKIE_NAME));

        StringBuilder h = new StringBuilder(2048);
        h.append(head("Pick mod role · Setup"));
        h.append("<main>");
        h.append("<h1>Pick your moderator role</h1>");
        h.append("<p class=lede>This role controls dashboard access and the in-Discord ")
                .append("Approve/Deny buttons. Members with this role become Warden moderators. ")
                .append("You can change this any time in the dashboard's Config page.</p>");

        if (!lookup.discordConnected()) {
            boolean tokenCollision = services.discordSrv() != null
                    && services.discordSrv().tokenMatches(services.config.discordBotToken());
            if (tokenCollision) {
                h.append("<div class=danger role=alert>");
                h.append("<h2>DiscordSRV is using the same bot token</h2>");
                h.append("<p>Warden and DiscordSRV are configured with the <strong>same Discord bot token</strong>, ")
                        .append("so Warden's Discord client refused to start. Discord allows only one gateway ")
                        .append("session per token, and slash commands are registered per-application, so the two ")
                        .append("plugins would fight for the connection and overwrite each other's commands.</p>");
                h.append("<p><strong>To fix:</strong></p>");
                h.append("<ol>");
                h.append("<li>Create a second bot application at ")
                        .append("<a href=\"https://discord.com/developers/applications\" target=_blank rel=noopener>")
                        .append("discord.com/developers/applications</a>.</li>");
                h.append("<li>Invite the new bot to your server with the same permissions ")
                        .append("(Manage Roles, Send Messages, Read Message History, etc.).</li>");
                h.append("<li>Put the new bot's token in <code>plugins/Warden/config.yml</code> under ")
                        .append("<code>discord.bot_token</code> (or set the ")
                        .append("<code>WARDEN_DISCORD_BOT_TOKEN</code> environment variable).</li>");
                h.append("<li>Restart the server.</li>");
                h.append("</ol>");
                h.append("<p class=hint>DiscordSRV will keep running normally. Warden's web dashboard and ")
                        .append("Minecraft-side features are unaffected.</p>");
                h.append("</div>");
            } else {
                h.append("<p class=warn>Discord isn't connected yet, so the role list is empty. ")
                        .append("Make sure the Discord bot token and guild id are set in ")
                        .append("<code>plugins/Warden/config.yml</code>, then restart the server and reload this page.</p>");
            }
            h.append("</main></body></html>");
            ctx.html(h.toString());
            return;
        }

        h.append("<form method=post action=/setup/mod-role class=card>");
        h.append("<label class=field><span>Moderator role</span>");
        h.append("<select name=mod_role_id required>");
        h.append("<option value=\"\" disabled ").append(s.modRoleId().isBlank() ? "selected" : "").append(">Pick a role...</option>");
        for (var o : roles) {
            h.append("<option value=\"").append(esc(o.id())).append("\"")
                    .append(o.id().equals(s.modRoleId()) ? " selected" : "").append(">")
                    .append(esc(o.name())).append("</option>");
        }
        h.append("</select></label>");
        h.append("<p class=hint>Don't see the role you want? Create it in Discord first, then refresh this page.</p>");
        h.append("<button class=\"btn primary\" type=submit>Save and continue</button>");
        h.append("</form>");

        if (sess.isPresent()) {
            h.append("<p class=meta>Signed in as <strong>").append(esc(sess.get().username())).append("</strong> ")
                    .append("(this account has admin-level access here because it's the server owner ")
                    .append("or has the Discord Administrator permission).</p>");
        }
        h.append("</main></body></html>");
        ctx.html(h.toString());
    }

    /** POST /setup/mod-role - persist the selected role and continue to config. */
    public void modRoleSave(Context ctx) throws Exception {
        String roleId = ctx.formParam("mod_role_id");
        if (roleId == null || roleId.isBlank()) {
            ctx.redirect("/setup/mod-role");
            return;
        }
        Settings current = services.settingsDao.get();
        Settings next = new Settings(
                current.rulesMarkdown(),
                current.gatedRoleId(),
                current.fullRoleId(),
                roleId,
                current.configAdminRoleId(),
                current.webManagerRoleId(),
                current.welcomeChannelId(),
                current.modReviewChannelId(),
                current.llmSystemPrompt(),
                current.llmAutoApproveThreshold(),
                current.llmAutoDenyThreshold(),
                current.llmAutoDenyEnabled(),
                current.llmApiKey(),
                current.llmBaseUrl(),
                current.llmModel(),
                current.geoipEnabled(),
                current.geoipLicenseKey(),
                current.flow(),
                current.landing()
        );
        services.settingsDao.save(next);
        services.audit.write("web", "mod_role_set", AuditActor.modDiscordId(ctx),
                AuditActor.payload(ctx, java.util.Map.of("via", "setup_wizard", "roleId", roleId)));
        ctx.redirect("/dash/config");
    }

    private static String head(String title) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>" +
                "<meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<title>" + esc(title) + "</title>" +
                Layout.THEME_BOOT_SCRIPT +
                "<link rel=icon type=\"image/svg+xml\" href=/static/img/warden-icon.svg>" +
                "<style>" + CSS + "</style></head><body>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CSS = """
            :root{color-scheme:light dark;--brand:#6b83ff;--brand-deep:#4a64e6;
              --bg:#f5f6fa;--card-bg:#fff;--border:#e2e4ea;--text:#1d1f2a;--muted:#6c7080}
            :root[data-theme=dark]{--bg:#0f1014;--card-bg:#181a21;--border:#2a2c33;--text:#eaeaea;--muted:#9ca0ac}
            @media (prefers-color-scheme:dark){
              :root:not([data-theme=light]){--bg:#0f1014;--card-bg:#181a21;--border:#2a2c33;--text:#eaeaea;--muted:#9ca0ac}}
            *,*::before,*::after{box-sizing:border-box}
            body{margin:0;font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;
                 background:var(--bg);color:var(--text);min-height:100vh;padding:3rem 1.5rem}
            main{max-width:560px;margin:0 auto}
            h1{margin:0 0 .5rem}
            p.lede{color:var(--muted);margin:0 0 1.5rem}
            .card{background:var(--card-bg);border:1px solid var(--border);border-radius:12px;
                  padding:1.5rem 1.75rem;margin-bottom:1.5rem}
            label.field{display:block;margin:0 0 .75rem}
            label.field > span{display:block;font-weight:600;font-size:.9em;color:var(--muted);
                               margin-bottom:.35rem}
            select{width:100%;padding:.6em .7em;border:1px solid var(--border);border-radius:6px;
                   font-size:1em;background:var(--card-bg);color:inherit}
            .hint{color:var(--muted);font-size:.92em;margin:.5rem 0 1rem}
            .warn{padding:.75rem 1rem;background:#fff3cd;color:#856404;border-radius:6px}
            .danger{padding:1.25rem 1.5rem;margin:0 0 1.5rem;background:#fff0f0;
                    border:2px solid #c0392b;border-radius:10px;color:#7a1a14}
            :root[data-theme=dark] .danger{background:rgba(192,57,43,.14);
                    border-color:#e57368;color:#ffb3a8}
            @media (prefers-color-scheme:dark){:root:not([data-theme=light]) .danger{
                    background:rgba(192,57,43,.14);border-color:#e57368;color:#ffb3a8}}
            .danger h2{margin:0 0 .65rem;font-size:1.15em;color:inherit}
            .danger ol{margin:.5rem 0 .75rem 1.25rem;padding:0}
            .danger li{margin:.4em 0}
            .danger a{color:inherit;text-decoration:underline}
            .danger code{background:rgba(192,57,43,.12);color:inherit}
            code{background:rgba(107,131,255,.12);padding:.1em .4em;border-radius:3px;font-size:.92em}
            .btn{padding:.7em 1.4em;border-radius:8px;font-weight:600;cursor:pointer;font-size:1em;
                 font-family:inherit;background:transparent;color:var(--text);border:1px solid var(--border)}
            .btn:hover{background:rgba(127,127,127,.08)}
            .btn.primary{background:var(--brand);color:#fff;border-color:var(--brand)}
            .btn.primary:hover{background:var(--brand-deep);border-color:var(--brand-deep)}
            .meta{font-size:.88em;color:var(--muted);margin-top:1rem}
            """;
}
