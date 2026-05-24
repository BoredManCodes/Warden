package io.warden.web;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.warden.WardenPlugin;
import io.warden.Services;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.DiscordOAuth;
import io.warden.web.auth.PageAccess;
import io.warden.web.auth.PageAccessDao;
import io.warden.web.auth.SessionCookie;
import io.warden.web.handlers.Layout;
import io.warden.web.handlers.DashAlertsHandlers;
import io.warden.web.handlers.DashAuditHandlers;
import io.warden.web.handlers.DashAutoresponderHandlers;
import io.warden.web.handlers.DashConfigHandlers;
import io.warden.web.handlers.DashSchedulerHandlers;
import io.warden.web.handlers.TimezoneHandlers;
import io.warden.web.handlers.DashEngagementHandlers;
import io.warden.web.handlers.DashGrimHandlers;
import io.warden.web.handlers.DashInvitesHandlers;
import io.warden.web.handlers.DashLevelsHandlers;
import io.warden.web.handlers.DashMembersHandlers;
import io.warden.web.handlers.DashModerationHandlers;
import io.warden.web.handlers.DashPendingHandlers;
import io.warden.web.handlers.DashReactionRolesHandlers;
import io.warden.web.handlers.DashAboutHandlers;
import io.warden.web.handlers.DashFeedbackHandlers;
import io.warden.web.handlers.DashHttpsHandlers;
import io.warden.web.handlers.DashPermissionsHandlers;
import io.warden.web.handlers.DashStatsHandlers;
import io.warden.web.handlers.DashTicketsHandlers;
import io.warden.web.handlers.GuildLookup;
import io.warden.web.handlers.OAuthHandlers;
import io.warden.web.handlers.OnboardHandlers;
import io.warden.web.handlers.SetupHandlers;
import io.warden.web.handlers.TranscriptPublicHandlers;
import io.warden.web.handlers.WelcomeHandlers;
import io.warden.web.ssl.SslSupport;
import io.warden.api.ApiV1Handlers;
import io.warden.web.handlers.DashApiKeysHandlers;
import io.warden.web.handlers.DashDebugHandlers;
import io.warden.web.handlers.DebugViewHandlers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the Javalin lifecycle. M1 skeleton: serves a placeholder root page
 * and the static assets at /static/*. Real routes get wired in M3+.
 */
public final class WebService {

    private final WardenPlugin plugin;
    private final Services services;
    private final Logger log;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean sslActive = new AtomicBoolean(false);
    private volatile String sslLoadError = null;

    private Javalin app;

    public WebService(WardenPlugin plugin, Services services) {
        this.plugin = plugin;
        this.services = services;
        this.log = plugin.getLogger();
    }

