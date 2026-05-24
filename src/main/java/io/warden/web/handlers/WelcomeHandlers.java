package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.discord.DiscordService;
import io.warden.onboarding.model.FlowConfig;
import io.warden.onboarding.model.LandingConfig;
import io.warden.onboarding.model.LandingFaq;
import io.warden.onboarding.model.LandingFeature;
import io.warden.onboarding.model.Settings;
import io.warden.web.auth.SessionCookie;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The "/" landing page is a first-launch setup wizard.
 *
 * Checklist-style: each row shows current state and the concrete next action.
 * Once everything is green, a logged-in mod is auto-forwarded to /dash; a
 * not-yet-signed-in mod sees a "Sign in with Discord" CTA; everyone else
 * sees a status page with what's left.
 */
public final class WelcomeHandlers {

    private final Services services;
    private final DiscordService discord;
    private final SessionCookie session; // may be null if session_secret not set

    public WelcomeHandlers(Services services, DiscordService discord, SessionCookie session) {
        this.services = services;
        this.discord = discord;
        this.session = session;
    }

    public void index(Context ctx) throws Exception {
        Settings s = services.settingsDao.get();
        FlowConfig f = s.flow();
        var cfg = services.config;

        Optional<SessionCookie.Session> sess = session == null
                ? Optional.empty()
                : session.decode(ctx.cookie(SessionCookie.COOKIE_NAME));

        boolean discordOk     = discord != null && discord.isReady();
        boolean sessionSecret = !nullOrBlank(cfg.webSessionSecret());
        boolean oauthOk       = cfg.oauthConfigured(); // client_id + client_secret + session_secret
        boolean manifestOk    = s.llmConfigured();
        boolean modRoleOk     = !nullOrBlank(s.modRoleId());
        boolean gatedRoleOk   = !nullOrBlank(s.gatedRoleId());
        boolean fullRoleOk    = !nullOrBlank(s.fullRoleId());
        boolean deliveryOk    = f.anyDeliveryEnabled() && f.anyEntryEnabled();

        boolean allReady = discordOk && sessionSecret && oauthOk
                && modRoleOk && gatedRoleOk && fullRoleOk && deliveryOk;

        // Mod already signed in + everything set up → skip the wizard.
        if (allReady && sess.isPresent() && sess.get().mod()) {
            ctx.redirect("/dash/stats");
            return;
        }

        // Public-facing landing: once setup is complete and the visitor is not a
        // signed-in mod, the operator-facing checklist isn't useful. Show the
        // configurable landing template (or redirect / 404 per landing settings).
        if (allReady && (sess.isEmpty() || !sess.get().mod())) {
            LandingConfig landing = s.landing();
            if (landing != null) {
                if (landing.redirect()) {
                    ctx.redirect(landing.redirectUrl());
                    return;
                }
                if (landing.disabled()) {
                    ctx.status(404).html("<h1>404</h1>");
                    return;
                }
                if (landing.enabled()) {
                    renderLanding(ctx, s, landing);
                    return;
                }
            }
        }

        String callbackUrl = cfg.webPublicUrl() + "/auth/discord/callback";
        String selfTag = (discord != null && discord.jda() != null && discord.jda().getSelfUser() != null)
                ? "@" + discord.jda().getSelfUser().getName() : "(not connected)";

        StringBuilder h = new StringBuilder(8192);
        h.append(head("Welcome to Warden"));
        h.append("<header class=hero>");
        h.append("<img class=logo src=\"/static/img/warden-icon.svg\" alt=\"Warden\" width=80 height=80>");
        h.append("<div>");
        h.append("<h1>Welcome to Warden</h1>");
        if (allReady) {
            h.append("<p class=lede>Everything's wired up. ");
            if (sess.isPresent() && !sess.get().mod()) {
                h.append("You're signed in but you don't have the mod role - ask an admin to grant it.");
            } else {
                h.append("Sign in to open the dashboard.");
            }
            h.append("</p>");
        } else {
            int doneCount = (discordOk?1:0)+(sessionSecret?1:0)+(oauthOk?1:0)
                    + (modRoleOk?1:0)+(gatedRoleOk?1:0)+(fullRoleOk?1:0)+(deliveryOk?1:0);
            h.append("<p class=lede>Let's get you set up - ").append(doneCount).append(" of 7 steps done.</p>");
        }
        h.append("</div></header>");

        h.append("<section class=card>");
        h.append("<h2>Setup checklist</h2>");

        h.append(row(discordOk, "Discord bot connected",
                discordOk
                        ? "Connected as " + esc(selfTag) + " · guild <strong>" + esc(guildLabel(cfg.discordGuildId())) + "</strong>"
                        : "Fill <code>discord.bot_token</code> and <code>discord.guild_id</code> in <code>plugins/Warden/config.yml</code> and restart.",
                null, null));

        h.append(row(sessionSecret, "Web session secret set",
                sessionSecret
                        ? "Used to sign session cookies."
                        : "Set <code>web.session_secret</code> to 32+ random bytes hex. Restart afterwards.",
                null, null));

        h.append(row(oauthOk, "Discord OAuth wired",
                oauthOk
                        ? "Client id <code>" + esc(cfg.discordClientId()) + "</code> + client secret set."
                        : "Set <code>discord.client_id</code> and <code>discord.client_secret</code> in config.yml.",
                "Register the callback URL in Discord developer portal:",
                "<code class=copyable>" + esc(callbackUrl) + "</code>"
                        + " - paste into <em>OAuth2 → Redirects</em> at "
                        + "<a href=\"https://discord.com/developers/applications/" + esc(cfg.discordClientId())
                        + "/oauth2\" target=_blank rel=noopener>discord.com/developers/applications/"
                        + esc(cfg.discordClientId()) + "/oauth2</a>"));

        h.append(row(manifestOk, "AI gateway (optional)",
                manifestOk
                        ? "Configured - the LLM auto and LLM only triage modes will use it."
                        : "Optional. Set the API key, base URL, and model on the AI tab in /dash/config. "
                        + "Any OpenAI-Responses-compatible gateway works.",
                null, null));

        // Role/channel + delivery checks need a signed-in mod to actually configure.
        h.append(row(modRoleOk, "Mod role assigned",
                modRoleOk
                        ? "Role <strong>" + esc(roleLabel(s.modRoleId())) + "</strong>"
                        : "Pick the role that controls dashboard access. Set from the dashboard.",
                null, null));

        h.append(row(gatedRoleOk, "Gated role assigned",
                gatedRoleOk
                        ? "Role <strong>" + esc(roleLabel(s.gatedRoleId())) + "</strong> applied to new joiners."
                        : "Pick the role applied on join (lock-out role). Set from the dashboard.",
                null, null));

        h.append(row(fullRoleOk, "Full-member role assigned",
                fullRoleOk
                        ? "Role <strong>" + esc(roleLabel(s.fullRoleId())) + "</strong> applied on approval."
                        : "Pick the role granted after approval. Set from the dashboard.",
                null, null));

        h.append(row(deliveryOk, "Delivery and entry enabled",
                deliveryOk
                        ? "At least one delivery (DM or public channel) and one entry method (Discord button, web code, or web OAuth) is on."
                        : "Right now Warden silently audits joins. Turn on at least one delivery method and one entry method on the config page.",
                null, null));

        h.append("</section>");

        // Action area
        h.append("<section class=card actions>");
        if (!sessionSecret) {
            h.append("<p>Once the session secret is set in <code>plugins/Warden/config.yml</code> ")
             .append("(key <code>web.session_secret</code>) and the plugin is restarted, you'll be able to sign in here.</p>");
        } else if (!oauthOk) {
            h.append("<p>Once Discord OAuth is wired up, you'll be able to sign in here. ");
            h.append("In the meantime you can still reach the dashboard with ");
            h.append("<code>/dash/stats?token=&lt;your session secret&gt;</code>.</p>");
        } else if (sess.isEmpty()) {
            String next = allReady ? "/dash/stats" : "/dash/config";
            h.append("<a class=\"btn primary\" href=\"/auth/discord/start?next=").append(esc(next)).append("\">Sign in with Discord</a>");
            h.append(" <span class=hint>You'll land on ").append(allReady ? "the stats dashboard" : "the config page").append(".</span>");
        } else if (!sess.get().mod()) {
            h.append("<p>Signed in as <strong>").append(esc(sess.get().username())).append("</strong>, ")
                    .append("but you don't have the mod role yet. Ask an admin to grant the configured mod role, ")
                    .append("or use the break-glass token: <code>/dash/stats?token=&lt;your session secret&gt;</code>.</p>");
            h.append("<form method=post action=/auth/logout style=display:inline><button class=btn>Log out</button></form>");
        } else {
            // signed in + mod + something still missing → drop them on /dash/config
            h.append("<a class=\"btn primary\" href=\"/dash/config\">Open dashboard config</a>");
        }
        h.append("</section>");

        h.append("<footer class=meta>");
        h.append("Warden ").append(esc(plugin_version())).append(" · ");
        h.append("Bot ").append(esc(selfTag)).append(" · ");
        h.append("Schema v3 · ");
        h.append("<a href=/health>/health</a>");
        h.append("</footer>");

        h.append("</body></html>");
        ctx.html(h.toString());
    }

