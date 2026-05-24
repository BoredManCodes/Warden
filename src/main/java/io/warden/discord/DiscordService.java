package io.warden.discord;

import io.warden.WardenPlugin;
import io.warden.Services;
import io.warden.analytics.DiscordMemberEventAnalyticsListener;
import io.warden.analytics.DiscordMessageAnalyticsListener;
import io.warden.analytics.DiscordVoiceAnalyticsListener;
import io.warden.analytics.InviteTracker;
import io.warden.autoresponder.AutoresponderListener;
import io.warden.discord.listeners.ButtonListener;
import io.warden.discord.listeners.GuildJoinListener;
import io.warden.discord.listeners.ModButtonListener;
import io.warden.discord.listeners.ModalListener;
import io.warden.discord.listeners.OnboardDmListener;
import io.warden.discord.listeners.SelectListener;
import io.warden.engagement.EngagementCommandListener;
import io.warden.engagement.EngagementListener;
import io.warden.feedback.FeedbackCommandListener;
import io.warden.feedback.FeedbackListener;
import io.warden.levels.LevelCommandListener;
import io.warden.levels.LevelListener;
import io.warden.discord.listeners.DebugCommandListener;
import io.warden.moderation.AutoModListener;
import io.warden.moderation.ModCommandListener;
import io.warden.moderation.RaidProtectionListener;
import io.warden.reactionroles.ReactionRoleListener;
import io.warden.tickets.TicketCommandListener;
import io.warden.tickets.TicketInboundListener;
import io.warden.tickets.TicketListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the JDA client lifecycle and registers all listeners.
 */
public final class DiscordService {

    private final WardenPlugin plugin;
    private final Services services;
    private final Logger log;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final OnboardingDelivery delivery;

    private JDA jda;

    public DiscordService(WardenPlugin plugin, Services services) {
        this.plugin = plugin;
        this.services = services;
        this.log = plugin.getLogger();
        this.delivery = new OnboardingDelivery(
                services.config,
                services.settingsDao,
                services.userDao,
                services.audit,
                log);
        services.attachDelivery(delivery);
    }

    /** Pipeline used by the join listener and the reonboard paths. */
    public OnboardingDelivery delivery() {
        return delivery;
    }

