package io.warden.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * Resolved Warden configuration. Secret-shaped keys (tokens, secret keys)
 * are env-first: if the env var is set we use it; otherwise we fall back to config.yml.
 * Public/non-secret values come straight from config.yml.
 *
 * Note: LLM endpoint settings (api key, base url, model) live on the settings
 * row (editable from /dash/config without a restart) so any OpenAI-compatible
 * gateway can be targeted.
 */
public record WardenConfig(
        String discordBotToken,
        String discordClientId,
        String discordClientSecret,
        String discordGuildId,
        String bootstrapModRoleId,
        String webBindHost,
        int webBindPort,
        String webPublicUrl,
        String webSessionSecret,
        Path dbFile,
        Path wwwDir,
        Ssl ssl,
        GeoIp geoip,
        Modules modules
) {
    public static WardenConfig load(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();

        String botToken     = envOr("WARDEN_DISCORD_BOT_TOKEN", cfg.getString("discord.bot_token", ""));
        String clientId     = cfg.getString("discord.client_id", "");
        String clientSecret = envOr("WARDEN_DISCORD_CLIENT_SECRET", cfg.getString("discord.client_secret", ""));
        String guildId      = cfg.getString("discord.guild_id", "");
        String bootstrapMod = cfg.getString("discord.bootstrap_mod_role_id", "");

        String bindHost     = cfg.getString("web.bind_host", "0.0.0.0");
        int    bindPort     = cfg.getInt("web.bind_port", 8788);
        String publicUrl    = cfg.getString("web.public_url", "http://localhost:" + bindPort);
        String sessionSec   = envOr("WARDEN_SESSION_SECRET", cfg.getString("web.session_secret", ""));

        String dbFileStr    = cfg.getString("db.file", "data/warden.db");
        Path dbFile         = plugin.getDataFolder().toPath().resolve(dbFileStr);

        String wwwDirStr    = cfg.getString("web.www_dir", "www");
        Path wwwDir         = plugin.getDataFolder().toPath().resolve(wwwDirStr);

        boolean sslEnabled  = cfg.getBoolean("web.ssl.enabled", false);
        int     sslPort     = cfg.getInt("web.ssl.port", 8443);
        String  sslCertStr  = cfg.getString("web.ssl.cert_file", "ssl/fullchain.pem");
        String  sslKeyStr   = cfg.getString("web.ssl.key_file",  "ssl/privkey.pem");
        boolean sslRedirect = cfg.getBoolean("web.ssl.redirect_http", true);
        Path    sslCert     = plugin.getDataFolder().toPath().resolve(sslCertStr);
        Path    sslKey      = plugin.getDataFolder().toPath().resolve(sslKeyStr);

        Modules modules = Modules.load(cfg);

        boolean geoEnabled  = cfg.getBoolean("analytics.geoip.enabled", false);
        String  geoLicense  = envOr("WARDEN_GEOIP_LICENSE_KEY",
                cfg.getString("analytics.geoip.license_key", ""));
        String  geoEdition  = cfg.getString("analytics.geoip.edition", "GeoLite2-Country");
        int     geoRefresh  = Math.max(1, cfg.getInt("analytics.geoip.refresh_days", 7));
        String  geoDirStr   = cfg.getString("analytics.geoip.db_dir", "data/geoip");
        Path    geoDir      = plugin.getDataFolder().toPath().resolve(geoDirStr);

        return new WardenConfig(
                botToken.trim(),
                clientId.trim(),
                clientSecret.trim(),
                guildId.trim(),
                bootstrapMod.trim(),
                bindHost.trim(),
                bindPort,
                stripTrailingSlash(publicUrl.trim()),
                sessionSec.trim(),
                dbFile,
                wwwDir,
                new Ssl(sslEnabled, sslPort, sslCert, sslKey, sslRedirect),
                new GeoIp(geoEnabled, geoLicense.trim(), geoEdition.trim(), geoRefresh, geoDir),
                modules
        );
    }

    /**
     * Native HTTPS via Jetty. When {@link #enabled()} is true and both PEM files
     * exist, WebService binds a second connector on {@link #port()} with TLS
     * terminated in-plugin. {@link #redirectHttp()} controls whether plain-HTTP
     * requests on the original port get 301'd to the HTTPS public URL.
     */
    public record Ssl(boolean enabled, int port, Path certFile, Path keyFile, boolean redirectHttp) {
        public boolean usable() {
            return enabled && certFile != null && keyFile != null;
        }
    }

    /**
     * GeoIP lookup is opt-in. When {@link #enabled()} is true and a MaxMind
     * licence key is configured, Warden downloads (and weekly refreshes) the
     * configured GeoLite2 edition into {@link #dbDir()} and tags MC sessions
     * with the player's country. When disabled - or when the licence key is
     * missing - the Geography page just reports that GeoIP isn't configured.
     */
    public record GeoIp(boolean enabled, String licenseKey, String edition, int refreshDays, Path dbDir) {
        public boolean hasLicense() {
            return enabled && licenseKey != null && !licenseKey.isBlank();
        }
    }

    /**
     * Feature-module toggles. Each flag gates one logical subsystem (listeners,
     * dashboard pages, API routes, background jobs). Edited from
     * /dash/config &gt; Modules tab and persisted to config.yml; takes effect on
     * the next plugin start. Defaults are all-on so existing installs keep
     * working unchanged.
     */
    public record Modules(
            boolean moderation,
            boolean violations,
            boolean levels,
            boolean reactionRoles,
            boolean engagement,
            boolean tickets,
            boolean feedback,
            boolean alerts,
            boolean autoresponders,
            boolean eventsTimezones
    ) {
        public static Modules load(FileConfiguration cfg) {
            return new Modules(
                    cfg.getBoolean("modules.moderation",        true),
                    cfg.getBoolean("modules.violations",        true),
                    cfg.getBoolean("modules.levels",            true),
                    cfg.getBoolean("modules.reaction_roles",    true),
                    cfg.getBoolean("modules.engagement",        true),
                    cfg.getBoolean("modules.tickets",           true),
                    cfg.getBoolean("modules.feedback",          true),
                    cfg.getBoolean("modules.alerts",            true),
                    cfg.getBoolean("modules.autoresponders",    true),
                    cfg.getBoolean("modules.events_timezones", true)
            );
        }

        public static Modules allOn() {
            return new Modules(true, true, true, true, true, true, true, true, true, true);
        }
    }

    public boolean discordConfigured() {
        return !discordBotToken.isBlank() && !discordGuildId.isBlank();
    }

    public boolean oauthConfigured() {
        return !discordClientId.isBlank() && !discordClientSecret.isBlank() && !webSessionSecret.isBlank();
    }

    private static String envOr(String envKey, String fallback) {
        String v = System.getenv(envKey);
        return (v != null && !v.isBlank()) ? v : (fallback == null ? "" : fallback);
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }
}