    /**
     * Render the configurable landing template at plugins/Warden/www/landing.html.
     * Supported placeholders are {@code {{server_name}}}, {@code {{tagline}}},
     * {@code {{server_address}}}, {@code {{players_online}}}, {@code {{players_max}}},
     * {@code {{discord_members}}}, {@code {{join_url}}}, {@code {{signin_url}}},
     * {@code {{dash_url}}}. Plus a handful of conditional blocks the default
     * template uses to keep optional rows out of the markup entirely when the
     * value is blank: {@code {{tagline_block}}}, {@code {{address_block}}},
     * {@code {{join_button}}}, {@code {{signin_button}}}, {@code {{signin_footer_link}}},
     * {@code {{features_block}}}, {@code {{faq_block}}}.
     *
     * If the file doesn't exist (operator deleted it without restarting), fall
     * back to a minimal inline page so visitors don't see a 500.
     */
    private void renderLanding(Context ctx, Settings s, LandingConfig landing) {
        Path templatePath = services.config.wwwDir().resolve("landing.html");
        String template;
        try {
            template = Files.readString(templatePath);
        } catch (Exception e) {
            services.log.warning("Could not read landing template at " + templatePath + ": " + e.getMessage()
                    + " - falling back to inline minimal landing.");
            template = fallbackInlineLanding();
        }

        int onlineNow = 0;
        int maxPlayers = 0;
        try {
            var server = org.bukkit.Bukkit.getServer();
            if (server != null) {
                onlineNow = server.getOnlinePlayers().size();
                maxPlayers = server.getMaxPlayers();
            }
        } catch (Throwable ignored) {
            // Bukkit static may be unavailable during early-boot edge cases or in tests.
        }

        int discordMembers = 0;
        if (discord != null && discord.jda() != null) {
            try {
                var guild = discord.jda().getGuildById(services.config.discordGuildId());
                if (guild != null) discordMembers = guild.getMemberCount();
            } catch (Throwable ignored) {
                // ditto - just leave it as 0
            }
        }

        // PAPI overrides: if the operator set a placeholder string and PAPI is
        // installed, use the resolved value as the displayed stat. Falls back to
        // the built-in number silently otherwise.
        String playersOnlineStr = Integer.toString(onlineNow);
        String discordMembersStr = Integer.toString(discordMembers);
        String papiPlayers = landing.papiPlayersOnline();
        if (papiPlayers != null && !papiPlayers.isBlank()) {
            String resolved = PapiBridge.resolve(papiPlayers, null);
            if (resolved != null && !resolved.isBlank()) playersOnlineStr = resolved;
        }
        String papiMembers = landing.papiDiscordMembers();
        if (papiMembers != null && !papiMembers.isBlank()) {
            String resolved = PapiBridge.resolve(papiMembers, null);
            if (resolved != null && !resolved.isBlank()) discordMembersStr = resolved;
        }

        String serverName    = blankToDefault(landing.serverName(), "Minecraft Server");
        String tagline       = landing.tagline()       == null ? "" : landing.tagline();
        String serverAddress = landing.serverAddress() == null ? "" : landing.serverAddress();
        String joinUrl       = landing.joinUrl()       == null ? "" : landing.joinUrl();

        String brandImageUrl = resolveBrandImageUrl(landing);
        String heroImageUrl  = landing.heroImageUrl()  == null ? "" : landing.heroImageUrl().trim();

        boolean hasJoin       = !joinUrl.isBlank();
        boolean hasSignin     = !nullOrBlank(services.config.webSessionSecret())
                && services.config.oauthConfigured();
        String signinUrl      = "/auth/discord/start?next=/dash/stats";
        String dashUrl        = "/dash";

        String taglineBlock   = tagline.isBlank()       ? ""
                : "<p class=\"tagline\">" + esc(tagline) + "</p>";
        String addressBlock   = serverAddress.isBlank() ? ""
                : "<p class=\"address\"><span class=\"label\">Server</span> <code>"
                + esc(serverAddress) + "</code></p>";
        // Rendered inside .hero-bg so the image (or autoplay YouTube clip) acts as
        // the hero's background. The scrim layer darkens it so the title text
        // stays legible regardless of how bright the underlying media is.
        String heroImageBlock = buildHeroImageBlock(heroImageUrl);
        String joinButton     = !hasJoin    ? ""
                : "<a class=\"btn primary\" href=\"" + esc(joinUrl) + "\">Join now</a>";
        String signinButton   = !hasSignin  ? ""
                : "<a class=\"btn\" href=\"" + esc(signinUrl) + "\">Sign in to Warden</a>";
        String signinFooterLink = !hasSignin ? ""
                : "<a class=\"footer-link\" href=\"" + esc(signinUrl) + "\">Sign in to Warden</a>"
                  + "<span class=\"footer-dot\" aria-hidden=\"true\">&middot;</span>";

        String featuresBlock = featuresBlockHtml(landing.features());
        String faqBlock      = faqBlockHtml(landing.faqs());
        String promoBlock    = buildPromoBlock(landing.promoVideoUrl());

        // On the landing page we extend the shared nav with in-page anchors for
        // sections that actually have content. Hide them when the operator emptied
        // out the matching list so we don't show a link that scrolls to nothing.
        StringBuilder navLinksBuf = new StringBuilder(navLinksHtml(s, landing));
        if (!featuresBlock.isEmpty()) navLinksBuf.append("<a href=\"#features\">Features</a>");
        if (!faqBlock.isEmpty())      navLinksBuf.append("<a href=\"#faq\">FAQ</a>");
        String navLinks = navLinksBuf.toString();

        String out = template
                .replace("{{server_name}}",     esc(serverName))
                .replace("{{tagline}}",         esc(tagline))
                .replace("{{server_address}}", esc(serverAddress))
                .replace("{{players_online}}", esc(playersOnlineStr))
                .replace("{{players_max}}",    Integer.toString(maxPlayers))
                .replace("{{discord_members}}", esc(discordMembersStr))
                .replace("{{accent_color}}",   esc(landing.accentColorOrDefault()))
                .replace("{{players_label}}",  esc(landing.statPlayersLabelOrDefault()))
                .replace("{{members_label}}",  esc(landing.statMembersLabelOrDefault()))
                .replace("{{join_url}}",       esc(joinUrl))
                .replace("{{signin_url}}",     signinUrl)
                .replace("{{dash_url}}",       dashUrl)
                .replace("{{tagline_block}}",  taglineBlock)
                .replace("{{address_block}}",  addressBlock)
                .replace("{{join_button}}",    joinButton)
                .replace("{{signin_button}}",  signinButton)
                .replace("{{signin_footer_link}}", signinFooterLink)
                .replace("{{features_block}}", featuresBlock)
                .replace("{{promo_block}}",    promoBlock)
                .replace("{{faq_block}}",      faqBlock)
                .replace("{{brand_image_url}}", esc(brandImageUrl))
                .replace("{{hero_image_url}}",  esc(heroImageUrl))
                .replace("{{hero_image_block}}", heroImageBlock)
                .replace("{{nav_links}}",      navLinks);

        out = swapLegacyBrand(out, brandImageUrl);
        out = injectAnalyticsAndConsent(out, landing);

        // Cache hints: never cache the landing HTML itself (live stats); the
        // static /www/landing.css is cached normally by the static handler.
        ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
        ctx.html(out);
    }

