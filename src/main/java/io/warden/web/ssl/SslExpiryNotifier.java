package io.warden.web.ssl;

import io.warden.audit.AuditService;
import io.warden.config.WardenConfig;
import io.warden.data.dao.AnalyticsMetaDao;
import io.warden.data.dao.SettingsDao;
import io.warden.discord.DiscordService;
import io.warden.discord.WardenEmbeds;
import io.warden.onboarding.model.Settings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.awt.Color;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DMs the Discord server owner plus anyone holding the Config-admin or
 * Web-manager role when the configured TLS certificate is approaching expiry.
 *
 * Runs once a day on a dedicated single-thread scheduler. Each cert (identified
 * by its notAfter timestamp) gets at most one DM per threshold - {30, 14, 7, 3,
 * 1, expired}. Renewals are detected automatically: when notAfter changes,
 * the per-threshold "already fired" set resets.
 *
 * State is persisted in {@code analytics_meta} under the key
 * {@code ssl_expiry_notifier_state}, serialised as
 * {@code <notAfterEpochSeconds>:<csvOfFiredThresholds>}.
 */
public final class SslExpiryNotifier {

    private static final int[] THRESHOLDS = {30, 14, 7, 3, 1, 0};
    private static final String META_KEY = "ssl_expiry_notifier_state";
    private static final long INITIAL_DELAY_SEC = 60;
    private static final long PERIOD_SEC = TimeUnit.DAYS.toSeconds(1);

    private final WardenConfig config;
    private final SettingsDao settingsDao;
    private final AnalyticsMetaDao meta;
    private final AuditService audit;
    private final Logger log;

    private volatile DiscordService discord;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    public SslExpiryNotifier(WardenConfig config, SettingsDao settingsDao,
                             AnalyticsMetaDao meta, AuditService audit, Logger log) {
        this.config = config;
        this.settingsDao = settingsDao;
        this.meta = meta;
        this.audit = audit;
        this.log = log;
    }

