package io.warden;

import io.warden.alerts.AlertDao;
import io.warden.alerts.AlertManager;
import io.warden.alerts.AlertService;
import io.warden.api.ApiKeyDao;
import io.warden.api.ApiKeyService;
import io.warden.debug.DebugReportDao;
import io.warden.analytics.AnalyticsService;
import io.warden.analytics.CohortJob;
import io.warden.analytics.GeoIpService;
import io.warden.analytics.RollupJob;
import io.warden.audit.AuditService;
import io.warden.autoresponder.AutoresponderDao;
import io.warden.autoresponder.AutoresponderService;
import io.warden.config.WardenConfig;
import io.warden.data.Database;
import io.warden.discord.DiscordSrvBridge;
import io.warden.discord.OnboardingDelivery;
import io.warden.engagement.EngagementScheduler;
import io.warden.engagement.EngagementService;
import io.warden.engagement.GiveawayDao;
import io.warden.engagement.PollDao;
import io.warden.engagement.ReminderDao;
import io.warden.feedback.FeedbackConfigDao;
import io.warden.feedback.FeedbackDao;
import io.warden.feedback.FeedbackService;
import io.warden.grim.GrimBridge;
import io.warden.levels.LevelConfigDao;
import io.warden.levels.LevelRewardDao;
import io.warden.levels.LevelService;
import io.warden.levels.LevelUserDao;
import io.warden.moderation.AutoModService;
import io.warden.moderation.AutomodConfigDao;
import io.warden.moderation.ModActionDao;
import io.warden.moderation.ModerationService;
import io.warden.moderation.RaidProtectionDao;
import io.warden.moderation.WarningDao;
import io.warden.reactionroles.ReactionRoleDao;
import io.warden.reactionroles.ReactionRoleService;
import io.warden.tickets.TicketCategoryDao;
import io.warden.tickets.TicketDao;
import io.warden.tickets.TicketPanelDao;
import io.warden.tickets.TicketService;
import io.warden.tickets.TicketsConfigDao;
import io.warden.tickets.TranscriptService;
import io.warden.timezone.EventRsvpDao;
import io.warden.timezone.ScheduledEventDao;
import io.warden.timezone.TimezoneConfigDao;
import io.warden.timezone.TimezoneService;
import io.warden.timezone.UserTimezoneDao;
import io.warden.web.auth.PageAccessDao;
import io.warden.web.ssl.SslExpiryNotifier;
import io.warden.data.dao.AnalyticsMetaDao;
import io.warden.data.dao.AnswerDao;
import io.warden.data.dao.ApplicationDao;
import io.warden.data.dao.AuditDao;
import io.warden.data.dao.DailyMetricsDao;
import io.warden.data.dao.DiscordMemberEventDao;
import io.warden.data.dao.DiscordMessageDao;
import io.warden.data.dao.DiscordVoiceSessionDao;
import io.warden.data.dao.InviteDao;
import io.warden.data.dao.LinkCodeDao;
import io.warden.data.dao.McSessionDao;
import io.warden.data.dao.QuestionDao;
import io.warden.data.dao.SettingsDao;
import io.warden.data.dao.UserDao;
import io.warden.llm.ManifestClient;
import io.warden.onboarding.BulkOnboardService;
import io.warden.onboarding.DecisionService;
import io.warden.onboarding.LinkCodeService;
import io.warden.onboarding.OnboardingService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Concrete-dependency container built once in onEnable and handed to
 * DiscordService + WebService. Keeps the plugin class small and makes
 * the wiring graph obvious in one place.
 *
 * Wiring order:
 *   1. Services(...) - DAOs + LLM-side services (Manifest, executor, audit)
 *   2. (plugin) DiscordService(this, services) - creates JDA wiring
 *   3. (plugin) JdaDecisionExecutor(discord, ...)
 *   4. (plugin) decisionService = DecisionService(..., jdaDecisionExecutor, ...)
 *   5. (plugin) services.attachDecisionService(decisionService)
 *      - wires OnboardingService.onApplicationSubmitted → triageAsync
 */
public final class Services {

    public final WardenConfig config;
    public final Database database;
    public final Logger log;