    /**
     * Public rules page at /rules. Renders the same Markdown the operator already
     * edits on the dashboard "Rules" tab as styled HTML inside the landing chrome.
     *
     * 404 if rules_markdown is empty - no point publishing a "Rules" page with
     * nothing on it, and we don't want the empty page to dangle in nav.
     */
    public void rulesPage(Context ctx) throws Exception {
        Settings s = services.settingsDao.get();
        LandingConfig landing = s.landing();
        if (landing == null || landing.disabled()) { ctx.status(404).html("<h1>404</h1>"); return; }
        String md = s.rulesMarkdown() == null ? "" : s.rulesMarkdown();
        if (md.isBlank()) {
            ctx.status(404).html("<h1>404</h1><p>No rules have been published.</p>");
            return;
        }

        String template = readTemplate("rules.html", FALLBACK_RULES_HTML);
        String serverName = blankToDefault(landing.serverName(), "Minecraft Server");
        String rulesHtml = MarkdownLite.toHtml(md);
        String brandImageUrl = resolveBrandImageUrl(landing);

        String out = template
                .replace("{{server_name}}",     esc(serverName))
                .replace("{{nav_links}}",       navLinksHtml(s, landing))
                .replace("{{rules_content}}",   rulesHtml)
                .replace("{{brand_image_url}}", esc(brandImageUrl));
        out = swapLegacyBrand(out, brandImageUrl);
        out = injectAnalyticsAndConsent(out, landing);

        ctx.header("Cache-Control", "no-cache");
        ctx.html(out);
    }