    public void start() {
        // Use the plugin classloader so Javalin/Jetty can find their classes when called
        // from threads that don't have the right context loader.
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            // Unpack the default landing template under plugins/Warden/www/ if
            // it's not there yet. Has to happen before Javalin starts since the
            // static-files mount is locked in at creation time.
            java.nio.file.Path wwwDir = services.config.wwwDir();
            ensureDefaultWww(wwwDir);

            this.app = Javalin.create(cfg -> {
                cfg.showJavalinBanner = false;
                cfg.jetty.defaultHost = services.config.webBindHost();
                cfg.staticFiles.add(staticDir -> {
                    staticDir.hostedPath = "/static";
                    staticDir.directory = "/web-static";
                    staticDir.location = Location.CLASSPATH;
                });
                // /www/* serves the admin-editable landing CSS/HTML/assets.
                cfg.staticFiles.add(staticDir -> {
                    staticDir.hostedPath = "/www";
                    staticDir.directory = wwwDir.toAbsolutePath().toString();
                    staticDir.location = Location.EXTERNAL;
                });
                wireHttpsConnector(cfg);
            });

            // HTTPS-on / redirect_http behaviour: anything that arrives on the
            // plain HTTP connector while SSL is active 301s to the same path on
            // the configured public_url. Registered before any route so it
            // wins over static-file serving and everything below.
            if (sslActive.get() && services.config.ssl().redirectHttp()) {
                String publicBase = services.config.webPublicUrl();
                app.before(ctx -> {
                    if (!"https".equalsIgnoreCase(ctx.scheme())) {
                        String q = ctx.queryString();
                        String target = publicBase + ctx.path() + (q == null || q.isEmpty() ? "" : "?" + q);
                        ctx.redirect(target, io.javalin.http.HttpStatus.MOVED_PERMANENTLY);
                        ctx.skipRemainingHandlers();
                    }
                });
            }

            SessionCookie sessionForWelcome =
                    (services.config.webSessionSecret() != null && !services.config.webSessionSecret().isBlank())
                            ? new SessionCookie(services.config.webSessionSecret()) : null;
            WelcomeHandlers welcome = new WelcomeHandlers(services, plugin.discordService(), sessionForWelcome);
            app.get("/", wrap(welcome::index));
            app.get("/rules", wrap(welcome::rulesPage));
            app.get("/map",   wrap(welcome::mapPage));
            app.get("/leaderboard", wrap(welcome::leaderboardPage));

            app.get("/health", ctx -> ctx.json(java.util.Map.of(
                    "status", "ok",
                    "discord", plugin.discordService() != null && plugin.discordService().isReady(),
                    // Monotonic counter, bumped on every bootstrap(). The Restart
                    // modal in the dashboard captures this before kicking the
                    // restart and waits for it to change as the "new instance is
                    // up" signal - the /health downtime window is too short
                    // (sub-second, in-process) to reliably catch otherwise.
                    "boot_id", plugin.bootId()
            )));

            // /api/v1/* - bearer-token-authed JSON API. Routes pin their own
            // scope checks; the index endpoint is the only one that responds
            // without a token (it advertises the surface so integrators can
            // discover what's available). Module-specific routes are skipped
            // when their module is disabled in config.yml; requests to those
            // paths then 404 instead of authenticating.
            var mods = services.config.modules();
            ApiV1Handlers api = new ApiV1Handlers(services, plugin.discordService());
            app.get ("/api/v1",                                wrap(api::index));
            app.get ("/api/v1/openapi.json",                   wrap(api::openapiJson));
            app.get ("/api/docs",                              wrap(api::swaggerUi));
            app.get ("/api/v1/health",                         wrap(api::health));
            app.get ("/api/v1/members",                        wrap(api::listMembers));
            app.get ("/api/v1/members/{discordId}",            wrap(api::getMember));
            app.get ("/api/v1/audit",                          wrap(api::listAudit));
            app.get ("/api/v1/onboarding/pending",             wrap(api::listPending));
            app.get ("/api/v1/onboarding/applications",        wrap(api::listApplications));
            app.get ("/api/v1/onboarding/applications/{id}",   wrap(api::getApplication));
            app.post("/api/v1/onboarding/applications/{id}/approve", wrap(api::approveApplication));
            app.post("/api/v1/onboarding/applications/{id}/deny",    wrap(api::denyApplication));
            if (mods.moderation()) {
                app.get ("/api/v1/moderation/warnings",            wrap(api::listWarnings));
                app.post("/api/v1/moderation/warnings",            wrap(api::createWarning));
                app.post("/api/v1/moderation/warnings/{id}/clear", wrap(api::clearWarning));
                app.get ("/api/v1/moderation/actions",             wrap(api::listModActions));
                app.post("/api/v1/moderation/actions/kick",        wrap(api::kickMember));
                app.post("/api/v1/moderation/actions/ban",         wrap(api::banMember));
                app.post("/api/v1/moderation/actions/tempban",     wrap(api::tempbanMember));
                app.post("/api/v1/moderation/actions/timeout",     wrap(api::timeoutMember));
                app.post("/api/v1/moderation/actions/unban",       wrap(api::unbanMember));
            }
            if (mods.levels()) {
                app.get ("/api/v1/levels/leaderboard",             wrap(api::leaderboard));
                app.get ("/api/v1/levels/{discordId}",             wrap(api::getLevel));
                app.post("/api/v1/levels/{discordId}/xp",          wrap(api::grantXp));
                app.post("/api/v1/levels/{discordId}/level",       wrap(api::setLevel));
                app.post("/api/v1/levels/{discordId}/reset",       wrap(api::resetLevel));
            }
            if (mods.tickets()) {
                app.get ("/api/v1/tickets",                        wrap(api::listTickets));
                app.get ("/api/v1/tickets/{id}",                   wrap(api::getTicket));
                app.post("/api/v1/tickets/{id}/reply",             wrap(api::replyToTicket));
                app.post("/api/v1/tickets/{id}/internal-note",     wrap(api::ticketInternalNote));
                app.post("/api/v1/tickets/{id}/status",            wrap(api::ticketStatus));
                app.post("/api/v1/tickets/{id}/assign",            wrap(api::ticketAssign));
            }
            if (mods.feedback()) {
                app.get ("/api/v1/feedback",                       wrap(api::listFeedback));
                app.post("/api/v1/feedback/{id}/status",           wrap(api::feedbackStatus));
                app.post("/api/v1/feedback/{id}/respond",          wrap(api::feedbackRespond));
                app.post("/api/v1/feedback/{id}/delete",           wrap(api::feedbackDelete));
            }
            if (mods.reactionRoles()) {
                app.get ("/api/v1/reaction-roles",                 wrap(api::listReactionRoles));
            }
            if (mods.engagement()) {
                app.get ("/api/v1/engagement/polls",               wrap(api::listPolls));
                app.post("/api/v1/engagement/polls",               wrap(api::createPoll));
                app.post("/api/v1/engagement/polls/{id}/close",    wrap(api::closePoll));
                app.get ("/api/v1/engagement/giveaways",           wrap(api::listGiveaways));
                app.post("/api/v1/engagement/giveaways",           wrap(api::createGiveaway));
                app.post("/api/v1/engagement/giveaways/{id}/draw",   wrap(api::drawGiveaway));
                app.post("/api/v1/engagement/giveaways/{id}/cancel", wrap(api::cancelGiveaway));
            }
            if (mods.autoresponders()) {
                app.get ("/api/v1/autoresponders",                 wrap(api::listAutoresponders));
                app.post("/api/v1/autoresponders/{id}/toggle",     wrap(api::toggleAutoresponder));
                app.post("/api/v1/autoresponders/{id}/delete",     wrap(api::deleteAutoresponder));
            }
            if (mods.alerts()) {
                app.get ("/api/v1/alerts",                         wrap(api::listAlerts));
                app.post("/api/v1/alerts/{id}/toggle",             wrap(api::toggleAlert));
                app.post("/api/v1/alerts/{id}/delete",             wrap(api::deleteAlert));
            }
            if (mods.eventsTimezones()) {
                app.get ("/api/v1/scheduler/events",               wrap(api::listScheduledEvents));
                app.post("/api/v1/scheduler/events/{id}/cancel",   wrap(api::cancelScheduledEvent));
                app.get ("/api/v1/timezones/{discordId}",          wrap(api::getTimezone));
                app.post("/api/v1/timezones/{discordId}",          wrap(api::setTimezone));
                app.post("/api/v1/timezones/{discordId}/clear",    wrap(api::clearTimezone));
            }
            app.get ("/api/v1/analytics/overview",             wrap(api::analyticsOverview));

            // Minecraft player snapshot endpoints. Always on - the bearer
            // token's READ_MC_PLAYERS scope is what gates access.
            app.get ("/api/v1/players/{username}",             wrap(api::mcPlayer));
            app.get ("/api/v1/discord/name/{username}",        wrap(api::mcDiscordByName));
            app.get ("/api/v1/discord/id/{id}",                wrap(api::mcDiscordById));

            // Token-gated public transcript endpoints. Reporters reach these via
            // the DM link sent when their ticket closes; no dashboard session
            // required - the token itself is the credential.
            services.tickets.setPublicBaseUrl(services.config.webPublicUrl());
            TranscriptPublicHandlers transcriptPub = new TranscriptPublicHandlers(
                    services.ticketDao, services.ticketTranscripts);
            app.get("/tickets/transcript/{token}", wrap(transcriptPub::view));
            app.get("/tickets/transcript-asset/{ticketId}/{messageId}/{name}", wrap(transcriptPub::asset));

            // /dash/* - dashboard surfaces. Auth gate first, then PageAccess is
            // pinned to the request so handlers + Layout can render lock states.
            DashAuth auth = new DashAuth(services.config, log);
            PageAccess pageAccess = new PageAccess(services.pageAccessDao, log);
            DashPendingHandlers pending = new DashPendingHandlers(services);

            app.before("/dash", ctx -> {
                if (!auth.check(ctx)) { ctx.skipRemainingHandlers(); return; }
                ctx.attribute(Layout.CTX_PAGE_ACCESS, pageAccess);
                ctx.attribute(Layout.CTX_MODULES, services.config.modules());
                if (needsTimezoneOnboarding(ctx)) {
                    ctx.redirect("/tz?required=1");
                    ctx.skipRemainingHandlers();
                }
            });
            app.before("/dash/*", ctx -> {
                if (!auth.check(ctx)) { ctx.skipRemainingHandlers(); return; }
                ctx.attribute(Layout.CTX_PAGE_ACCESS, pageAccess);
                ctx.attribute(Layout.CTX_MODULES, services.config.modules());
                if (needsTimezoneOnboarding(ctx)) {
                    ctx.redirect("/tz?required=1");
                    ctx.skipRemainingHandlers();
                }
            });

            // Generic per-page guard: a single before-handler maps each /dash/<key>
            // prefix to a PageAccess key. Pages with their own special access rules
            // (config, alerts admin) are excluded and handled below. Prefixes for
            // disabled modules are dropped from the map - the routes themselves
            // aren't registered, so requests 404.
            java.util.Map<String, String> pagePrefixes = new java.util.LinkedHashMap<>();
            pagePrefixes.put("/dash/stats",   "stats");
            pagePrefixes.put("/dash/pending", "pending");
            pagePrefixes.put("/dash/audit",   "audit");
            pagePrefixes.put("/dash/members", "members");
            pagePrefixes.put("/dash/invites", "invites");
            if (mods.moderation())      pagePrefixes.put("/dash/moderation",     "moderation");
            if (mods.violations())      pagePrefixes.put("/dash/violations",     "violations");
            if (mods.levels())          pagePrefixes.put("/dash/levels",         "levels");
            if (mods.reactionRoles())   pagePrefixes.put("/dash/reaction-roles", "reaction-roles");
            if (mods.engagement())      pagePrefixes.put("/dash/engagement",     "engagement");
            if (mods.tickets())         pagePrefixes.put("/dash/tickets",        "tickets");
            if (mods.feedback())        pagePrefixes.put("/dash/feedback",       "feedback");
            if (mods.alerts())          pagePrefixes.put("/dash/alerts",         "alerts");
            if (mods.autoresponders())  pagePrefixes.put("/dash/autoresponders", "autoresponders");
            if (mods.eventsTimezones()) pagePrefixes.put("/dash/scheduler",      "scheduler");
            app.before("/dash/*", ctx -> {
                String path = ctx.path();
                for (var entry : pagePrefixes.entrySet()) {
                    String prefix = entry.getKey();
                    if (!path.equals(prefix) && !path.startsWith(prefix + "/")) continue;
                    if (!pageAccess.canAccess(ctx, entry.getValue())) {
                        ctx.status(401).html(notAllowedHtml(
                                Layout.escape(PageAccess.LABELS.getOrDefault(entry.getValue(), entry.getValue()))
                                        + " is restricted",
                                "This page is restricted to specific roles. Ask a server admin to grant you "
                                + "one of the roles listed for it on the dashboard's Permissions tab."));
                        ctx.skipRemainingHandlers();
                    }
                    return;
                }
            });

            GuildLookup lookup = new GuildLookup(plugin.discordService(), services.config.discordGuildId());
            DashConfigHandlers cfg = new DashConfigHandlers(services, lookup, plugin);

            app.get("/dash", ctx -> {
                // Mods land on Stats; config-admin/web-manager who aren't mods land on config.
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isPresent() && !sess.get().mod() && sess.get().canEditLanding()) {
                    ctx.redirect("/dash/config");
                } else {
                    ctx.redirect("/dash/stats");
                }
            });
            app.get("/dash/pending", wrap(pending::list));
            app.post("/dash/pending/{id}/approve", wrap(pending::approve));
            app.post("/dash/pending/{id}/deny", wrap(pending::deny));

