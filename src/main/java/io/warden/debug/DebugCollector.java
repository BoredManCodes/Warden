package io.warden.debug;

import io.warden.Services;
import io.warden.WardenPlugin;
import io.warden.config.WardenConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects a snapshot of the running plugin state for a debug report.
 * All secrets are redacted; the output is safe to encrypt and share with
 * server operators or support staff.
 */
public final class DebugCollector {

    private static final DateTimeFormatter UTC_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneId.of("UTC"));

    private final WardenPlugin plugin;
    private final Services     services;

    public DebugCollector(WardenPlugin plugin, Services services) {
        this.plugin   = plugin;
        this.services = services;
    }

    public Map<String, Object> collect() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generated_at",    UTC_FMT.format(Instant.now()));
        root.put("generated_at_ms", System.currentTimeMillis());
        root.put("boot_id",         safeBootId());
        root.put("system",   collectSystem());
        root.put("plugin",   collectPlugin());
        root.put("config",   collectConfig());
        root.put("discord",  collectDiscord());
        root.put("web",      collectWeb());
        root.put("database", collectDatabase());
        root.put("modules",  collectModules());
        root.put("llm",      collectLlm());
        root.put("files",    collectFiles());
        return root;
    }

    // ── System ──────────────────────────────────────────────────────────────

    private Map<String, Object> collectSystem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("java_version", System.getProperty("java.version"));
        m.put("java_vendor",  System.getProperty("java.vendor"));
        m.put("os",           System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("arch",         System.getProperty("os.arch"));
        m.put("cpu_cores",    Runtime.getRuntime().availableProcessors());
        m.put("thread_count", Thread.activeCount());
        try {
            long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
            m.put("uptime_ms",      uptimeMs);
            m.put("uptime_minutes", uptimeMs / 60_000);
        } catch (Exception ignored) {}
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            m.put("heap_used_mb",      heap.getUsed()      / 1_048_576L);
            m.put("heap_committed_mb", heap.getCommitted() / 1_048_576L);
            m.put("heap_max_mb",       heap.getMax()       / 1_048_576L);
        } catch (Exception ignored) {}
        return m;
    }

    // ── Plugin ──────────────────────────────────────────────────────────────

    private Map<String, Object> collectPlugin() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("version",         plugin.getDescription().getVersion());
            m.put("server_version",  plugin.getServer().getVersion());
            m.put("bukkit_version",  plugin.getServer().getBukkitVersion());
            m.put("online_players",  plugin.getServer().getOnlinePlayers().size());
            m.put("max_players",     plugin.getServer().getMaxPlayers());
        } catch (Exception e) {
            m.put("error", safeMsg(e));
        }
        return m;
    }

    // ── Config ──────────────────────────────────────────────────────────────

    private Map<String, Object> collectConfig() {
        Map<String, Object> root = new LinkedHashMap<>();
        WardenConfig cfg = services.config;

        Map<String, Object> discord = new LinkedHashMap<>();
        discord.put("bot_token",             redact(cfg.discordBotToken()));
        discord.put("client_id",             cfg.discordClientId());
        discord.put("client_secret",         redact(cfg.discordClientSecret()));
        discord.put("guild_id",              cfg.discordGuildId());
        discord.put("bootstrap_mod_role_id", cfg.bootstrapModRoleId());
        root.put("discord", discord);

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("bind_host",      cfg.webBindHost());
        web.put("bind_port",      cfg.webBindPort());
        web.put("public_url",     cfg.webPublicUrl());
        web.put("session_secret", isSet(cfg.webSessionSecret()) ? "[SET]" : "[NOT SET]");
        web.put("ssl_enabled",    cfg.ssl().enabled());
        web.put("ssl_port",       cfg.ssl().port());
        web.put("ssl_redirect",   cfg.ssl().redirectHttp());
        root.put("web", web);

        Map<String, Object> db = new LinkedHashMap<>();
        db.put("file", cfg.dbFile().toString());
        root.put("db", db);

        Map<String, Object> geoip = new LinkedHashMap<>();
        geoip.put("enabled",     cfg.geoip().enabled());
        geoip.put("edition",     cfg.geoip().edition());
        geoip.put("license_key", isSet(cfg.geoip().licenseKey()) ? "[SET]" : "[NOT SET]");
        root.put("geoip", geoip);

        return root;
    }

    // ── Discord ─────────────────────────────────────────────────────────────

    private Map<String, Object> collectDiscord() {
        Map<String, Object> m = new LinkedHashMap<>();
        var ds = plugin.discordService();
        if (ds == null) {
            m.put("status", "not_started");
            return m;
        }
        boolean ready = ds.isReady();
        m.put("ready", ready);
        try {
            var jda = ds.jda();
            if (jda != null) {
                m.put("jda_status",   jda.getStatus().toString());
                m.put("bot_user_id",  jda.getSelfUser().getId());
                m.put("bot_username", jda.getSelfUser().getName());
                var guild = jda.getGuildById(services.config.discordGuildId());
                if (guild != null) {
                    m.put("guild_name",         guild.getName());
                    m.put("guild_member_count", guild.getMemberCount());
                    m.put("guild_locale",       guild.getLocale().name());
                    m.put("guild_boost_tier",   guild.getBoostTier().name());
                } else {
                    m.put("guild_found_in_cache", false);
                }
            } else {
                m.put("jda_status", "null");
            }
        } catch (Exception e) {
            m.put("error", safeMsg(e));
        }
        return m;
    }

    // ── Web ─────────────────────────────────────────────────────────────────

    private Map<String, Object> collectWeb() {
        Map<String, Object> m = new LinkedHashMap<>();
        var ws = plugin.webService();
        m.put("running",    ws != null && ws.isRunning());
        m.put("ssl_active", ws != null && ws.sslActive());
        if (ws != null && ws.sslLoadError() != null) {
            m.put("ssl_load_error", ws.sslLoadError());
        }
        m.put("bind_port",  services.config.webBindPort());
        m.put("public_url", services.config.webPublicUrl());
        return m;
    }

    // ── Database ─────────────────────────────────────────────────────────────

    private Map<String, Object> collectDatabase() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            long bytes = java.nio.file.Files.size(services.database.file());
            m.put("file_size_kb", bytes / 1024);
            m.put("file_size_mb", bytes / 1_048_576);
        } catch (Exception ignored) {}

        String[] tables = {
            "users", "applications", "link_codes", "questions", "answers",
            "audit_log", "settings",
            "tickets", "ticket_messages", "ticket_categories",
            "polls", "poll_votes", "giveaways", "giveaway_entries",
            "feedback", "feedback_votes",
            "alerts", "autoresponders",
            "level_users", "reaction_role_groups",
            "reminders", "scheduled_events",
            "debug_reports"
        };
        Map<String, Object> counts = new LinkedHashMap<>();
        try (Connection c = services.database.connection();
             Statement st = c.createStatement()) {
            for (String tbl : tables) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tbl)) {
                    counts.put(tbl, rs.getInt(1));
                } catch (Exception ignored) {
                    counts.put(tbl, null);
                }
            }
        } catch (Exception e) {
            m.put("count_error", safeMsg(e));
        }
        m.put("table_counts", counts);
        return m;
    }

    // ── Modules ──────────────────────────────────────────────────────────────

    private Map<String, Object> collectModules() {
        Map<String, Object> m = new LinkedHashMap<>();
        var mods = services.config.modules();
        m.put("moderation",       mods.moderation());
        m.put("violations",       mods.violations());
        m.put("levels",           mods.levels());
        m.put("reaction_roles",   mods.reactionRoles());
        m.put("engagement",       mods.engagement());
        m.put("tickets",          mods.tickets());
        m.put("feedback",         mods.feedback());
        m.put("alerts",           mods.alerts());
        m.put("autoresponders",   mods.autoresponders());
        m.put("events_timezones", mods.eventsTimezones());
        return m;
    }

    // ── LLM ──────────────────────────────────────────────────────────────────

    private Map<String, Object> collectLlm() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            var settings = services.settingsDao.get();
            String apiKey = settings.llmApiKey();
            boolean keySet = isSet(apiKey);
            m.put("api_key_set", keySet);
            if (keySet && apiKey.length() > 8) {
                m.put("api_key_prefix", apiKey.substring(0, 8) + "...");
            }
            String baseUrl = settings.llmBaseUrl();
            m.put("base_url", isSet(baseUrl) ? baseUrl : "(default)");
            m.put("model",    isSet(settings.llmModel()) ? settings.llmModel() : "(default)");
            m.put("triage_mode",           settings.flow().triageMode().name());
            m.put("auto_approve_threshold", settings.llmAutoApproveThreshold());
            m.put("auto_deny_threshold",    settings.llmAutoDenyThreshold());
            m.put("auto_deny_enabled",      settings.llmAutoDenyEnabled());
        } catch (Exception e) {
            m.put("error", safeMsg(e));
        }
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long safeBootId() {
        try { return plugin.bootId(); } catch (Exception e) { return -1; }
    }

    private static String redact(String s) {
        return isSet(s) ? "[REDACTED]" : "[NOT SET]";
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.getClass().getSimpleName();
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> collectFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        java.io.File pluginDir  = plugin.getDataFolder().getAbsoluteFile();
        java.io.File pluginsDir = pluginDir.getParentFile();
        if (pluginsDir == null) return files;
        java.io.File serverRoot = pluginsDir.getParentFile();
        if (serverRoot == null) return files;

        // Warden's own config
        addTextFile(files, serverRoot, new java.io.File(pluginDir, "config.yml"));

        // Paper / Spigot / Bukkit server configs
        java.io.File cfgDir = new java.io.File(serverRoot, "config");
        addTextFile(files, serverRoot, new java.io.File(cfgDir, "paper-global.yml"));
        addTextFile(files, serverRoot, new java.io.File(cfgDir, "paper-world-defaults.yml"));
        addTextFile(files, serverRoot, new java.io.File(serverRoot, "paper.yml"));
        addTextFile(files, serverRoot, new java.io.File(serverRoot, "spigot.yml"));
        addTextFile(files, serverRoot, new java.io.File(serverRoot, "bukkit.yml"));
        addTextFile(files, serverRoot, new java.io.File(serverRoot, "server.properties"));

        // Other plugin configs
        addTextFile(files, serverRoot, new java.io.File(new java.io.File(pluginsDir, "GrimAC"), "config.yml"));
        addTextFile(files, serverRoot, new java.io.File(new java.io.File(pluginsDir, "Grim"),   "config.yml"));

        // All DiscordSRV YML files (config, messages, synchronization, linking, voice, alerts, etc.)
        java.io.File discordSrvDir = new java.io.File(pluginsDir, "DiscordSRV");
        if (discordSrvDir.isDirectory()) {
            java.io.File[] dsYmls = discordSrvDir.listFiles(f -> f.isFile() && f.getName().endsWith(".yml"));
            if (dsYmls != null) {
                java.util.Arrays.sort(dsYmls);
                for (java.io.File yml : dsYmls) addTextFile(files, serverRoot, yml);
            }
        }

        // Server log
        addLogFile(files, serverRoot, new java.io.File(new java.io.File(serverRoot, "logs"), "latest.log"), 500);

        return files;
    }

    // Matches YAML lines whose key is a known secret (case-insensitive).
    private static final java.util.regex.Pattern SECRET_LINE = java.util.regex.Pattern.compile(
            "(?im)^([ \\t]*(?:bot[-_]?token|client[-_]?secret|password)[ \\t]*:)[ \\t]*.+$");

    private static String redactSecrets(String content) {
        return SECRET_LINE.matcher(content).replaceAll("$1 [REDACTED]");
    }

    private static void addTextFile(List<Map<String, Object>> list, java.io.File serverRoot, java.io.File file) {
        if (!file.exists() || !file.isFile()) return;
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("path", serverRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        } catch (Exception e) {
            m.put("path", file.getName());
        }
        try {
            String content = redactSecrets(
                    java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8));
            boolean truncated = content.length() > 50_000;
            if (truncated) content = content.substring(0, 50_000);
            m.put("content", content);
            m.put("truncated", truncated);
        } catch (Exception e) {
            m.put("content", "");
            m.put("error", safeMsg(e));
        }
        list.add(m);
    }

    private static void addLogFile(List<Map<String, Object>> list, java.io.File serverRoot, java.io.File file, int maxLines) {
        if (!file.exists() || !file.isFile()) return;
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("path", serverRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        } catch (Exception e) {
            m.put("path", file.getName());
        }
        try {
            java.util.ArrayDeque<String> deque = new java.util.ArrayDeque<>(maxLines + 1);
            int[] total = {0};
            try (java.util.stream.Stream<String> stream = java.nio.file.Files.lines(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                stream.forEach(line -> {
                    deque.add(line);
                    total[0]++;
                    if (deque.size() > maxLines) deque.poll();
                });
            }
            boolean truncated = total[0] > maxLines;
            m.put("content", String.join("\n", deque));
            m.put("truncated", truncated);
            if (truncated) m.put("total_lines", total[0]);
        } catch (Exception e) {
            m.put("content", "");
            m.put("error", safeMsg(e));
        }
        list.add(m);
    }
}