    /**
     * Public live-map page at /map. Embeds the operator-configured map URL
     * (Dynmap/Pl3xMap/BlueMap/squaremap/custom) in an iframe with a thin
     * Warden-branded toolbar on top. 404 when the map toggle is off or no URL set.
     */
    public void mapPage(Context ctx) throws Exception {
        Settings s = services.settingsDao.get();
        LandingConfig landing = s.landing();
        if (landing == null || landing.disabled() || !landing.mapReady()) {
            ctx.status(404).html("<h1>404</h1><p>No map has been published.</p>");
            return;
        }
        String mapUrl = landing.mapUrl();
        if (!isSafeHttpUrl(mapUrl)) {
            ctx.status(400).html("<h1>Map URL not allowed</h1>"
                    + "<p>Only http:// and https:// URLs can be embedded.</p>");
            return;
        }

        String template = readTemplate("map.html", FALLBACK_MAP_HTML);
        String serverName = blankToDefault(landing.serverName(), "Minecraft Server");
        String label      = landing.mapLabelOrDefault();
        String providerLabel = mapProviderLabel(landing.mapProvider());
        String brandImageUrl = resolveBrandImageUrl(landing);

        String out = template
                .replace("{{server_name}}",         esc(serverName))
                .replace("{{nav_links}}",           navLinksHtml(s, landing))
                .replace("{{map_label}}",           esc(label))
                .replace("{{map_provider_label}}",  esc(providerLabel))
                .replace("{{map_url}}",             esc(mapUrl))
                .replace("{{brand_image_url}}",     esc(brandImageUrl));
        out = swapLegacyBrand(out, brandImageUrl);
        out = injectAnalyticsAndConsent(out, landing);

        ctx.header("Cache-Control", "no-cache");
        ctx.html(out);
    }

    /**
     * Public leaderboard at /leaderboard. Shows the top N members by XP, with
     * customisable title/description set in /dash/config. 404 if the toggle is off.
     */
    public void leaderboardPage(Context ctx) throws Exception {
        Settings s = services.settingsDao.get();
        LandingConfig landing = s.landing();
        if (landing == null || landing.disabled() || !landing.leaderboardEnabled()) {
            ctx.status(404).html("<h1>404</h1><p>No leaderboard has been published.</p>");
            return;
        }

        String template = readTemplate("leaderboard.html", FALLBACK_LEADERBOARD_HTML);
        String serverName = blankToDefault(landing.serverName(), "Minecraft Server");
        String brandImageUrl = resolveBrandImageUrl(landing);

        int topN = landing.leaderboardTopNOrDefault();
        StringBuilder rows = new StringBuilder();
        try {
            var top = services.levelUserDao.top(topN);
            int rank = 1;
            for (var u : top) {
                String displayName = "Member " + u.discordId();
                try {
                    var existing = services.userDao.findByDiscordId(u.discordId());
                    if (existing.isPresent() && existing.get().username() != null
                            && !existing.get().username().isBlank()) {
                        displayName = existing.get().username();
                    }
                } catch (Exception ignored) {}
                rows.append("<tr><td class=lb-rank>#").append(rank++).append("</td>")
                        .append("<td class=lb-user>").append(esc(displayName)).append("</td>")
                        .append("<td class=lb-level>").append(u.level()).append("</td>")
                        .append("<td class=lb-xp>").append(u.xp()).append("</td>")
                        .append("<td class=lb-msgs>").append(u.messages()).append("</td></tr>");
            }
            if (rows.length() == 0) {
                rows.append("<tr><td colspan=5 class=lb-empty>No members on the leaderboard yet.</td></tr>");
            }
        } catch (Exception e) {
            rows.append("<tr><td colspan=5 class=lb-empty>Leaderboard temporarily unavailable.</td></tr>");
        }

        String out = template
                .replace("{{server_name}}",             esc(serverName))
                .replace("{{nav_links}}",               navLinksHtml(s, landing))
                .replace("{{leaderboard_title}}",       esc(landing.leaderboardTitleOrDefault()))
                .replace("{{leaderboard_description}}", esc(landing.leaderboardDescriptionOrDefault()))
                .replace("{{leaderboard_rows}}",        rows.toString())
                .replace("{{brand_image_url}}",         esc(brandImageUrl));
        out = swapLegacyBrand(out, brandImageUrl);
        out = injectAnalyticsAndConsent(out, landing);

        ctx.header("Cache-Control", "no-cache");
        ctx.html(out);
    }

    /**
     * Pick the brand image URL in this order: explicit setting → Discord guild
     * icon → bundled Warden icon as last resort. The chip always renders.
     */
    private String resolveBrandImageUrl(LandingConfig landing) {
        String url = landing == null || landing.brandImageUrl() == null ? "" : landing.brandImageUrl().trim();
        if (url.isBlank() && discord != null && discord.jda() != null) {
            try {
                var guild = discord.jda().getGuildById(services.config.discordGuildId());
                if (guild != null && guild.getIconUrl() != null) {
                    url = guild.getIconUrl();
                }
            } catch (Throwable ignored) {
                // Best-effort - leave blank for the next fallback.
            }
        }
        if (url.isBlank()) url = "/static/img/warden-icon.svg";
        return url;
    }