            // /dash/config requires at least web_manager. Question CRUD and the
            // settings form itself gate on canEditConfig inside the handler so
            // web-manager-only users see just the Landing tab.
            app.before("/dash/config", ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditLanding()) {
                    ctx.status(401).html(notAllowedHtml("Configuration is restricted",
                            "The Configuration page is available to members with the "
                            + "<strong>Config admin</strong> or <strong>Web manager</strong> role. "
                            + "Ask a server owner to grant one of those roles in your Discord, "
                            + "then sign back in here."));
                    ctx.skipRemainingHandlers();
                }
            });
            app.before("/dash/config/*", ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    // Anything under /dash/config/* (questions CRUD, LLM test) is
                    // Config-admin-only. Web managers stay on the landing tab.
                    ctx.status(401).html(notAllowedHtml("Config admin only",
                            "This section is restricted to the <strong>Config admin</strong> role. "
                            + "Web managers can edit the public Landing page but not onboarding "
                            + "questions, role pickers, AI gateway, or other flow settings."));
                    ctx.skipRemainingHandlers();
                }
            });

            app.get("/dash/config", wrap(cfg::form));
            app.post("/dash/config", wrap(cfg::save));
            app.post("/dash/config/modules", wrap(cfg::saveModules));
            app.post("/dash/config/restart-plugin", wrap(cfg::restartPlugin));
            app.post("/dash/config/llm/test", wrap(cfg::testLlm));
            app.get("/dash/config/questions/new", wrap(cfg::newQuestion));
            app.post("/dash/config/questions/new", wrap(cfg::saveQuestion));
            // Literal sub-paths must be registered BEFORE the catch-all /{id} below,
            // otherwise Javalin routes /ai-generate, /ai-save and /reorder to saveQuestion.
            app.post("/dash/config/questions/reorder", wrap(cfg::reorderQuestions));
            app.post("/dash/config/questions/ai-generate", wrap(cfg::generateQuestions));
            app.post("/dash/config/questions/ai-save", wrap(cfg::saveGeneratedQuestions));
            app.get("/dash/config/questions/{id}/edit", wrap(cfg::editQuestion));
            app.post("/dash/config/questions/{id}", wrap(cfg::saveQuestion));
            app.post("/dash/config/questions/{id}/delete", wrap(cfg::deleteQuestion));
            app.post("/dash/config/questions/{id}/move", wrap(cfg::moveQuestion));
            app.post("/dash/config/questions/{id}/toggle", wrap(cfg::toggleQuestion));

            // Landing-AI endpoints (polish + features-from-plugins). These are
            // gated by canEditLanding (web manager OR config admin), not by the
            // /dash/config/* admin-only guard. Auth is enforced inside each handler.
            app.post("/dash/landing-ai/polish", wrap(cfg::polish));
            app.post("/dash/landing-ai/features-from-plugins", wrap(cfg::featuresFromPlugins));

            DashAuditHandlers audit = new DashAuditHandlers(services);
            DashMembersHandlers members = new DashMembersHandlers(services, plugin.discordService());
            DashStatsHandlers stats = new DashStatsHandlers(services, plugin.discordService());
            DashInvitesHandlers invites = new DashInvitesHandlers(services, lookup);
            app.get("/dash/audit", wrap(audit::list));
            app.get("/dash/members", wrap(members::list));
            app.post("/dash/members/bulk-onboard", wrap(members::bulkOnboard));
            app.get("/dash/members/{discordId}", wrap(members::detail));
            app.get("/dash/api/member/{discordId}", wrap(members::activityApi));
            app.post("/dash/members/{discordId}/reonboard", wrap(members::reonboard));
            app.get("/dash/stats", wrap(stats::page));
            app.get("/dash/stats/activity", wrap(stats::activityPage));
            app.get("/dash/stats/retention", wrap(stats::retentionPage));
            app.get("/dash/stats/geo", wrap(stats::geoPage));
            app.get("/dash/api/overview", wrap(stats::overviewApi));
            app.get("/dash/api/messages/daily", wrap(stats::messagesDailyApi));
            app.get("/dash/api/top-channels", wrap(stats::topChannelsApi));
            app.get("/dash/api/funnel", wrap(stats::funnelApi));
            app.get("/dash/api/retention", wrap(stats::retentionApi));
            app.get("/dash/api/joins-by-invite", wrap(stats::joinsByInviteApi));
            app.get("/dash/api/geo", wrap(stats::geoApi));
            app.post("/dash/stats/rollup-now", wrap(stats::rollupNow));
            app.get("/dash/invites", wrap(invites::list));
            app.post("/dash/invites/{code}/label", wrap(invites::saveLabel));

            if (mods.moderation()) {
                DashModerationHandlers modHandlers = new DashModerationHandlers(
                        services.autoMod, services.warningDao, services.raidProtectionDao, services.moderation,
                        lookup);
                app.get("/dash/moderation", wrap(modHandlers::page));
                app.post("/dash/moderation/automod", wrap(modHandlers::saveAutomod));
                app.post("/dash/moderation/raid", wrap(modHandlers::saveRaid));
                app.post("/dash/moderation/raid/clear", wrap(modHandlers::clearLockdown));
                app.post("/dash/moderation/warn/{id}/clear", wrap(modHandlers::clearWarning));
            }

            if (mods.violations()) {
                // Violations tab: lists Grim FlagEvents recorded by the bridge.
                // Handler renders an install prompt instead of an empty page when
                // Grim isn't installed.
                DashGrimHandlers grimHandlers = new DashGrimHandlers(services.grim);
                app.get("/dash/violations", wrap(grimHandlers::page));
            }

            if (mods.levels()) {
                DashLevelsHandlers levelHandlers = new DashLevelsHandlers(services.levelService, lookup);
                app.get("/dash/levels", wrap(levelHandlers::page));
                app.post("/dash/levels", wrap(levelHandlers::save));
                app.post("/dash/levels/rewards/add", wrap(levelHandlers::addReward));
                app.post("/dash/levels/rewards/delete", wrap(levelHandlers::deleteReward));
                app.post("/dash/levels/multipliers/add", wrap(levelHandlers::addMultiplier));
                app.post("/dash/levels/multipliers/delete", wrap(levelHandlers::deleteMultiplier));
                app.post("/dash/levels/reset", wrap(levelHandlers::resetAll));
            }

            if (mods.reactionRoles()) {
                DashReactionRolesHandlers rrHandlers = new DashReactionRolesHandlers(
                        services.reactionRoles, plugin.discordService(), lookup);
                app.get("/dash/reaction-roles", wrap(rrHandlers::list));
                app.post("/dash/reaction-roles/new", wrap(rrHandlers::createGroup));
                app.post("/dash/reaction-roles/{id}", wrap(rrHandlers::updateGroup));
                app.post("/dash/reaction-roles/{id}/delete", wrap(rrHandlers::deleteGroup));
                app.post("/dash/reaction-roles/{id}/options", wrap(rrHandlers::addOption));
                app.post("/dash/reaction-roles/{id}/options/{optId}/delete", wrap(rrHandlers::deleteOption));
                app.post("/dash/reaction-roles/{id}/post", wrap(rrHandlers::post));
            }

            if (mods.alerts()) {
                DashAlertsHandlers alertHandlers = new DashAlertsHandlers(
                        services.alertService(), services.alertManager(), lookup, services.discordSrv());
                app.get ("/dash/alerts",                wrap(alertHandlers::list));
                app.get ("/dash/alerts/new",            wrap(alertHandlers::newForm));
                app.post("/dash/alerts/new",            wrap(alertHandlers::save));
                // Importer routes must be registered BEFORE the catch-all /{id} POST below,
                // otherwise Javalin sends /import to the save handler.
                app.get ("/dash/alerts/import",            wrap(alertHandlers::importForm));
                app.post("/dash/alerts/import",            wrap(alertHandlers::importSave));
                app.get ("/dash/alerts/import/done",       wrap(alertHandlers::importDone));
                app.post("/dash/alerts/import/clear-dsrv", wrap(alertHandlers::clearDsrvAlertsFile));
                app.get ("/dash/alerts/{id}/edit",      wrap(alertHandlers::editForm));
                app.post("/dash/alerts/{id}",           wrap(alertHandlers::save));
                app.post("/dash/alerts/{id}/delete",    wrap(alertHandlers::delete));
                app.post("/dash/alerts/{id}/toggle",    wrap(alertHandlers::toggle));
                app.post("/dash/alerts/{id}/test",      wrap(alertHandlers::test));
            }

            if (mods.autoresponders()) {
                DashAutoresponderHandlers autoresponderHandlers = new DashAutoresponderHandlers(
                        services.autoresponders, lookup);
                app.get ("/dash/autoresponders",                wrap(autoresponderHandlers::list));
                app.get ("/dash/autoresponders/new",            wrap(autoresponderHandlers::newForm));
                app.post("/dash/autoresponders/new",            wrap(autoresponderHandlers::save));
                app.get ("/dash/autoresponders/{id}/edit",      wrap(autoresponderHandlers::editForm));
                app.post("/dash/autoresponders/{id}",           wrap(autoresponderHandlers::save));
                app.post("/dash/autoresponders/{id}/delete",    wrap(autoresponderHandlers::delete));
                app.post("/dash/autoresponders/{id}/toggle",    wrap(autoresponderHandlers::toggle));
            }

            // Shared AI helpers used across multiple dashboard pages (autoresponder
            // regex, ticket reply suggestions, automod word lists, scheduler
            // descriptions, alert message polish). Each handler enforces its own
            // permission check against the dashboard session. The endpoints are
            // tied to specific modules; we only register the ones whose owner
            // module is on so disabled features have no AI-helper surface.
            io.warden.web.handlers.DashAiHandlers aiHandlers =
                    new io.warden.web.handlers.DashAiHandlers(services);
            if (mods.autoresponders()) {
                app.post("/dash/ai/autoresponder-regex",     wrap(aiHandlers::autoresponderRegex));
                app.post("/dash/ai/autoresponder-response",  wrap(aiHandlers::autoresponderResponse));
            }
            if (mods.tickets()) {
                app.post("/dash/ai/tickets/{id}/reply",      wrap(aiHandlers::ticketReply));
            }
            if (mods.moderation()) {
                app.post("/dash/ai/automod-words",           wrap(aiHandlers::automodBannedWords));
            }
            if (mods.eventsTimezones()) {
                app.post("/dash/ai/event-draft",             wrap(aiHandlers::eventDraft));
            }
            if (mods.alerts()) {
                app.post("/dash/ai/alert-polish",            wrap(aiHandlers::alertPolish));
            }

            if (mods.engagement()) {
                DashEngagementHandlers engHandlers = new DashEngagementHandlers(
                        services.engagement, plugin.discordService(), lookup);
                app.get("/dash/engagement", wrap(engHandlers::page));
                app.post("/dash/engagement/polls/new", wrap(engHandlers::createPoll));
                app.post("/dash/engagement/polls/{id}/close", wrap(engHandlers::closePoll));
                app.post("/dash/engagement/giveaways/new", wrap(engHandlers::createGiveaway));
                app.post("/dash/engagement/giveaways/{id}/draw", wrap(engHandlers::drawGiveaway));
                app.post("/dash/engagement/giveaways/{id}/cancel", wrap(engHandlers::cancelGiveaway));
            }

            if (mods.eventsTimezones()) {
                // Event scheduler (mod-gated via PageAccess "scheduler").
                DashSchedulerHandlers scheduler = new DashSchedulerHandlers(services, plugin.discordService(), lookup);
                app.get ("/dash/scheduler",              wrap(scheduler::page));
                app.post("/dash/scheduler/settings",     wrap(scheduler::saveSettings));
                app.get ("/dash/scheduler/new",          wrap(scheduler::newForm));
                app.post("/dash/scheduler/new",          wrap(scheduler::save));
                app.get ("/dash/scheduler/{id}",         wrap(scheduler::detail));
                app.get ("/dash/scheduler/{id}/edit",    wrap(scheduler::editForm));
                app.post("/dash/scheduler/{id}",         wrap(scheduler::save));
                app.post("/dash/scheduler/{id}/cancel",  wrap(scheduler::cancel));
                app.post("/dash/scheduler/{id}/delete",  wrap(scheduler::delete));
            }

            // /dash/permissions - owner / config-admin only. Falls under no
            // per-page guard since it's the surface that *defines* page guards.
            app.before("/dash/permissions", ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    ctx.status(401).html(notAllowedHtml("Permissions admin only",
                            "Only the server <strong>Owner</strong> or someone with the "
                            + "<strong>Config admin</strong> role can change dashboard page permissions."));
                    ctx.skipRemainingHandlers();
                }
            });
            DashPermissionsHandlers perms = new DashPermissionsHandlers(services.pageAccessDao, lookup);
            app.get ("/dash/permissions", wrap(perms::page));
            app.post("/dash/permissions", wrap(perms::save));

            // /dash/api-keys - Config-admin-only surface for minting bearer
            // tokens for /api/v1. Same gate as /dash/permissions because keys
            // grant scope-bounded access to module data and write actions.
            app.before("/dash/api-keys", ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    ctx.status(401).html(notAllowedHtml("API keys admin only",
                            "Only the server <strong>Owner</strong> or someone with the "
                            + "<strong>Config admin</strong> role can mint or revoke API keys."));
                    ctx.skipRemainingHandlers();
                }
            });
            app.before("/dash/api-keys/*", ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    ctx.status(401).html(notAllowedHtml("API keys admin only",
                            "Only the server <strong>Owner</strong> or someone with the "
                            + "<strong>Config admin</strong> role can mint or revoke API keys."));
                    ctx.skipRemainingHandlers();
                }
            });
            DashApiKeysHandlers apiKeys = new DashApiKeysHandlers(services);
            app.get ("/dash/api-keys",                wrap(apiKeys::page));
            app.post("/dash/api-keys/new",            wrap(apiKeys::create));
            app.post("/dash/api-keys/{id}/revoke",    wrap(apiKeys::revoke));
            app.post("/dash/api-keys/{id}/delete",    wrap(apiKeys::delete));

            // /dash/debug + /debug/{id} - encrypted debug reports. Config-admin only
            // for the management surface; the viewer (/debug/*) is fully public since
            // the AES-256 key embedded in the URL fragment is the sole access control.
            app.before("/dash/debug",    ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    ctx.status(401).html(notAllowedHtml("Debug reports admin only",
                            "Only the server <strong>Owner</strong> or someone with the "
                            + "<strong>Config admin</strong> role can manage debug reports."));
                    ctx.skipRemainingHandlers();
                }
            });
            app.before("/dash/debug/*",  ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    ctx.status(401).html(notAllowedHtml("Debug reports admin only",
                            "Only the server <strong>Owner</strong> or someone with the "
                            + "<strong>Config admin</strong> role can manage debug reports."));
                    ctx.skipRemainingHandlers();
                }
            });
            DashDebugHandlers debug = new DashDebugHandlers(plugin, services);
            app.get ("/dash/debug",                   wrap(debug::list));
            app.post("/dash/debug/generate",          wrap(debug::generate));
            app.post("/dash/debug/{id}/delete",       wrap(debug::delete));

            DebugViewHandlers debugView = new DebugViewHandlers(services);
            app.get("/debug/{id}",                    wrap(debugView::view));
            app.get("/api/debug/{id}/payload",        wrap(debugView::payload));

            if (mods.tickets()) {
                DashTicketsHandlers tickets = new DashTicketsHandlers(
                        services.tickets, plugin.discordService(), lookup);
                // Literal sub-paths must be registered BEFORE the {id} catch-all below.
                app.get ("/dash/tickets",                          wrap(tickets::list));
                app.get ("/dash/tickets/categories",               wrap(tickets::categoriesPage));
                app.post("/dash/tickets/categories",               wrap(tickets::categoryCreate));
                app.post("/dash/tickets/categories/{id}",          wrap(tickets::categoryUpdate));
                app.post("/dash/tickets/categories/{id}/delete",   wrap(tickets::categoryDelete));
                app.get ("/dash/tickets/panels",                   wrap(tickets::panelsPage));
                app.post("/dash/tickets/panels",                   wrap(tickets::panelCreate));
                app.post("/dash/tickets/panels/{id}",              wrap(tickets::panelUpdate));
                app.post("/dash/tickets/panels/{id}/delete",       wrap(tickets::panelDelete));
                app.post("/dash/tickets/panels/{id}/post",         wrap(tickets::panelPost));
                app.get ("/dash/tickets/settings",                 wrap(tickets::settingsPage));
                app.post("/dash/tickets/settings",                 wrap(tickets::settingsSave));
                app.get ("/dash/tickets/{id}",                              wrap(tickets::detail));
                app.get ("/dash/tickets/{id}/messages.json",                wrap(tickets::messagesJson));
                app.post("/dash/tickets/{id}/reply",                        wrap(tickets::reply));
                app.post("/dash/tickets/{id}/note",                         wrap(tickets::note));
                app.post("/dash/tickets/{id}/status",                       wrap(tickets::changeStatus));
                app.post("/dash/tickets/{id}/assign",                       wrap(tickets::assign));
                app.post("/dash/tickets/{id}/migrate",                      wrap(tickets::migrate));
                app.get ("/dash/tickets/members/search",                    wrap(tickets::membersSearch));
                app.post("/dash/tickets/{id}/participants",                 wrap(tickets::participantAdd));
                app.post("/dash/tickets/{id}/participants/{userId}/remove", wrap(tickets::participantRemove));
                app.post("/dash/tickets/{id}/transcript/regen",             wrap(tickets::regenerateTranscript));
                app.get ("/dash/tickets/{id}/attachments/{msgId}/{name}",   wrap(tickets::attachment));
            }

            DashAboutHandlers about = new DashAboutHandlers();
            app.get("/dash/about", wrap(about::page));

            // /dash/https - Config-admin-only explainer for native TLS. Status
            // panel + step-by-step certbot / win-acme walkthrough. Read-only;
            // changes go through config.yml + restart.
            app.before("/dash/https", ctx -> {
                var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
                if (sess.isEmpty() || !sess.get().canEditConfig()) {
                    ctx.status(401).html(notAllowedHtml("HTTPS admin only",
                            "The HTTPS page is restricted to the <strong>Config admin</strong> role."));
                    ctx.skipRemainingHandlers();
                }
            });
            DashHttpsHandlers https = new DashHttpsHandlers(services.config, this);
            app.get("/dash/https", wrap(https::page));

            if (mods.feedback()) {
                DashFeedbackHandlers feedback = new DashFeedbackHandlers(
                        services.feedback, plugin.discordService(), lookup);
                app.get ("/dash/feedback",                 wrap(feedback::list));
                app.get ("/dash/feedback/settings",        wrap(feedback::settings));
                app.post("/dash/feedback/settings",        wrap(feedback::settingsSave));
                app.get ("/dash/feedback/{id}",            wrap(feedback::detail));
                app.post("/dash/feedback/{id}/status",     wrap(feedback::changeStatus));
                app.post("/dash/feedback/{id}/response",   wrap(feedback::response));
                app.post("/dash/feedback/{id}/delete",     wrap(feedback::delete));
            }

            // OAuth + public onboarding (only if session_secret is set).
            if (services.config.webSessionSecret() != null && !services.config.webSessionSecret().isBlank()) {
                SessionCookie sessions = new SessionCookie(services.config.webSessionSecret());
                if (services.config.oauthConfigured()) {
                    DiscordOAuth oauth = new DiscordOAuth(services.config);
                    OAuthHandlers oauthHandlers = new OAuthHandlers(
                            services, plugin.discordService(), oauth, sessions, log);
                    app.get("/auth/discord/start", oauthHandlers::start);
                    app.get(DiscordOAuth.CALLBACK_PATH, oauthHandlers::callback);
                    app.post("/auth/logout", oauthHandlers::logout);
                } else {
                    log.info("OAuth not configured (need discord.client_id + client_secret); /auth/* routes disabled");
                }

                // First-run bootstrap pages - same auth gate as /dash/*.
                SetupHandlers setup = new SetupHandlers(services, lookup, sessions);
                app.before("/setup", ctx -> { if (!auth.check(ctx)) ctx.skipRemainingHandlers(); });
                app.before("/setup/*", ctx -> { if (!auth.check(ctx)) ctx.skipRemainingHandlers(); });
                app.get("/setup/mod-role", wrap(setup::modRoleForm));
                app.post("/setup/mod-role", wrap(setup::modRoleSave));

                OnboardHandlers onb = new OnboardHandlers(services, plugin.discordService(), sessions);

                // Rate-limit POST routes on /onboard/*: 20 req/min per IP across the flow.
                // A second, tighter limiter on /onboard/code/issue caps code-minting churn.
                RateLimiter onboardLimit = new RateLimiter(20, 60_000);
                RateLimiter issueLimit   = new RateLimiter(8,  60_000);
                // Polling fires every couple seconds while the user is on the
                // landing page, so it gets its own generous bucket.
                RateLimiter pollLimit    = new RateLimiter(120, 60_000);
                app.before("/onboard/*", ctx -> {
                    String ip = ctx.ip();
                    String path = ctx.path();
                    boolean isPost = "POST".equalsIgnoreCase(ctx.method().name());
                    if ("/onboard/code/poll".equals(path)) {
                        if (!pollLimit.tryAcquire(ip)) {
                            ctx.status(429).header("Retry-After", "30")
                                    .json(java.util.Map.of("error", "rate_limited"));
                            ctx.skipRemainingHandlers();
                        }
                        return;
                    }
                    if ("/onboard/code/issue".equals(path)) {
                        if (!issueLimit.tryAcquire(ip)) {
                            ctx.status(429).header("Retry-After", "60")
                                    .json(java.util.Map.of("error", "rate_limited"));
                            ctx.skipRemainingHandlers();
                        }
                        return;
                    }
                    if (!isPost) return;
                    if (!onboardLimit.tryAcquire(ip)) {
                        ctx.status(429).header("Retry-After", "60")
                                .html("<h1>Slow down.</h1><p>Too many submissions from this IP. Try again in a minute.</p>");
                        ctx.skipRemainingHandlers();
                    }
                });

                // Rate-limit /auth/* (OAuth start + callback) per IP. Same reasoning: a normal
                // OAuth round trip is two requests; 30/min leaves headroom for retries while
                // blocking grinding on the state/code params.
                RateLimiter authLimit = new RateLimiter(30, 60_000);
                app.before("/auth/*", ctx -> {
                    if (!authLimit.tryAcquire(ctx.ip())) {
                        ctx.status(429).header("Retry-After", "60")
                                .html("<h1>Slow down.</h1><p>Too many sign-in attempts. Try again in a minute.</p>");
                        ctx.skipRemainingHandlers();
                    }
                });

                app.get("/onboard", wrap(onb::landing));
                app.post("/onboard/code/issue", wrap(onb::issueCode));
                app.get("/onboard/code/poll", wrap(onb::pollCode));
                app.get("/onboard/next", wrap(onb::next));
                app.get("/onboard/rules", wrap(onb::rules));
                app.post("/onboard/agree", wrap(onb::agree));
                app.post("/onboard/disagree", wrap(onb::disagree));
                app.get("/onboard/q/{id}", wrap(onb::questionForm));
                app.post("/onboard/q/{id}", wrap(onb::submitAnswer));
                app.get("/onboard/done", wrap(onb::done));

                if (mods.eventsTimezones()) {
                    // /tz - per-user timezone picker. Reachable by anyone signed
                    // in via Discord OAuth, no role required.
                    TimezoneHandlers tz = new TimezoneHandlers(services, sessions);
                    app.get ("/tz",                       wrap(tz::picker));
                    app.post("/tz",                       wrap(tz::save));
                    app.post("/tz/clear",                 wrap(tz::clear));
                    app.get ("/tz/events",                wrap(tz::eventsList));
                    app.post("/tz/events/{id}/rsvp",      wrap(tz::rsvp));
                }
            } else {
                log.warning("web.session_secret not set; /onboard and /auth/* routes disabled");
            }

            app.start(services.config.webBindHost(), services.config.webBindPort());
            running.set(true);
            log.info("Web: Javalin listening on " + services.config.webBindHost() + ":" + services.config.webBindPort()
                    + " (public URL: " + services.config.webPublicUrl() + ")");
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    public void stop() {
        if (app != null) {
            log.info("Web: stopping Javalin...");
            try {
                app.stop();
            } catch (Exception ignored) {
                // best-effort
            }
            app = null;
        }
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    /** True when an HTTPS connector was wired successfully at boot. */
    public boolean sslActive() {
        return sslActive.get();
    }

    /**
     * Non-null when SSL was configured but failed to load - e.g. PEM files
     * missing or unparseable. The /dash/https page surfaces this so admins
     * don't have to grep the server log.
     */
    public String sslLoadError() {
        return sslLoadError;
    }

    /**
     * Best-effort: build the SslContextFactory from the configured PEM files
     * and attach it as a second Jetty connector. Failures are logged and
     * surfaced via {@link #sslLoadError()} - the plugin continues with HTTP
     * only so admins can hit /dash/https and read the explainer.
     */
    private void wireHttpsConnector(io.javalin.config.JavalinConfig cfg) {
        var sslCfg = services.config.ssl();
        if (!sslCfg.usable()) return;
        try {
            var ctx = SslSupport.buildSslContextFactory(sslCfg.certFile(), sslCfg.keyFile());
            cfg.jetty.addConnector(SslSupport.connectorFactory(
                    services.config.webBindHost(), sslCfg.port(), ctx));
            sslActive.set(true);
            log.info("Web: HTTPS connector wired on " + services.config.webBindHost() + ":" + sslCfg.port()
                    + " (cert: " + sslCfg.certFile() + ")");
        } catch (Exception e) {
            sslLoadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.log(Level.WARNING, "Web: HTTPS configuration failed; falling back to HTTP only ("
                    + sslLoadError + ")", e);
        }
    }

    /**
     * Unpack the default landing.html / landing.css into the configured www dir
     * if either file is missing. Operators can edit the unpacked files to
     * customise their landing without recompiling - and if they ever want to
     * reset to defaults, deleting the file and restarting puts it back.
     */
    private void ensureDefaultWww(java.nio.file.Path wwwDir) {
        try {
            java.nio.file.Files.createDirectories(wwwDir);
        } catch (java.io.IOException e) {
            log.log(Level.WARNING, "Could not create www dir at " + wwwDir + ": " + e.getMessage());
            return;
        }
        for (String name : new String[] {"landing.html", "landing.css", "rules.html", "map.html", "leaderboard.html"}) {
            java.nio.file.Path target = wwwDir.resolve(name);
            if (java.nio.file.Files.exists(target)) continue;
            try (java.io.InputStream in =
                         getClass().getResourceAsStream("/web-default/" + name)) {
                if (in == null) {
                    log.warning("Default www resource missing from jar: " + name);
                    continue;
                }
                java.nio.file.Files.copy(in, target);
                log.info("Unpacked default " + name + " to " + target);
            } catch (java.io.IOException e) {
                log.log(Level.WARNING, "Failed to unpack default " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * Returns true when admins have flipped on the require-timezone setting
     * and the current session has no recorded timezone yet. Used as a soft
     * gate that bounces /dash visits to the picker until the user saves one.
     */
    private boolean needsTimezoneOnboarding(io.javalin.http.Context ctx) {
        // No /tz surface when the events+timezones module is off, so the soft
        // gate must also be silent - otherwise admins would be bounced to a
        // page that doesn't exist.
        if (!services.config.modules().eventsTimezones()) return false;
        try {
            var cfg = services.timezones.config();
            if (!cfg.onboardingRequired()) return false;
            var sess = io.warden.web.auth.DashAuth.sessionOf(ctx);
            if (sess.isEmpty()) return false;
            String did = sess.get().discordId();
            if (did == null || did.isBlank()) return false;
            return services.timezones.findUser(did).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String notAllowedHtml(String headline, String detailHtml) {
        String h = headline == null ? "Not allowed" : headline;
        String d = detailHtml == null ? "" : detailHtml; // allowed: pre-built safe HTML
        return """
                <!doctype html><html lang=en><head><meta charset=utf-8>
                <title>Restricted · Warden</title>
                <link rel=icon type="image/svg+xml" href="/static/img/warden-icon.svg">
                <link rel=stylesheet href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css">
                <link rel=stylesheet href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.13.1/font/bootstrap-icons.min.css">
                <style>
                  body{min-height:100vh;display:flex;align-items:center;justify-content:center;
                       background:linear-gradient(135deg,#0f1226,#1a1f3d);color:#e8eaf0;padding:2rem}
                  .card-401{max-width:520px;width:100%%;background:#1c2140;border:1px solid #2a3158;
                            border-radius:14px;padding:2.5rem 2.25rem;box-shadow:0 10px 40px rgba(0,0,0,.4)}
                  .icon-wrap{width:64px;height:64px;border-radius:50%%;
                             background:rgba(255,193,7,.15);display:flex;align-items:center;
                             justify-content:center;margin-bottom:1.25rem}
                  .icon-wrap i{font-size:1.8rem;color:#ffc107}
                  h1{font-size:1.5rem;font-weight:600;margin:0 0 .6rem}
                  p{color:#b6bdd5;margin:0 0 1rem;line-height:1.55}
                  .btn-row{margin-top:1.5rem;display:flex;gap:.6rem;flex-wrap:wrap}
                  .btn-row a{text-decoration:none}
                  .btn-primary{background:#5865F2;border-color:#5865F2}
                  .btn-primary:hover{background:#4752c4;border-color:#4752c4}
                  .btn-outline-light{color:#b6bdd5;border-color:#3a4170}
                  .btn-outline-light:hover{color:#fff;background:#2a3158;border-color:#3a4170}
                </style></head><body>
                <div class="card-401">
                  <div class="icon-wrap"><i class="bi bi-shield-lock"></i></div>
                  <h1>%s</h1>
                  <p>%s</p>
                  <div class="btn-row">
                    <a class="btn btn-primary" href="/dash"><i class="bi bi-arrow-left me-1"></i>Back to dashboard</a>
                    <a class="btn btn-outline-light" href="/">Home</a>
                  </div>
                </div></body></html>
                """.formatted(escapeText(h), d);
    }

    private static String escapeText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Wrap a handler that throws checked exceptions so we don't have to sprinkle
     * try/catch in every route. Anything thrown becomes a 500 with the message.
     */
    @FunctionalInterface
    private interface CheckedHandler {
        void handle(io.javalin.http.Context ctx) throws Exception;
    }

    private io.javalin.http.Handler wrap(CheckedHandler h) {
        return ctx -> {
            try {
                h.handle(ctx);
            } catch (Exception e) {
                log.log(Level.WARNING, "handler failure on " + ctx.path(), e);
                ctx.status(500);
                ctx.html("<h1>500</h1><pre>" + e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "" : e.getMessage().replace("<", "&lt;")) + "</pre>");
            }
        };
    }
}
