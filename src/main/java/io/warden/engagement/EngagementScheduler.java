package io.warden.engagement;

import io.warden.config.WardenConfig;
import io.warden.data.dao.SettingsDao;
import io.warden.moderation.ModerationService;
import net.dv8tion.jda.api.JDA;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single ticker that runs all the "polls/giveaways/reminders/mod-action expiry"
 * housekeeping every 20 seconds.
 */
public final class EngagementScheduler {

    private final EngagementService engagement;
    private final ModerationService moderation;
    private final SettingsDao settings;
    private final WardenConfig config;
    private final Logger log;
    private ScheduledExecutorService exec;
    private volatile JDA jda;

    public EngagementScheduler(EngagementService engagement, ModerationService moderation,
                               SettingsDao settings, WardenConfig config, Logger log) {
        this.engagement = engagement;
        this.moderation = moderation;
        this.settings = settings;
        this.config = config;
        this.log = log;
    }

    public void start(JDA jda) {
        this.jda = jda;
        if (exec != null) return;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "warden-engagement-tick");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(this::tick, 5, 20, TimeUnit.SECONDS);
    }

    public void stop() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }

    private void tick() {
        JDA j = this.jda;
        if (j == null) return;
        try { engagement.closePollsDue(j); } catch (Exception e) { log.log(Level.WARNING, "tick poll close", e); }
        try { engagement.drawGiveawaysDue(j); } catch (Exception e) { log.log(Level.WARNING, "tick draw", e); }
        try { engagement.fireReminders(j); } catch (Exception e) { log.log(Level.WARNING, "tick remind", e); }
        try {
            String mutedRoleId = "";
            try { mutedRoleId = settings.get().gatedRoleId(); } catch (Exception ignored) {}
            moderation.revokeDue(j, config.discordGuildId(), mutedRoleId);
        } catch (Exception e) { log.log(Level.WARNING, "tick revoke", e); }
    }
}