    /**
     * Inject Google Analytics (gtag.js) and, optionally, a minimal first-party
     * cookie consent banner into the public-site templates.
     *
     * Both are no-ops when their respective settings are blank/disabled, so the
     * public site ships zero third-party calls and zero banner markup by default.
     *
     * When the cookie banner is enabled together with GA, the page boots gtag
     * with {@code analytics_storage:'denied'} so no GA cookies are written
     * before the visitor clicks Accept. The banner script then calls
     * {@code gtag('consent','update',...)} to unlock GA, mirroring the standard
     * Google "Consent Mode v2" pattern. Choosing Decline simply leaves consent
     * denied; gtag still loads but won't drop identifying cookies.
     *
     * The GA ID is validated by {@link LandingConfig#googleAnalyticsIdOrEmpty()}
     * before reaching the template, so it's safe to inline into the script URL.
     */
    private static String injectAnalyticsAndConsent(String html, LandingConfig landing) {
        if (html == null || landing == null) return html;
        boolean gaOn      = landing.googleAnalyticsEnabled();
        boolean bannerOn  = landing.cookieBannerEnabled();
        if (!gaOn && !bannerOn) return html;

        StringBuilder head = new StringBuilder(512);
        if (gaOn) {
            String id = landing.googleAnalyticsIdOrEmpty();
            head.append("<!-- Google tag (gtag.js) -->")
                .append("<script async src=\"https://www.googletagmanager.com/gtag/js?id=")
                .append(esc(id)).append("\"></script>")
                .append("<script>window.dataLayer=window.dataLayer||[];")
                .append("function gtag(){dataLayer.push(arguments);}")
                .append("gtag('js',new Date());");
            if (bannerOn) {
                head.append("gtag('consent','default',{")
                    .append("'analytics_storage':'denied',")
                    .append("'ad_storage':'denied',")
                    .append("'ad_user_data':'denied',")
                    .append("'ad_personalization':'denied'");
                head.append("});");
            }
            head.append("gtag('config','").append(esc(id)).append("');</script>");
        }
        String withHead = insertBefore(html, "</head>", head.toString());

        if (!bannerOn) return withHead;

        // Local-only consent: stored in localStorage so we don't drop our own
        // tracking cookie just to remember the visitor's choice. Banner hides
        // itself once a choice has been made.
        String banner =
                "<div id=\"warden-cookie-banner\" role=\"region\" aria-label=\"Cookie notice\"" +
                " style=\"display:none;position:fixed;left:1rem;right:1rem;bottom:1rem;z-index:9999;" +
                "max-width:38rem;margin:0 auto;background:#1d1f2a;color:#eaeaea;" +
                "border-radius:.6rem;padding:.95rem 1.1rem;font:14px/1.45 -apple-system,system-ui,sans-serif;" +
                "box-shadow:0 8px 28px rgba(0,0,0,.35)\">" +
                "<p style=\"margin:0 0 .6rem\">We use cookies for site analytics. " +
                "Click Accept to allow them, or Decline to keep them off. " +
                "You can change your choice any time by clearing site data.</p>" +
                "<div style=\"display:flex;gap:.5rem;flex-wrap:wrap;justify-content:flex-end\">" +
                "<button type=\"button\" id=\"warden-cookie-decline\"" +
                " style=\"background:transparent;color:#eaeaea;border:1px solid rgba(255,255,255,.25);" +
                "padding:.4rem .85rem;border-radius:.35rem;cursor:pointer;font:inherit\">Decline</button>" +
                "<button type=\"button\" id=\"warden-cookie-accept\"" +
                " style=\"background:#6b83ff;color:#fff;border:0;padding:.45rem .95rem;" +
                "border-radius:.35rem;cursor:pointer;font:inherit;font-weight:600\">Accept</button>" +
                "</div></div>" +
                "<script>(function(){" +
                "var KEY='warden.cookieConsent';" +
                "var el=document.getElementById('warden-cookie-banner');if(!el)return;" +
                "var saved=null;try{saved=localStorage.getItem(KEY);}catch(_){}" +
                "function grant(){try{if(window.gtag)gtag('consent','update',{" +
                "'analytics_storage':'granted'});}catch(_){}}" +
                "if(saved==='granted'){grant();return;}" +
                "if(saved==='denied'){return;}" +
                "el.style.display='block';" +
                "document.getElementById('warden-cookie-accept').addEventListener('click',function(){" +
                "try{localStorage.setItem(KEY,'granted');}catch(_){};grant();el.style.display='none';});" +
                "document.getElementById('warden-cookie-decline').addEventListener('click',function(){" +
                "try{localStorage.setItem(KEY,'denied');}catch(_){};el.style.display='none';});" +
                "})();</script>";
        return insertBefore(withHead, "</body>", banner);
    }