    public final UserDao userDao;
    public final LinkCodeDao linkCodeDao;
    public final QuestionDao questionDao;
    public final AnswerDao answerDao;
    public final ApplicationDao applicationDao;
    public final SettingsDao settingsDao;
    public final AuditDao auditDao;

    public final DiscordMessageDao discordMessageDao;
    public final DiscordMemberEventDao discordMemberEventDao;
    public final DiscordVoiceSessionDao discordVoiceSessionDao;
    public final McSessionDao mcSessionDao;
    public final AnalyticsMetaDao analyticsMetaDao;
    public final DailyMetricsDao dailyMetricsDao;
    public final InviteDao inviteDao;

    public final AuditService audit;
    public final AnalyticsService analytics;
    public final GeoIpService geoip;
    public final RollupJob rollup;
    public final CohortJob cohorts;
    public final SslExpiryNotifier sslExpiry;
    public final LinkCodeService linkCodes;
    public final OnboardingService onboarding;

    // Moderation
    public final AutomodConfigDao automodConfigDao;
    public final WarningDao warningDao;
    public final ModActionDao modActionDao;
    public final RaidProtectionDao raidProtectionDao;
    public final AutoModService autoMod;
    public final ModerationService moderation;

    // Levels
    public final LevelConfigDao levelConfigDao;
    public final LevelUserDao levelUserDao;
    public final LevelRewardDao levelRewardDao;
    public final LevelService levelService;

    // Reaction roles
    public final ReactionRoleDao reactionRoleDao;
    public final ReactionRoleService reactionRoles;

    // Engagement (polls/giveaways/reminders)
    public final PollDao pollDao;
    public final GiveawayDao giveawayDao;
    public final ReminderDao reminderDao;
    public final EngagementService engagement;
    public final EngagementScheduler engagementScheduler;

    // Tickets (feedback / suggestions)
    public final TicketDao ticketDao;
    public final TicketCategoryDao ticketCategoryDao;
    public final TicketPanelDao ticketPanelDao;
    public final TicketsConfigDao ticketsConfigDao;
    public final TicketService tickets;
    public final TranscriptService ticketTranscripts;

    // Per-page dashboard permissions
    public final PageAccessDao pageAccessDao;

    // Feedback (independent of tickets, voting-enabled)
    public final FeedbackDao feedbackDao;
    public final FeedbackConfigDao feedbackConfigDao;
    public final FeedbackService feedback;

    // Grim anticheat bridge (softdepend) - reads Grim's own violations.sqlite
    public final GrimBridge grim;

    // Autoresponders
    public final AutoresponderDao autoresponderDao;
    public final AutoresponderService autoresponders;

    // Bulk onboard: wired after DiscordService exists so the dashboard handler
    // can iterate cached guild members.
    private BulkOnboardService bulkOnboard;

    // Timezones + simple event scheduler
    public final UserTimezoneDao userTimezoneDao;
    public final TimezoneConfigDao timezoneConfigDao;
    public final ScheduledEventDao scheduledEventDao;
    public final EventRsvpDao eventRsvpDao;
    public final TimezoneService timezones;

    // Alerts: dao built upfront; service + manager attached after DiscordService exists.
    public final AlertDao alertDao;
    private AlertService alertService;
    private AlertManager alertManager;

    // API keys: bearer-token credentials for the public /api/v1 endpoints.
    public final ApiKeyDao apiKeyDao;
    public final ApiKeyService apiKeys;

    // Debug reports
    public final DebugReportDao debugReportDao;

    // Minecraft player snapshot cache (lazy-attached so the data
    // folder can be passed in from the plugin instance).
    private io.warden.api.mc.McPlayerCache mcPlayerCache;

    public final ManifestClient manifest;
    public final ExecutorService bgExecutor;

    private DecisionService decisionService; // set by attachDecisionService(...)
    private DiscordSrvBridge discordSrv;     // set by attachDiscordSrv(...) - may stay null in tests
    private OnboardingDelivery delivery;     // set by attachDelivery(...) - owned by DiscordService

