package io.warden;

import io.warden.alerts.AlertContext;
import io.warden.alerts.AlertDiscordListener;
import io.warden.alerts.AlertEvent;
import io.warden.alerts.AlertManager;
import io.warden.alerts.AlertService;
import io.warden.analytics.PlayerSessionAnalyticsListener;
import io.warden.config.WardenConfig;
import io.warden.data.Database;
import io.warden.data.SchemaLoader;
import io.warden.debug.DebugService;
import io.warden.discord.DiscordService;
import io.warden.discord.DiscordSrvBridge;
import io.warden.discord.JdaDecisionExecutor;
import io.warden.discord.OnboardingDelivery;
import io.warden.metrics.Metrics;
import io.warden.onboarding.DecisionService;
import io.warden.web.WebService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;

public final class WardenPlugin extends JavaPlugin {

    private WardenConfig config;
    private Database database;
    private Services services;
    private DiscordService discord;
    private WebService web;
    private DiscordSrvBridge discordSrv;
    private Metrics metrics;

    // Bumped on every successful bootstrap(). Surfaced via /health so the
    // dashboard's Restart modal can detect "new instance is up" by watching
    // the value change, instead of trying to catch the (sub-second) /health
    // downtime window between teardown and bootstrap.
    private final java.util.concurrent.atomic.AtomicLong bootId =
            new java.util.concurrent.atomic.AtomicLong(0);

    public long bootId() { return bootId.get(); }

    @Override
    public void onEnable() {
        bootstrap();
    }