    /** Wired after DiscordService is constructed - DMs need JDA. */
    public void attachDiscord(DiscordService discord) {
        this.discord = discord;
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "warden-ssl-expiry");
            t.setDaemon(true);
            return t;
        });
        task = scheduler.scheduleAtFixedRate(this::runOnce,
                INITIAL_DELAY_SEC, PERIOD_SEC, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (task != null) task.cancel(false);
        if (scheduler != null) scheduler.shutdownNow();
        task = null;
        scheduler = null;
    }

    /** Public for tests and for a future /warden ssl-check command. */
    public void runOnce() {
        try {
            if (!config.ssl().usable()) return;
            Optional<SslSupport.CertSummary> certOpt = SslSupport.inspect(config.ssl().certFile());
            if (certOpt.isEmpty()) return;
            SslSupport.CertSummary cert = certOpt.get();

            long notAfter = cert.notAfter().getEpochSecond();
            long days = cert.daysUntilExpiry();
            boolean expired = cert.expired();

            State state = loadState();
            if (state.notAfter != notAfter) {
                state = new State(notAfter, new HashSet<>());
                saveState(state);
            }

            for (int t : THRESHOLDS) {
                if (state.fired.contains(t)) continue;
                boolean crossed = (t == 0) ? expired : (!expired && days <= t);
                if (!crossed) continue;
                int sent = notifyRecipients(t, cert, days, expired);
                state.fired.add(t);
                saveState(state);
                log.info("ssl-expiry: threshold=" + t + " days=" + days
                        + " expired=" + expired + " dms_sent=" + sent);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "ssl-expiry: pass failed", e);
        }
    }

    private int notifyRecipients(int threshold, SslSupport.CertSummary cert,
                                 long days, boolean expired) {
        DiscordService d = discord;
        if (d == null) return 0;
        JDA jda = d.jda();
        if (jda == null) return 0;
        Guild guild = jda.getGuildById(config.discordGuildId());
        if (guild == null) {
            log.warning("ssl-expiry: configured guild " + config.discordGuildId() + " not cached; skipping DMs");
            return 0;
        }

        Set<String> recipients = new LinkedHashSet<>();
        String ownerId = guild.getOwnerId();
        if (ownerId != null && !ownerId.isBlank()) recipients.add(ownerId);

        Settings s;
        try {
            s = settingsDao.get();
        } catch (Exception e) {
            log.warning("ssl-expiry: settings read failed: " + e.getMessage());
            return 0;
        }
        addRoleMembers(guild, s.configAdminRoleId(), recipients);
        addRoleMembers(guild, s.webManagerRoleId(), recipients);

        if (recipients.isEmpty()) return 0;

        EmbedBuilder eb = buildEmbed(cert, days, expired);
        for (String id : recipients) {
            sendDm(jda, id, eb);
        }

        try {
            audit.write("system", "ssl_expiry_notified", null, Map.of(
                    "threshold_days", threshold,
                    "days_until_expiry", days,
                    "expired", expired,
                    "recipients", recipients.size(),
                    "not_after", cert.notAfter().toString()));
        } catch (Exception e) {
            log.warning("ssl-expiry: audit write failed: " + e.getMessage());
        }
        return recipients.size();
    }

    private static void addRoleMembers(Guild g, String roleId, Set<String> sink) {
        if (roleId == null || roleId.isBlank()) return;
        Role role = g.getRoleById(roleId);
        if (role == null) return;
        List<Member> members = g.getMembersWithRoles(role);
        for (Member m : members) {
            if (m.getUser().isBot()) continue;
            sink.add(m.getId());
        }
    }

    private static EmbedBuilder buildEmbed(SslSupport.CertSummary cert, long days, boolean expired) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .withZone(ZoneOffset.UTC);

        String title = expired
                ? "Warden HTTPS certificate has expired"
                : "Warden HTTPS certificate expires in " + days + " day" + (days == 1 ? "" : "s");

        Color color;
        if (expired || days <= 3) color = new Color(0xDC, 0x35, 0x45);
        else if (days <= 7)       color = new Color(0xF6, 0xB7, 0x3C);
        else                      color = new Color(0xFF, 0xC1, 0x07);

        StringBuilder desc = new StringBuilder();
        if (expired) {
            desc.append("The certificate Warden uses to serve HTTPS has **expired**. ")
                    .append("Browsers will refuse to load the dashboard, onboarding flow, ")
                    .append("and landing page until you renew it.\n\n");
        } else {
            desc.append("Renew the certificate Warden uses to serve HTTPS before it expires - ")
                    .append("once it lapses, browsers will start refusing the dashboard, ")
                    .append("onboarding flow, and landing page.\n\n");
        }
        desc.append("**Expires:** ").append(fmt.format(cert.notAfter())).append("\n");
        String cn = cnOf(cert.subject());
        if (cn != null) desc.append("**Hostname:** `").append(cn).append("`\n");
        desc.append("**Issuer:** `").append(shortIssuer(cert.issuer())).append("`\n\n");
        desc.append("Open the **HTTPS** tab on your Warden dashboard (`/dash/https`) for ")
                .append("renewal instructions - certbot on Linux, win-acme on Windows. ")
                .append("Restart the Minecraft server afterwards so Warden picks up the new files.");

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(title)
                .setColor(color)
                .setDescription(desc.toString());
        return WardenEmbeds.brand(eb);
    }

    private void sendDm(JDA jda, String discordId, EmbedBuilder eb) {
        jda.retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(
                        ch -> ch.sendMessageEmbeds(eb.build()).queue(
                                ok -> {},
                                err -> log.warning("ssl-expiry: DM to " + discordId
                                        + " send failed: " + err.getMessage())),
                        err -> log.warning("ssl-expiry: openPrivateChannel " + discordId
                                + " failed: " + err.getMessage())),
                err -> log.warning("ssl-expiry: retrieveUser " + discordId
                        + " failed: " + err.getMessage()));
    }

    private static String cnOf(String dn) {
        if (dn == null) return null;
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
        }
        return null;
    }

    private static String shortIssuer(String dn) {
        String cn = cnOf(dn);
        return cn != null ? cn : (dn == null ? "" : dn);
    }

    // --- state persistence ---------------------------------------------------

    private static final class State {
        long notAfter;
        final Set<Integer> fired;
        State(long notAfter, Set<Integer> fired) {
            this.notAfter = notAfter;
            this.fired = fired;
        }
    }

    private State loadState() {
        try {
            Optional<String> raw = meta.get(META_KEY);
            if (raw.isEmpty()) return new State(0, new HashSet<>());
            String[] parts = raw.get().split(":", 2);
            long na = Long.parseLong(parts[0]);
            Set<Integer> fired = new HashSet<>();
            if (parts.length > 1 && !parts[1].isBlank()) {
                for (String s : parts[1].split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) fired.add(Integer.parseInt(trimmed));
                }
            }
            return new State(na, fired);
        } catch (Exception e) {
            return new State(0, new HashSet<>());
        }
    }

    private void saveState(State s) {
        try {
            List<Integer> sorted = new ArrayList<>(s.fired);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            sb.append(s.notAfter).append(':');
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(sorted.get(i));
            }
            meta.put(META_KEY, sb.toString());
        } catch (Exception e) {
            log.warning("ssl-expiry: state save failed: " + e.getMessage());
        }
    }
}