    public Services(WardenConfig config, Database database, Logger log) {
        this.config = config;
        this.database = database;
        this.log = log;

        this.userDao = new UserDao(database);
        this.linkCodeDao = new LinkCodeDao(database);
        this.questionDao = new QuestionDao(database);
        this.answerDao = new AnswerDao(database);
        this.applicationDao = new ApplicationDao(database);
        this.settingsDao = new SettingsDao(database);
        this.auditDao = new AuditDao(database);

        this.discordMessageDao = new DiscordMessageDao(database);
        this.discordMemberEventDao = new DiscordMemberEventDao(database);
        this.discordVoiceSessionDao = new DiscordVoiceSessionDao(database);
        this.mcSessionDao = new McSessionDao(database);
        this.analyticsMetaDao = new AnalyticsMetaDao(database);
        this.dailyMetricsDao = new DailyMetricsDao(database);
        this.inviteDao = new InviteDao(database);

        this.audit = new AuditService(auditDao, log);
        this.linkCodes = new LinkCodeService(linkCodeDao);
        this.onboarding = new OnboardingService(
                userDao, questionDao, answerDao, applicationDao, settingsDao, audit, log);

        this.manifest = new ManifestClient(log);
        this.bgExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "warden-bg");
            t.setDaemon(true);
            return t;
        });

        this.geoip = new GeoIpService(resolveGeoIp(config, settingsDao, log), bgExecutor, log);
        this.analytics = new AnalyticsService(
                discordMessageDao, discordMemberEventDao, discordVoiceSessionDao,
                mcSessionDao, analyticsMetaDao, bgExecutor, geoip, log);
        this.rollup = new RollupJob(database, dailyMetricsDao, analyticsMetaDao, log);
        this.cohorts = new CohortJob(database, dailyMetricsDao, analyticsMetaDao, log);
        this.sslExpiry = new SslExpiryNotifier(config, settingsDao, analyticsMetaDao, audit, log);

        // Moderation
        this.automodConfigDao = new AutomodConfigDao(database);
        this.warningDao = new WarningDao(database);
        this.modActionDao = new ModActionDao(database);
        this.raidProtectionDao = new RaidProtectionDao(database);
        this.autoMod = new AutoModService(automodConfigDao, warningDao, audit, log);
        this.moderation = new ModerationService(modActionDao, warningDao, audit, log);

        // Levels
        this.levelConfigDao = new LevelConfigDao(database);
        this.levelUserDao = new LevelUserDao(database);
        this.levelRewardDao = new LevelRewardDao(database);
        this.levelService = new LevelService(levelConfigDao, levelUserDao, levelRewardDao, userDao, log);

        // Reaction roles
        this.reactionRoleDao = new ReactionRoleDao(database);
        this.reactionRoles = new ReactionRoleService(reactionRoleDao, audit, log);

        // Engagement
        this.pollDao = new PollDao(database);
        this.giveawayDao = new GiveawayDao(database);
        this.reminderDao = new ReminderDao(database);
        this.engagement = new EngagementService(pollDao, giveawayDao, reminderDao, audit, log);
        this.engagementScheduler = new EngagementScheduler(engagement, moderation, settingsDao, config, log);

        // Tickets
        this.ticketDao = new TicketDao(database);
        this.ticketCategoryDao = new TicketCategoryDao(database);
        this.ticketPanelDao = new TicketPanelDao(database);
        this.ticketsConfigDao = new TicketsConfigDao(database);
        java.nio.file.Path ticketAttachmentsDir = config.dbFile().getParent() == null
                ? java.nio.file.Path.of("ticket-attachments")
                : config.dbFile().getParent().resolve("ticket-attachments");
        this.ticketTranscripts = new TranscriptService(ticketDao, ticketAttachmentsDir, log);
        this.tickets = new TicketService(
                ticketDao, ticketCategoryDao, ticketPanelDao, ticketsConfigDao, audit,
                settingsDao, ticketAttachmentsDir, ticketTranscripts, log);

        // Per-page permissions
        this.pageAccessDao = new PageAccessDao(database);

        // Feedback
        this.feedbackDao = new FeedbackDao(database);
        this.feedbackConfigDao = new FeedbackConfigDao(database);
        this.feedback = new FeedbackService(feedbackDao, feedbackConfigDao, audit, log);

        // Grim anticheat
        this.grim = new GrimBridge(log);

        // Autoresponders: bridge stays null until WardenPlugin wires DiscordSRV in
        // via attachDiscordSrv. The service reads the bridge lazily so a late
        // attach still works.
        this.autoresponderDao = new AutoresponderDao(database);
        this.autoresponders = new AutoresponderService(autoresponderDao, this::discordSrv, log);

        // Timezones
        this.userTimezoneDao = new UserTimezoneDao(database);
        this.timezoneConfigDao = new TimezoneConfigDao(database);
        this.scheduledEventDao = new ScheduledEventDao(database);
        this.eventRsvpDao = new EventRsvpDao(database);
        this.timezones = new TimezoneService(
                userTimezoneDao, timezoneConfigDao, scheduledEventDao, eventRsvpDao,
                geoip, audit, log);

        // Alerts: dao only; AlertService is wired in WardenPlugin once DiscordService exists.
        this.alertDao = new AlertDao(database);

        // API keys
        this.apiKeyDao = new ApiKeyDao(database);
        this.apiKeys = new ApiKeyService(apiKeyDao, audit, log);

        // Debug reports
        this.debugReportDao = new DebugReportDao(database);
    }

    public void attachAlertService(AlertService svc) { this.alertService = svc; }
    public AlertService alertService() { return alertService; }
    public void attachAlertManager(AlertManager mgr) { this.alertManager = mgr; }
    public AlertManager alertManager() { return alertManager; }

    public void attachBulkOnboard(BulkOnboardService svc) { this.bulkOnboard = svc; }
    public BulkOnboardService bulkOnboard() { return bulkOnboard; }

    public void attachMcPlayerCache(io.warden.api.mc.McPlayerCache cache) { this.mcPlayerCache = cache; }
    public io.warden.api.mc.McPlayerCache mcPlayerCache() { return mcPlayerCache; }

    /**
     * Build the {@link WardenConfig.GeoIp} record GeoIpService should run with,
     * preferring values the operator set via /dash/config over the
     * config.yml defaults. The {@code WARDEN_GEOIP_LICENSE_KEY} env var still
     * wins, because the env override has already been folded into
     * {@code config.geoip().licenseKey()} by {@link WardenConfig#load}.
     */
    private static WardenConfig.GeoIp resolveGeoIp(
            WardenConfig config, io.warden.data.dao.SettingsDao settingsDao, Logger log) {
        WardenConfig.GeoIp base = config.geoip();
        try {
            var settings = settingsDao.get();
            String envKey = System.getenv("WARDEN_GEOIP_LICENSE_KEY");
            boolean envSet = envKey != null && !envKey.isBlank();
            // env > DB > config.yml
            String key = envSet
                    ? base.licenseKey()
                    : (settings.geoipLicenseKey() == null || settings.geoipLicenseKey().isBlank()
                            ? base.licenseKey()
                            : settings.geoipLicenseKey());
            // Enabled: true if either source says so. DB toggle alone is enough; admins
            // shouldn't need both a config.yml flip AND a dashboard toggle.
            boolean enabled = base.enabled() || settings.geoipEnabled();
            return new WardenConfig.GeoIp(enabled, key,
                    base.edition(), base.refreshDays(), base.dbDir());
        } catch (Exception e) {
            log.log(java.util.logging.Level.WARNING,
                    "geoip: failed to read settings row at startup; using config.yml only", e);
            return base;
        }
    }

    public void attachDecisionService(DecisionService ds) {
        this.decisionService = ds;
        this.onboarding.setOnApplicationSubmitted(ds::triageAsync);
    }

    public DecisionService decisionService() {
        return decisionService;
    }

    public void attachDiscordSrv(DiscordSrvBridge bridge) {
        this.discordSrv = bridge;
    }

    public DiscordSrvBridge discordSrv() {
        return discordSrv;
    }

    public void attachDelivery(OnboardingDelivery delivery) {
        this.delivery = delivery;
    }

    public OnboardingDelivery delivery() {
        return delivery;
    }

    public void shutdownBackground() {
        try {
            grim.shutdown();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            engagementScheduler.stop();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            rollup.stop();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            cohorts.stop();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            sslExpiry.stop();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            geoip.close();
        } catch (Exception ignored) {
            // best-effort
        }
        try {
            bgExecutor.shutdown();
            if (!bgExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                bgExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            bgExecutor.shutdownNow();
        }
    }
}