    /**
     * Brings up every Warden subsystem (config, DB, services, Discord, web,
     * alerts, metrics). Split out from onEnable so {@link #restartSubsystems()}
     * can reuse the same wiring without going through Bukkit's plugin manager
     * (which under modern Paper closes the plugin classloader's jar on
     * disable, breaking any later class loads from Javalin/JDA).
     */
    private void bootstrap() {
        saveDefaultConfig();
        ensureSessionSecret();
        try {
            this.config = WardenConfig.load(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load Warden config - plugin disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Warden starting (web port " + config.webBindPort() + ")");

        try {
            this.database = new Database(config);
            new SchemaLoader(database, getLogger()).initialise();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database init failed - plugin disabled", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.services = new Services(config, database, getLogger());

        // Analytics: load IP salt and close any sessions orphaned by a prior crash/restart.
        services.analytics.initOnEnable();
        services.geoip.initOnEnable();
        services.rollup.start();
        services.cohorts.start();
        getServer().getPluginManager().registerEvents(
                new PlayerSessionAnalyticsListener(services.analytics), this);

        // Player-data snapshot cache. Writes plugins/Warden/playerdata/<name>.json
        // on PlayerQuitEvent so /api/v1/players/{username} can return a snapshot
        // for offline players.
        io.warden.api.mc.McPlayerCache mcCache = new io.warden.api.mc.McPlayerCache(
                getDataFolder().toPath().resolve("playerdata"), getLogger());
        services.attachMcPlayerCache(mcCache);
        getServer().getPluginManager().registerEvents(
                new io.warden.api.mc.McPlayerCacheListener(mcCache), this);
        this.discordSrv = new DiscordSrvBridge(getLogger());
        services.attachDiscordSrv(discordSrv);

        // DiscordService is constructed before .start() so we can wire JdaDecisionExecutor
        // (which captures a reference to it) and attach the triage hook before listeners fire.
        this.discord = new DiscordService(this, services);
        var executor = new JdaDecisionExecutor(discord, config.discordGuildId(), getLogger());
        var decisions = new DecisionService(
                services.settingsDao, services.applicationDao, services.questionDao,
                services.answerDao, services.userDao,
                services.manifest, executor, services.audit, services.bgExecutor,
                getLogger(), services.discordSrv());
        services.attachDecisionService(decisions);

        try {
            this.discord.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Discord bot failed to start", e);
        }

        // Bulk onboard helper. Needs DiscordService for the guild lookup; the
        // dashboard's Members page exposes a one-click button that calls it.
        services.attachBulkOnboard(new io.warden.onboarding.BulkOnboardService(
                services.userDao, services.settingsDao, services.audit,
                discord, config.discordGuildId(), getLogger()));

        // Minecraft chat -> leaderboard XP for linked players. Registered after
        // DiscordService is constructed so level-up announcements can go out
        // via JDA. The listener no-ops when DiscordSRV isn't installed or the
        // mc_xp_enabled toggle is off. Skipped entirely when the Levels module
        // is disabled in config.yml.
        if (config.modules().levels()) {
            getServer().getPluginManager().registerEvents(
                    new io.warden.levels.LevelMcChatListener(
                            services.levelService, discordSrv, discord,
                            config.discordGuildId(), getLogger()),
                    this);
        }

        // SSL expiry notifier: needs JDA to DM, so wire it up after DiscordService
        // is constructed. The daily scheduler is a no-op when SSL is off.
        services.sslExpiry.attachDiscord(discord);
        services.sslExpiry.start();

        // Alerts: construct the service + manager before WebService so
        // DashAlertsHandlers gets non-null references. Bukkit listeners come
        // from AlertManager.reload() (dynamic per-event-class registration);
        // the Discord listener and SERVER_START fire-off happen below once
        // JDA is up. Skipped entirely when the Alerts module is disabled -
        // the dashboard page also disappears in that case.
        AlertService alerts = null;
        AlertManager alertManager = null;
        if (config.modules().alerts()) {
            try {
                alerts = new AlertService(
                        services.alertDao, discord, services.audit, this, getLogger());
                services.attachAlertService(alerts);
                alertManager = new AlertManager(alerts, services.alertDao, this, getLogger());
                services.attachAlertManager(alertManager);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Alerts service construction failed", e);
            }
        }

        try {
            this.web = new WebService(this, services);
            this.web.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Web service failed to start", e);
        }

        // Open the optional Grim anticheat bridge. No-op when Grim isn't
        // installed; the dashboard's Violations tab shows an install prompt
        // in that case. Skipped entirely when the Violations module is
        // disabled in config.yml.
        if (config.modules().violations()) {
            try {
                services.grim.open();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Grim bridge open failed", e);
            }
        }

        if (alerts != null) {
            try {
                if (alertManager != null) alertManager.reload();
                if (discord != null && discord.jda() != null) {
                    discord.jda().addEventListener(
                            new AlertDiscordListener(alerts, config.discordGuildId()));
                }
                alerts.fire(AlertEvent.SERVER_START, new AlertContext());
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Alerts wiring failed", e);
            }
        }

        // bStats: anonymous usage metrics, opt-out via metrics.enabled in
        // config.yml (or globally via plugins/bStats/config.yml). Plugin id
        // 31403 = https://bstats.org/plugin/bukkit/Warden/31403.
        if (getConfig().getBoolean("metrics.enabled", true)) {
            try {
                this.metrics = new Metrics(this, 31403);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "bStats metrics init failed", e);
            }
        } else {
            getLogger().info("bStats metrics disabled by config (metrics.enabled: false).");
        }

        bootId.incrementAndGet();
        getLogger().info("Warden ready (boot " + bootId.get() + ").");
    }

    @Override
    public void onDisable() {
        teardown();
    }

    /**
     * Stops every subsystem started by {@link #bootstrap()}. Safe to call when
     * pieces failed to initialise (each block is null-guarded). Does not
     * unregister Bukkit listeners or scheduler tasks - the plugin manager
     * normally does that on disable, and {@link #restartSubsystems()} does it
     * explicitly between teardown and bootstrap.
     */
    private void teardown() {
        if (metrics != null) {
            try { metrics.shutdown(); } catch (Exception e) { getLogger().log(Level.WARNING, "metrics shutdown failed", e); }
            metrics = null;
        }
        if (services != null && services.alertService() != null) {
            try { services.alertService().fire(AlertEvent.SERVER_STOP, new AlertContext()); }
            catch (Exception e) { getLogger().log(Level.WARNING, "alerts: SERVER_STOP fire failed", e); }
        }
        if (services != null && services.alertManager() != null) {
            try { services.alertManager().shutdown(); }
            catch (Exception e) { getLogger().log(Level.WARNING, "alerts: manager shutdown failed", e); }
        }
        if (web != null) {
            try { web.stop(); } catch (Exception e) { getLogger().log(Level.WARNING, "web stop failed", e); }
            web = null;
        }
        if (discord != null) {
            try { discord.stop(); } catch (Exception e) { getLogger().log(Level.WARNING, "discord stop failed", e); }
            discord = null;
        }
        if (services != null) {
            try { services.shutdownBackground(); } catch (Exception e) { getLogger().log(Level.WARNING, "bg shutdown failed", e); }
        }
        if (database != null) {
            try { database.close(); } catch (Exception e) { getLogger().log(Level.WARNING, "db close failed", e); }
            database = null;
        }
    }

    /**
     * Cycles every Warden subsystem in place without going through Bukkit's
     * disablePlugin / enablePlugin (which on modern Paper closes the plugin
     * classloader's jar, after which Javalin classpath resource loads and JDA
     * shutdown callbacks fail with "zip file closed"). Called from the
     * dashboard's Restart button. Must run on the main server thread.
     *
     * Why: the plugin instance and its classloader stay alive across the
     * cycle, so anything that loads classes from the shaded jar (Jetty,
     * Javalin, JDA) keeps working. We do the bookkeeping the plugin manager
     * normally does on disable - clearing event listeners and scheduler tasks
     * left over from the previous cycle - between teardown and bootstrap.
     */
    public synchronized void restartSubsystems() {
        getLogger().info("Restart: cycling Warden subsystems in-process...");
        teardown();
        org.bukkit.event.HandlerList.unregisterAll(this);
        // Cancels everything except the task we're running inside right now -
        // CraftScheduler leaves tasks already executing alone.
        getServer().getScheduler().cancelTasks(this);
        bootstrap();
        getLogger().info("Restart: subsystems back up.");
    }

    public Database database() { return database; }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("warden")) return false;
        if (!sender.hasPermission("warden.admin")) {
            sender.sendMessage("§cYou don't have permission to use /warden.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7Usage: /warden <reload|status|reonboard <discordId|playerName>|debug [label]>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status" -> {
                sender.sendMessage("§eWarden: discord=" + (discord != null && discord.isReady() ? "ready" : "down")
                        + ", web=" + (web != null && web.isRunning() ? "ready" : "down"));
                return true;
            }
            case "reload" -> {
                doReload(sender);
                return true;
            }
            case "reonboard" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /warden reonboard <discordId|playerName>");
                    return true;
                }
                doReonboard(sender, args[1]);
                return true;
            }
            case "debug" -> {
                if (!sender.hasPermission("warden.debug")) {
                    sender.sendMessage("§cYou don't have permission to generate debug reports. (warden.debug)");
                    return true;
                }
                doDebug(sender, args);
                return true;
            }
            default -> {
                sender.sendMessage("§cUnknown /warden subcommand: " + args[0]);
                return true;
            }
        }
    }

    public WardenConfig wardenConfig() { return config; }
    public DiscordService discordService() { return discord; }
    public WebService webService() { return web; }

    @Override
    public @Nullable java.util.List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("warden")) return null;
        if (!sender.hasPermission("warden.admin")) return java.util.List.of();
        if (args.length == 1) {
            String pref = args[0].toLowerCase(java.util.Locale.ROOT);
            return java.util.stream.Stream.of("status", "reload", "reonboard", "debug")
                    .filter(s -> s.startsWith(pref))
                    .toList();
        }
        if (args.length == 2 && "reonboard".equalsIgnoreCase(args[0])) {
            // Suggest online player names; the resolver also accepts raw Discord ids.
            String pref = args[1].toLowerCase(java.util.Locale.ROOT);
            return getServer().getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(pref))
                    .toList();
        }
        return java.util.List.of();
    }

    /**
     * Re-reads config.yml and reports which keys changed. Settings (DB row) are
     * already read fresh on each lookup so no action is needed for those. Keys
     * bound at boot time (web port, bot token, DB file path) require a full
     * server restart to take effect; we list them so the operator knows.
     */
    private void doReload(CommandSender sender) {
        if (config == null) {
            sender.sendMessage("§cCannot reload - plugin failed to load originally. Check the server log.");
            return;
        }
        WardenConfig before = config;
        reloadConfig();
        WardenConfig after;
        try {
            after = WardenConfig.load(this);
        } catch (Exception e) {
            sender.sendMessage("§cReload failed: " + e.getMessage());
            return;
        }
        java.util.List<String> bootBoundChanges = new java.util.ArrayList<>();
        if (!before.discordBotToken().equals(after.discordBotToken()))         bootBoundChanges.add("discord.bot_token");
        if (!before.discordClientId().equals(after.discordClientId()))         bootBoundChanges.add("discord.client_id");
        if (!before.discordClientSecret().equals(after.discordClientSecret())) bootBoundChanges.add("discord.client_secret");
        if (!before.discordGuildId().equals(after.discordGuildId()))           bootBoundChanges.add("discord.guild_id");
        if (!before.webBindHost().equals(after.webBindHost()))                 bootBoundChanges.add("web.bind_host");
        if (before.webBindPort() != after.webBindPort())                       bootBoundChanges.add("web.bind_port");
        if (!before.webPublicUrl().equals(after.webPublicUrl()))               bootBoundChanges.add("web.public_url");
        if (!before.webSessionSecret().equals(after.webSessionSecret()))       bootBoundChanges.add("web.session_secret");
        if (!before.dbFile().equals(after.dbFile()))                           bootBoundChanges.add("db.file");

        this.config = after;
        sender.sendMessage("§aReloaded config.yml.");
        if (bootBoundChanges.isEmpty()) {
            sender.sendMessage("§7No boot-time keys changed. Dashboard settings are always live.");
        } else {
            sender.sendMessage("§e" + bootBoundChanges.size() + " boot-time key(s) changed - restart the server to apply:");
            for (String k : bootBoundChanges) sender.sendMessage("§e  - " + k);
        }
        if (services != null && services.audit != null) {
            services.audit.write("system", "config_reloaded", null,
                    java.util.Map.of("changedKeys", bootBoundChanges));
        }
    }

    /**
     * Resets a user's onboarding state and re-runs the delivery pipeline so
     * they get a fresh DM / channel post and a new link code. Accepts either a
     * Discord snowflake id directly, or - if DiscordSRV is installed - a
     * Minecraft player name that DiscordSRV has a linked account for.
     */
    private void doReonboard(CommandSender sender, String input) {
        if (services == null) {
            sender.sendMessage("§cCannot reonboard - services not initialised.");
            return;
        }

        String discordId = resolveReonboardTarget(sender, input);
        if (discordId == null) return; // resolver already messaged the sender

        try {
            var user = services.userDao.findByDiscordId(discordId).orElse(null);
            services.userDao.setState(discordId, io.warden.onboarding.OnboardingState.PENDING_LINK);
            services.answerDao.clearFor(discordId);
            services.audit.write("ingame:" + sender.getName(), "reonboard_reset", discordId,
                    java.util.Map.of("note", "state reset to pending_link; answers cleared",
                            "modName", sender.getName()));
            String label = (user != null && user.username() != null && !user.username().isBlank())
                    ? user.username() + " (" + discordId + ")"
                    : discordId;
            sender.sendMessage("§aReset onboarding for " + label + ".");
        } catch (Exception e) {
            sender.sendMessage("§cReonboard reset failed: " + e.getMessage());
            return;
        }

        if (discord == null) {
            sender.sendMessage("§7Discord bot isn't running - onboarding will replay next time it's up and the user is present.");
            return;
        }
        OnboardingDelivery.ReplayResult replay = discord.delivery().replayFor(discordId);
        switch (replay) {
            case OK -> sender.sendMessage("§aReplayed onboarding delivery for " + discordId + " (check the audit log).");
            case DISCORD_NOT_READY -> sender.sendMessage("§eDiscord client not ready yet; delivery did not replay.");
            case GUILD_NOT_FOUND -> sender.sendMessage("§cConfigured discord.guild_id not found by the bot.");
            case MEMBER_NOT_IN_GUILD -> sender.sendMessage("§7User isn't in the guild right now; they'll be onboarded on next join.");
            case LOOKUP_FAILED -> sender.sendMessage("§cLookup of the member failed - see server log.");
        }
    }

    /**
     * Generate an encrypted debug report and send the viewer URL to the sender.
     * Runs on the background executor so the server thread isn't blocked by the
     * DB write; replies are scheduled back via the Bukkit scheduler.
     */
    private void doDebug(CommandSender sender, String[] args) {
        if (services == null || web == null) {
            sender.sendMessage("§cCannot generate debug report - services not initialised.");
            return;
        }
        // Collect the optional label from remaining args.
        String label = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null;
        sender.sendMessage("§7Generating debug report" + (label != null && !label.isBlank() ? " (" + label + ")…" : "…"));

        DebugService svc = new DebugService(this, services, services.debugReportDao);
        services.bgExecutor.submit(() -> {
            try {
                var result = svc.generate(label);
                String url = svc.viewerUrl(result.id(), result.keyB64Url());
                // Schedule reply back on the main thread.
                getServer().getScheduler().runTask(this, () -> {
                    sender.sendMessage("§aDebug report ready. §7ID: §f" + result.id());
                    sender.sendMessage("§7URL (keep this private - it contains the decryption key):");
                    sender.sendMessage("§b" + url);

                    boolean llmConfigured = false;
                    try { llmConfigured = services.settingsDao.get().llmApiKey() != null
                            && !services.settingsDao.get().llmApiKey().isBlank(); }
                    catch (Exception ignored) {}
                    if (llmConfigured) {
                        sender.sendMessage("§7AI analysis is running in the background.");
                    } else {
                        sender.sendMessage("§7No LLM configured - AI analysis skipped.");
                    }
                });
            } catch (Exception e) {
                getServer().getScheduler().runTask(this, () ->
                        sender.sendMessage("§cFailed to generate debug report: " + e.getMessage()));
            }
        });
    }

    /**
     * Turn the raw command argument into a Discord snowflake id.
     * Direct snowflake → used as-is. Otherwise, if DiscordSRV is present, try
     * to map the player name to a linked Discord account.
     */
    private String resolveReonboardTarget(CommandSender sender, String input) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            sender.sendMessage("§cUsage: /warden reonboard <discordId|playerName>");
            return null;
        }
        if (trimmed.matches("\\d{15,21}")) return trimmed;

        DiscordSrvBridge srv = services.discordSrv();
        if (srv == null || !srv.isPresent()) {
            sender.sendMessage("§cThat doesn't look like a Discord snowflake id (15-21 digits), "
                    + "and DiscordSRV isn't installed so player names can't be resolved.");
            return null;
        }
        var resolved = srv.discordIdForPlayer(trimmed);
        if (resolved.isEmpty()) {
            sender.sendMessage("§cNo DiscordSRV link found for player '" + trimmed + "'. "
                    + "Ask them to /discord link first, or pass their Discord id directly.");
            return null;
        }
        sender.sendMessage("§7Resolved " + trimmed + " → Discord " + resolved.get() + " via DiscordSRV.");
        return resolved.get();
    }

    /**
     * Generate a random 32-byte hex session secret on first start if neither
     * web.session_secret in config.yml nor the WARDEN_SESSION_SECRET env var is
     * set, and write it back to config.yml so it persists across restarts.
     * Without a secret the OAuth + /onboard routes stay disabled, so this gets
     * fresh installs to a working state without an extra manual step.
     */
    private void ensureSessionSecret() {
        String envSecret = System.getenv("WARDEN_SESSION_SECRET");
        if (envSecret != null && !envSecret.isBlank()) return;
        String existing = getConfig().getString("web.session_secret", "");
        if (existing != null && !existing.isBlank()) return;

        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String hex = java.util.HexFormat.of().formatHex(bytes);

        getConfig().set("web.session_secret", hex);
        saveConfig();
        getLogger().info("Generated a random web.session_secret and saved it to config.yml.");
    }

}