    public void start() {
        if (!services.config.discordConfigured()) {
            log.warning("Discord bot token / guild_id not configured - Discord bot not started. Fill plugins/Warden/config.yml.");
            return;
        }

        // Refuse to boot if DiscordSRV is using the same bot token. Two plugins
        // sharing one token cannot coexist - Discord allows one gateway session
        // per token, and slash-command registration is per-application.
        if (services.discordSrv() != null
                && services.discordSrv().tokenMatches(services.config.discordBotToken())) {
            log.severe("==============================================================");
            log.severe("Warden and DiscordSRV are configured with the SAME bot token.");
            log.severe("Discord allows only one gateway session per token; the two");
            log.severe("plugins would fight for the connection and overwrite each");
            log.severe("other's slash commands.");
            log.severe("");
            log.severe("Warden's Discord client will NOT start. To fix:");
            log.severe("  1. Create a second bot application at");
            log.severe("     https://discord.com/developers/applications");
            log.severe("  2. Invite the new bot to your server with the same");
            log.severe("     permissions (Manage Roles, Send Messages, etc.).");
            log.severe("  3. Put the new bot's token in plugins/Warden/config.yml");
            log.severe("     under discord.bot_token (or WARDEN_DISCORD_BOT_TOKEN).");
            log.severe("");
            log.severe("DiscordSRV will continue running normally. Warden's web");
            log.severe("dashboard and Minecraft-side features are unaffected.");
            log.severe("==============================================================");
            return;
        }

        log.info("Discord: building JDA client...");
        String guildId = services.config.discordGuildId();

        // Listeners are gated by the module flags from config.yml. Disabled
        // modules contribute nothing to JDA so their events never fire even
        // if the underlying DAOs/services are still constructed.
        var mods = services.config.modules();
        List<Object> listeners = new ArrayList<>();
        listeners.add(new ReadyListener());
        // Core onboarding pipeline - always on.
        listeners.add(new GuildJoinListener(delivery));
        listeners.add(new ButtonListener(services.onboarding, services.questionDao, log));
        listeners.add(new ModalListener(services.onboarding, log));
        listeners.add(new SelectListener(services.onboarding, log));
        listeners.add(new OnboardDmListener(services.config, services.linkCodes, services.audit, log));
        listeners.add(new ModButtonListener(services.config, services.settingsDao,
                services.decisionService(), log));
        // Analytics - always on (powers Stats / Members / Invites tabs).
        listeners.add(new DiscordMessageAnalyticsListener(services.analytics, guildId));
        listeners.add(new DiscordMemberEventAnalyticsListener(services.analytics, guildId));
        listeners.add(new DiscordVoiceAnalyticsListener(services.analytics, guildId));
        listeners.add(new InviteTracker(services.inviteDao, guildId, services.bgExecutor, log));
        // Debug command - always registered; access controlled by Warden role check inside the listener.
        listeners.add(new DebugCommandListener(plugin, services, guildId, log));
        if (mods.moderation()) {
            listeners.add(new AutoModListener(services.autoMod, services.moderation,
                    services.settingsDao, services.audit, log));
            listeners.add(new RaidProtectionListener(services.raidProtectionDao, services.moderation,
                    services.audit, log));
            listeners.add(new ModCommandListener(services.moderation, services.warningDao,
                    services.settingsDao, guildId, log));
        }
        if (mods.levels()) {
            listeners.add(new LevelListener(services.levelService, log));
            listeners.add(new LevelCommandListener(services.levelService, services.config, log));
        }
        if (mods.reactionRoles()) {
            listeners.add(new ReactionRoleListener(services.reactionRoles, log));
        }
        if (mods.engagement()) {
            listeners.add(new EngagementListener(services.engagement, log));
            listeners.add(new EngagementCommandListener(services.engagement, guildId, log));
        }
        if (mods.tickets()) {
            listeners.add(new TicketListener(services.tickets, log));
            listeners.add(new TicketCommandListener(services.tickets, guildId, log));
            listeners.add(new TicketInboundListener(services.tickets, log));
        }
        if (mods.feedback()) {
            listeners.add(new FeedbackListener(services.feedback, log));
            listeners.add(new FeedbackCommandListener(services.feedback, guildId, log));
        }
        if (mods.autoresponders()) {
            listeners.add(new AutoresponderListener(services.autoresponders, log));
        }

        this.jda = JDABuilder.createDefault(
                        services.config.discordBotToken(),
                        EnumSet.of(
                                GatewayIntent.GUILD_MEMBERS,
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT,
                                GatewayIntent.DIRECT_MESSAGES,
                                GatewayIntent.GUILD_MODERATION,
                                GatewayIntent.GUILD_VOICE_STATES,
                                GatewayIntent.GUILD_INVITES,
                                // Needed to populate Guild.getEmojis() so the dashboard
                                // server-emoji picker (reaction roles, polls, tickets) has data.
                                GatewayIntent.GUILD_EMOJIS_AND_STICKERS
                        ))
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableCache(CacheFlag.ROLE_TAGS, CacheFlag.VOICE_STATE, CacheFlag.EMOJI)
                // Suppress the boot-time WARNs about auto-disabled cache flags; we don't need these.
                .disableCache(
                        CacheFlag.STICKER,
                        CacheFlag.SCHEDULED_EVENTS,
                        CacheFlag.ONLINE_STATUS,
                        CacheFlag.ACTIVITY,
                        CacheFlag.CLIENT_STATUS)
                .addEventListeners(listeners.toArray())
                .build();
        delivery.attachJda(jda);
        if (mods.engagement()) {
            services.engagementScheduler.start(jda);
        }
    }

    public void stop() {
        if (jda != null) {
            log.info("Discord: shutting down JDA...");
            try {
                jda.shutdown();
            } catch (Exception e) {
                log.log(Level.WARNING, "JDA shutdown raised", e);
            }
            jda = null;
        }
        ready.set(false);
    }

    public boolean isReady() {
        return ready.get();
    }

    public JDA jda() { return jda; }

    private final class ReadyListener extends ListenerAdapter {
        @Override
        public void onReady(@NotNull ReadyEvent event) {
            ready.set(true);
            log.info("Discord: ready as @" + event.getJDA().getSelfUser().getName() + " in " + event.getGuildTotalCount() + " guild(s)");
        }
    }
}