    /** Insert {@code payload} immediately before the first case-insensitive {@code marker}; appends if missing. */
    private static String insertBefore(String html, String marker, String payload) {
        if (html == null || payload == null || payload.isEmpty()) return html;
        int idx = indexOfIgnoreCase(html, marker);
        if (idx < 0) return html + payload;
        return html.substring(0, idx) + payload + html.substring(idx);
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        int hLen = haystack.length();
        int nLen = needle.length();
        for (int i = 0; i <= hLen - nLen; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nLen)) return i;
        }
        return -1;
    }

    /**
     * Back-compat: older unpacked templates (rules.html, map.html, landing.html)
     * hard-coded the Warden icon path in {@code <img src=...>} tags. Swap them
     * for the resolved brand URL so users who unpacked the v1 templates pick
     * up their guild icon without manually editing files.
     *
     * Skips when the resolved brand IS the warden icon - that's the fallback.
     */
    private static String swapLegacyBrand(String html, String brandImageUrl) {
        if ("/static/img/warden-icon.svg".equals(brandImageUrl)) return html;
        return html.replace("src=\"/static/img/warden-icon.svg\"", "src=\"" + esc(brandImageUrl) + "\"");
    }

    private String readTemplate(String filename, String fallback) {
        Path templatePath = services.config.wwwDir().resolve(filename);
        try {
            return Files.readString(templatePath);
        } catch (Exception e) {
            services.log.warning("Could not read template at " + templatePath + ": " + e.getMessage()
                    + " - falling back to inline minimal page.");
            return fallback;
        }
    }

    /**
     * Build the conditional nav-link strip used in landing.html, rules.html, and map.html.
     * Home is always present; Rules and Map are shown only when their feature is configured.
     */
    private static String navLinksHtml(Settings s, LandingConfig landing) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<a href=\"/\">Home</a>");
        boolean hasRules = s != null && s.rulesMarkdown() != null && !s.rulesMarkdown().isBlank();
        if (hasRules) sb.append("<a href=\"/rules\">Rules</a>");
        if (landing != null && landing.mapReady()) {
            sb.append("<a href=\"/map\">").append(esc(landing.mapLabelOrDefault())).append("</a>");
        }
        if (landing != null && landing.leaderboardEnabled()) {
            sb.append("<a href=\"/leaderboard\">").append(esc(landing.leaderboardLabelOrDefault())).append("</a>");
        }
        return sb.toString();
    }

    private static String mapProviderLabel(String provider) {
        if (provider == null) return "Live map";
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "dynmap"    -> "Dynmap";
            case "pl3xmap"   -> "Pl3xMap";
            case "bluemap"   -> "BlueMap";
            case "squaremap" -> "squaremap";
            case "custom"    -> "Live map";
            default          -> "Live map";
        };
    }

    /**
     * Render the configurable feature-card grid. Returns an empty string when
     * the operator has no features set, so the landing template can omit the
     * section entirely rather than show an empty grid.
     */
    private static String featuresBlockHtml(List<LandingFeature> features) {
        if (features == null || features.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<section class=\"section\" id=\"features\"><div class=\"container\">");
        sb.append("<p class=\"eyebrow\"><span class=\"eyebrow-mark\">//</span> What you get</p>");
        sb.append("<h2 class=\"section-title\">A server built to stick around.</h2>");
        sb.append("<div class=\"feature-grid\">");
        for (LandingFeature f : features) {
            String title = f.title() == null ? "" : f.title();
            String body  = f.body()  == null ? "" : f.body();
            if (title.isBlank() && body.isBlank()) continue;
            sb.append("<article class=\"feature\">");
            sb.append("<div class=\"feature-ico\" aria-hidden=\"true\">").append(iconSvg(f.icon())).append("</div>");
            sb.append("<h3>").append(esc(title)).append("</h3>");
            sb.append("<p>").append(esc(body)).append("</p>");
            sb.append("</article>");
        }
        sb.append("</div></div></section>");
        return sb.toString();
    }

    /** Render the configurable FAQ accordion. Empty list = section omitted. */
    private static String faqBlockHtml(List<LandingFaq> faqs) {
        if (faqs == null || faqs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<section class=\"section\" id=\"faq\"><div class=\"container\">");
        sb.append("<p class=\"eyebrow\"><span class=\"eyebrow-mark\">//</span> Common questions</p>");
        sb.append("<h2 class=\"section-title\">Before you join.</h2>");
        sb.append("<div class=\"faq-list\">");
        for (LandingFaq q : faqs) {
            String qt = q.question() == null ? "" : q.question();
            String an = q.answer()   == null ? "" : q.answer();
            if (qt.isBlank() && an.isBlank()) continue;
            sb.append("<details class=\"faq\"><summary>").append(esc(qt)).append("</summary>");
            sb.append("<p>").append(esc(an)).append("</p></details>");
        }
        sb.append("</div></div></section>");
        return sb.toString();
    }

    /**
     * Map a feature icon token (from {@link LandingFeature#ICONS}) to an inline
     * SVG. Unknown tokens render a neutral dot so a bad value never breaks the
     * page or leaks raw text into the markup.
     */
    private static String iconSvg(String token) {
        String t = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        String path = switch (t) {
            case "shield"   -> "<path d=\"M12 2 4 5v6c0 5 3.5 9 8 11 4.5-2 8-6 8-11V5l-8-3z\"/>";
            case "sun"      -> "<circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M3 12h2M19 12h2M12 3v2M12 19v2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4\"/>";
            case "shop"     -> "<path d=\"M3 7h18M3 12h18M3 17h12\"/>";
            case "globe"    -> "<path d=\"M12 3v18M3 12h18\"/><circle cx=\"12\" cy=\"12\" r=\"9\"/>";
            case "users"    -> "<path d=\"M17 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2\"/><circle cx=\"10\" cy=\"7\" r=\"4\"/><path d=\"M21 21v-2a4 4 0 0 0-3-3.87M17 3.13a4 4 0 0 1 0 7.75\"/>";
            case "grid"     -> "<path d=\"M4 12h16M12 4v16\"/><rect x=\"3\" y=\"3\" width=\"18\" height=\"18\" rx=\"3\"/>";
            case "sparkles" -> "<path d=\"M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8\"/>";
            case "sword"    -> "<path d=\"m14.5 17.5 3-3M3 21l5-2 12-12-3-3L5 16l-2 5z\"/>";
            case "pickaxe"  -> "<path d=\"M14 4a8 8 0 0 1 6 6l-3 3-6-6 3-3zM11 7 3 21\"/>";
            case "chat"     -> "<path d=\"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z\"/>";
            case "lock"     -> "<rect x=\"4\" y=\"11\" width=\"16\" height=\"10\" rx=\"2\"/><path d=\"M8 11V7a4 4 0 0 1 8 0v4\"/>";
            case "map"      -> "<path d=\"M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2z\"/><path d=\"M9 4v14M15 6v14\"/>";
            case "trophy"   -> "<path d=\"M8 21h8M12 17v4M7 4h10v4a5 5 0 0 1-10 0V4z\"/><path d=\"M7 6H4v2a3 3 0 0 0 3 3M17 6h3v2a3 3 0 0 1-3 3\"/>";
            case "heart"    -> "<path d=\"M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z\"/>";
            default          -> "<circle cx=\"12\" cy=\"12\" r=\"4\"/>";
        };
        return "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\""
                + " stroke-linecap=\"round\" stroke-linejoin=\"round\">" + path + "</svg>";
    }

    /** Allow only http:// and https:// URLs in the map embed - blocks javascript:, data:, file:. */
    private static boolean isSafeHttpUrl(String url) {
        if (url == null) return false;
        String t = url.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /**
     * Build the markup that fills .hero-bg with the operator-supplied hero
     * media. A plain image URL becomes an &lt;img&gt;; a YouTube URL becomes a
     * muted, looping, controls-off iframe that plays in the background behind
     * the hero text. Anything else (or a blank value) renders nothing.
     */
    private static String buildHeroImageBlock(String heroImageUrl) {
        if (heroImageUrl == null || heroImageUrl.isBlank()) return "";
        String ytId = extractYouTubeId(heroImageUrl);
        if (ytId != null) {
            // loop=1 only works on the YouTube embed when playlist=<same id>
            // is also set, which tells the player to re-queue the clip.
            String src = "https://www.youtube-nocookie.com/embed/" + ytId
                    + "?autoplay=1&mute=1&controls=0&loop=1&playlist=" + ytId
                    + "&modestbranding=1&playsinline=1&rel=0&iv_load_policy=3"
                    + "&disablekb=1&fs=0";
            return "<div class=\"hero-image-bg hero-image-bg-video\" aria-hidden=\"true\">"
                    + "<iframe src=\"" + esc(src) + "\" "
                    + "frameborder=\"0\" "
                    + "allow=\"autoplay; encrypted-media; picture-in-picture\" "
                    + "tabindex=\"-1\" title=\"\" loading=\"eager\"></iframe>"
                    + "<div class=\"hero-image-scrim\"></div>"
                    + "</div>";
        }
        return "<div class=\"hero-image-bg\" aria-hidden=\"true\">"
                + "<img src=\"" + esc(heroImageUrl) + "\" alt=\"\" loading=\"eager\">"
                + "<div class=\"hero-image-scrim\"></div>"
                + "</div>";
    }

    /**
     * Build the standalone YouTube promo section. Returns an empty string when
     * the URL is blank or doesn't parse as a YouTube link, which collapses the
     * section out of the template entirely.
     */
    private static String buildPromoBlock(String promoVideoUrl) {
        if (promoVideoUrl == null || promoVideoUrl.isBlank()) return "";
        String ytId = extractYouTubeId(promoVideoUrl);
        if (ytId == null) return "";
        String src = "https://www.youtube-nocookie.com/embed/" + ytId
                + "?rel=0&modestbranding=1&playsinline=1&iv_load_policy=3";
        StringBuilder sb = new StringBuilder(512);
        sb.append("<section class=\"section\" id=\"promo\">");
        sb.append("<div class=\"container promo\">");
        sb.append("<div class=\"promo-frame\">");
        sb.append("<iframe src=\"").append(esc(src)).append("\" ");
        sb.append("frameborder=\"0\" ");
        sb.append("allow=\"accelerometer; encrypted-media; gyroscope; picture-in-picture\" ");
        sb.append("allowfullscreen loading=\"lazy\" title=\"Featured video\"></iframe>");
        sb.append("</div>");
        sb.append("</div>");
        sb.append("</section>");
        return sb.toString();
    }

    /**
     * Pull an 11-char YouTube video id out of any of the common URL shapes
     * (watch, youtu.be, embed, shorts, /v/). Returns null when the URL is not
     * a YouTube link or no valid id can be found.
     */
    private static final java.util.regex.Pattern YOUTUBE_ID_RE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{11}");

    static String extractYouTubeId(String url) {
        if (url == null) return null;
        String t = url.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return null;
        try {
            java.net.URI u = java.net.URI.create(t);
            String host = u.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            else if (host.startsWith("m.")) host = host.substring(2);
            String path = u.getPath() == null ? "" : u.getPath();
            String query = u.getQuery() == null ? "" : u.getQuery();
            String id = null;
            if (host.equals("youtu.be")) {
                if (path.length() > 1) id = path.substring(1);
            } else if (host.equals("youtube.com") || host.equals("youtube-nocookie.com")) {
                if (path.equals("/watch") || path.startsWith("/watch/")) {
                    for (String p : query.split("&")) {
                        if (p.startsWith("v=")) { id = p.substring(2); break; }
                    }
                } else if (path.startsWith("/embed/")) {
                    id = path.substring("/embed/".length());
                } else if (path.startsWith("/shorts/")) {
                    id = path.substring("/shorts/".length());
                } else if (path.startsWith("/v/")) {
                    id = path.substring("/v/".length());
                } else if (path.startsWith("/live/")) {
                    id = path.substring("/live/".length());
                }
            }
            if (id == null) return null;
            int slash = id.indexOf('/');
            if (slash >= 0) id = id.substring(0, slash);
            int q = id.indexOf('?');
            if (q >= 0) id = id.substring(0, q);
            if (!YOUTUBE_ID_RE.matcher(id).matches()) return null;
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private static final String FALLBACK_RULES_HTML =
            "<!doctype html><html><head><meta charset=utf-8><title>Rules &middot; {{server_name}}</title></head>"
            + "<body><h1>Rules</h1><div>{{rules_content}}</div>"
            + "<p>(plugins/Warden/www/rules.html missing - restart to restore)</p></body></html>";

    private static final String FALLBACK_MAP_HTML =
            "<!doctype html><html><head><meta charset=utf-8><title>{{map_label}} &middot; {{server_name}}</title>"
            + "<style>body,html{margin:0;height:100%}iframe{border:0;width:100%;height:100%}</style>"
            + "</head><body><iframe src=\"{{map_url}}\"></iframe></body></html>";

    private static final String FALLBACK_LEADERBOARD_HTML =
            "<!doctype html><html><head><meta charset=utf-8><title>{{leaderboard_title}} &middot; {{server_name}}</title></head>"
            + "<body><h1>{{leaderboard_title}}</h1><p>{{leaderboard_description}}</p>"
            + "<table><thead><tr><th>#</th><th>Member</th><th>Level</th><th>XP</th><th>Messages</th></tr></thead>"
            + "<tbody>{{leaderboard_rows}}</tbody></table>"
            + "<p>(plugins/Warden/www/leaderboard.html missing - restart to restore)</p></body></html>";

    private static String fallbackInlineLanding() {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<title>Home &middot; {{server_name}}</title></head><body style=\"font-family:sans-serif;"
                + "max-width:560px;margin:4rem auto;padding:0 1rem;text-align:center\">"
                + "<h1>{{server_name}}</h1>"
                + "{{tagline_block}}{{address_block}}"
                + "<p>{{players_online}}/{{players_max}} players online &middot; "
                + "{{discord_members}} Discord members</p>"
                + "<p>{{join_button}}</p>"
                + "{{features_block}}"
                + "{{faq_block}}"
                + "<p style=\"color:#888;font-size:.85em\">{{signin_footer_link}}</p>"
                + "<p style=\"color:#888;font-size:.85em\">(plugins/Warden/www/landing.html missing - "
                + "restart to restore the default template)</p>"
                + "</body></html>";
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String row(boolean done, String title, String body, String hintHeader, String hintBody) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<article class=\"step ").append(done ? "done" : "todo").append("\">");
        sb.append("<div class=tick>").append(done ? "&#10003;" : "&middot;").append("</div>");
        sb.append("<div class=body>");
        sb.append("<h3>").append(esc(title)).append("</h3>");
        sb.append("<p>").append(body).append("</p>");
        if (hintHeader != null && hintBody != null) {
            sb.append("<details><summary>").append(esc(hintHeader)).append("</summary>");
            sb.append("<div class=hint>").append(hintBody).append("</div>");
            sb.append("</details>");
        }
        sb.append("</div></article>");
        return sb.toString();
    }

    private static String head(String title) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>" +
                "<meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<title>" + esc(title) + "</title>" +
                Layout.THEME_BOOT_SCRIPT +
                "<link rel=icon type=\"image/svg+xml\" href=/static/img/warden-icon.svg>" +
                "<style>" + CSS + "</style></head><body>";
    }

    /**
     * Resolve a Discord guild id to its name via the JDA cache. Falls back to the
     * raw id when the bot isn't connected yet so the operator can still verify
     * what is configured.
     */
    private String guildLabel(String guildId) {
        if (guildId == null || guildId.isBlank()) return "";
        if (discord != null) {
            var jda = discord.jda();
            if (jda != null) {
                try {
                    var g = jda.getGuildById(guildId);
                    if (g != null) return g.getName();
                } catch (Exception ignored) {
                }
            }
        }
        return guildId;
    }

    /**
     * Resolve a Discord role id to a friendly role name via the JDA cache. Falls
     * back to the raw id when the bot can't see the guild yet (early setup) so
     * the operator can still tell whether a value is configured.
     */
    private String roleLabel(String roleId) {
        if (roleId == null || roleId.isBlank()) return "";
        if (discord != null) {
            var jda = discord.jda();
            if (jda != null) {
                try {
                    var role = jda.getRoleById(roleId);
                    if (role != null) return role.getName();
                } catch (Exception ignored) {
                }
            }
        }
        return roleId;
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

    private static boolean nullOrBlank(String s) { return s == null || s.isBlank(); }

    private static String plugin_version() { return "0.1.0"; }

    private static final String CSS = """
            :root{color-scheme:light dark;
              --brand:#6b83ff; --brand-deep:#4a64e6;
              --ok:#4caf50; --warn:#f6b73c;
              --bg:#f5f6fa; --card-bg:#fff; --border:#e2e4ea;
              --text:#1d1f2a; --muted:#6c7080;
              --tick-bg:#dde0ee;
            }
            :root[data-theme=dark]{--bg:#0f1014;--card-bg:#181a21;--border:#2a2c33;--text:#eaeaea;--muted:#9ca0ac;
                  --tick-bg:#2a2c33}
            @media (prefers-color-scheme:dark){
              :root:not([data-theme=light]){--bg:#0f1014;--card-bg:#181a21;--border:#2a2c33;--text:#eaeaea;--muted:#9ca0ac;
                    --tick-bg:#2a2c33}
            }
            *,*::before,*::after{box-sizing:border-box}
            body{margin:0;font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;
                 background:var(--bg);color:var(--text);min-height:100vh;padding:2.5rem 1.5rem}
            header.hero{max-width:760px;margin:0 auto 2rem;display:flex;gap:1.5rem;align-items:center}
            header.hero .logo{flex:0 0 auto;border-radius:14px}
            header.hero h1{margin:0 0 .3rem;font-size:1.75rem}
            header.hero p.lede{margin:0;color:var(--muted);font-size:1.05rem}
            section.card{max-width:760px;margin:0 auto 1.5rem;background:var(--card-bg);
                         border:1px solid var(--border);border-radius:12px;padding:1.5rem 1.75rem;
                         box-shadow:0 1px 2px rgba(0,0,0,.04)}
            section.card h2{margin:0 0 1rem;font-size:1.1rem;color:var(--muted);text-transform:uppercase;
                            letter-spacing:.06em;font-weight:600}
            article.step{display:flex;gap:1rem;padding:.85rem 0;border-bottom:1px solid var(--border)}
            article.step:last-child{border-bottom:0}
            article.step .tick{flex:0 0 auto;width:1.85rem;height:1.85rem;border-radius:50%;
                               display:flex;align-items:center;justify-content:center;font-weight:700;
                               background:var(--tick-bg);color:var(--muted)}
            article.step.done .tick{background:var(--ok);color:white}
            article.step .body{flex:1 1 auto;min-width:0}
            article.step h3{margin:0 0 .2rem;font-size:1rem}
            article.step p{margin:0 0 .3rem;color:var(--muted)}
            article.step.done h3::after{content:" \\2713";color:var(--ok);font-weight:700;font-size:.85em;letter-spacing:.05em}
            code{background:rgba(107,131,255,.12);padding:.1em .4em;border-radius:3px;font-size:.92em}
            code.copyable{user-select:all;padding:.45em .85em;background:rgba(107,131,255,.16);
                          font-family:ui-monospace,SFMono-Regular,Menlo,monospace;display:inline-block;margin:.5rem 0}
            details{margin-top:.5rem}
            details summary{cursor:pointer;color:var(--brand-deep);font-size:.92em}
            details .hint{margin-top:.5rem;padding:.6rem .9rem;background:rgba(107,131,255,.06);border-radius:6px;font-size:.94em}
            details .hint a{color:var(--brand-deep)}
            section.actions{display:flex;align-items:center;flex-wrap:wrap;gap:.75rem}
            section.actions p{margin:0;color:var(--muted)}
            .btn{display:inline-block;padding:.7em 1.4em;border-radius:8px;text-decoration:none;
                 font-weight:600;background:transparent;color:var(--text);
                 border:1px solid var(--border);cursor:pointer;font-size:1em;font-family:inherit}
            .btn:hover{background:rgba(127,127,127,.08)}
            .btn.primary{background:var(--brand);color:#fff;border-color:var(--brand)}
            .btn.primary:hover{background:var(--brand-deep);border-color:var(--brand-deep)}
            .hint{color:var(--muted);font-size:.92em}
            footer.meta{max-width:760px;margin:1rem auto 0;color:var(--muted);font-size:.85em;text-align:center}
            footer.meta a{color:var(--muted)}
            @media (max-width:520px){
              header.hero{flex-direction:column;text-align:center;align-items:center}
              section.card{padding:1.25rem}
            }
            """;
}
