package io.warden.discord;

import io.warden.audit.AuditService;
import io.warden.config.WardenConfig;
import io.warden.data.dao.SettingsDao;
import io.warden.data.dao.UserDao;
import io.warden.onboarding.Template;
import io.warden.onboarding.model.FlowConfig;
import io.warden.onboarding.model.Settings;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the gate + DM/channel delivery for a single guild member.
 *
 * Used by:
 *  - {@link io.warden.discord.listeners.GuildJoinListener} on real member joins.
 *  - {@code WardenPlugin.doReonboard} and {@code DashMembersHandlers.reonboard}
 *    to replay onboarding for an already-present member after a state reset.
 *
 * Note: link codes are no longer pre-issued here. The web /onboard page mints
 * a code on demand; the user DMs it to the bot to pair. This means delivery
 * messages just point the user at /onboard (and optionally include a Start in
 * Discord button) without baking in any per-user code.
 */
public final class OnboardingDelivery {

    /** Tells {@link #runFor} which entry point invoked it; only affects audit signal. */
    public enum Trigger { JOIN, REPLAY }

    /** Result of a replay attempt against an already-present member id. */
    public enum ReplayResult {
        OK,
        DISCORD_NOT_READY,
        GUILD_NOT_FOUND,
        MEMBER_NOT_IN_GUILD,
        LOOKUP_FAILED
    }

    private final WardenConfig config;
    private final SettingsDao settingsDao;
    private final UserDao userDao;
    private final AuditService audit;
    private final Logger log;
    private final AtomicReference<JDA> jdaRef = new AtomicReference<>();

    public OnboardingDelivery(WardenConfig config, SettingsDao settingsDao, UserDao userDao,
                              AuditService audit, Logger log) {
        this.config = config;
        this.settingsDao = settingsDao;
        this.userDao = userDao;
        this.audit = audit;
        this.log = log;
    }

    /** DiscordService sets this once JDA is built. */
    public void attachJda(JDA jda) {
        this.jdaRef.set(jda);
    }

    /**
     * Resolve the member from JDA and run the delivery pipeline as a replay.
     * Returns a coarse status so the caller can produce a useful message.
     */
    public ReplayResult replayFor(String discordId) {
        JDA jda = jdaRef.get();
        if (jda == null) return ReplayResult.DISCORD_NOT_READY;
        Guild guild = jda.getGuildById(config.discordGuildId());
        if (guild == null) return ReplayResult.GUILD_NOT_FOUND;
        try {
            Member member = guild.retrieveMemberById(discordId).complete();
            if (member == null) return ReplayResult.MEMBER_NOT_IN_GUILD;
            runFor(guild, member, Trigger.REPLAY);
            return ReplayResult.OK;
        } catch (net.dv8tion.jda.api.exceptions.ErrorResponseException notFound) {
            return ReplayResult.MEMBER_NOT_IN_GUILD;
        } catch (Exception e) {
            log.log(Level.WARNING, "replay lookup failed for " + discordId + ": " + e.getMessage());
            return ReplayResult.LOOKUP_FAILED;
        }
    }

    /**
     * Core pipeline. Same shape as the original join handler after bot/guild
     * filters, with audit signals branching on the trigger.
     */
    public void runFor(Guild guild, Member member, Trigger trigger) {
        User user = member.getUser();
        if (user.isBot()) return;
        if (!guild.getId().equals(config.discordGuildId())) return;

        String discordId = user.getId();
        String username = user.getName();

        try {
            userDao.upsert(discordId, username);
        } catch (Exception e) {
            log.log(Level.WARNING, "users upsert failed for " + discordId, e);
        }

        Settings settings;
        try {
            settings = settingsDao.get();
        } catch (Exception e) {
            log.log(Level.SEVERE, "settings load failed; skipping onboarding for " + discordId, e);
            return;
        }
        FlowConfig flow = settings.flow();

        if (flow.gatingEnabled() && !settings.gatedRoleId().isBlank()) {
            assignGatedRole(guild, member, settings.gatedRoleId());
        } else if (flow.gatingEnabled()) {
            log.warning("gating_enabled=true but no gated_role_id configured; cannot gate " + discordId);
        }

        if (!flow.anyDeliveryEnabled()) {
            String action = trigger == Trigger.REPLAY ? "replay_no_delivery" : "join_recorded_no_delivery";
            audit.write(AuditService.ACTOR_BOT, action, discordId,
                    Map.of("note", "delivery off in flow config - admin must enable to start onboarding"));
            return;
        }
        if (!flow.anyEntryEnabled()) {
            String action = trigger == Trigger.REPLAY ? "replay_no_entry_method" : "join_no_entry_method";
            audit.write(AuditService.ACTOR_BOT, action, discordId,
                    Map.of("note", "no entry method enabled; user would have nowhere to go"));
            return;
        }

        Map<String, String> vars = templateVars(username, guild, flow);
        String content = Template.render(flow.deliveryMessageTemplate(), vars);
        if (content.isBlank()) {
            content = "Welcome to " + guild.getName() + "! Onboarding is configured but the message template is blank.";
        }
        List<ActionRow> components = buildComponents(discordId, flow);

        if (trigger == Trigger.REPLAY) {
            audit.write(AuditService.ACTOR_BOT, "reonboard_replayed", discordId,
                    Map.of("via", flow.deliveryViaDm() && flow.deliveryViaChannel()
                            ? "dm+channel"
                            : flow.deliveryViaDm() ? "dm" : "channel"));
        }

        if (flow.deliveryViaDm()) {
            sendDm(user, content, components);
        }
        if (flow.deliveryViaChannel() && !flow.deliveryChannelId().isBlank()) {
            sendChannel(guild, member, flow.deliveryChannelId(), content, components);
        } else if (flow.deliveryViaChannel()) {
            log.warning("delivery_via_channel=true but delivery_channel_id is blank; cannot post for " + discordId);
        }
    }

    /* ---------- internals (moved from GuildJoinListener) ---------- */

    private void assignGatedRole(Guild guild, Member member, String gatedRoleId) {
        var role = guild.getRoleById(gatedRoleId);
        if (role == null) {
            log.warning("Configured gated_role_id " + gatedRoleId + " not found in guild");
            audit.write(AuditService.ACTOR_BOT, "gated_role_missing", member.getId(),
                    Map.of("roleId", gatedRoleId));
            return;
        }
        // If the member already has the role (replay case), skip the API call.
        for (var existing : member.getRoles()) {
            if (existing.getId().equals(gatedRoleId)) return;
        }
        guild.addRoleToMember(member, role)
                .reason("Warden onboarding: gated until approved")
                .queue(
                        success -> audit.write(AuditService.ACTOR_BOT, "gated_role_assigned", member.getId(),
                                Map.of("roleId", gatedRoleId)),
                        err -> {
                            log.log(Level.WARNING, "addRole failed for " + member.getId() + ": " + err.getMessage());
                            audit.write(AuditService.ACTOR_BOT, "gated_role_failed", member.getId(),
                                    Map.of("error", err.getMessage(), "roleId", gatedRoleId));
                        });
    }

    private Map<String, String> templateVars(String username, Guild guild, FlowConfig flow) {
        Map<String, String> vars = new HashMap<>();
        vars.put("username", username);
        vars.put("guild_name", guild.getName());
        vars.put("web_url", config.webPublicUrl() + "/onboard");
        vars.put("entry_options", buildEntryOptionsText(flow));
        // Kept for backwards compatibility with admin templates that still
        // reference these placeholders; they now render to empty strings.
        vars.put("code", "");
        vars.put("web_url_with_code", config.webPublicUrl() + "/onboard");
        vars.put("code_note", "");
        return vars;
    }

    private String buildEntryOptionsText(FlowConfig flow) {
        List<String> bullets = new ArrayList<>();
        if (flow.entryViaDiscordButton()) {
            bullets.add("- Click **Start in Discord** below to answer right here.");
        }
        if (flow.entryViaWebOauth() || flow.entryViaWebCode()) {
            bullets.add("- Or open " + config.webPublicUrl() + "/onboard to get started on the web.");
        }
        return String.join("\n", bullets);
    }

    private List<ActionRow> buildComponents(String discordId, FlowConfig flow) {
        List<ItemComponent> buttons = new ArrayList<>();
        if (flow.entryViaDiscordButton()) {
            buttons.add(Button.primary("onboard:start:" + discordId, "Start in Discord"));
        }
        if (flow.entryViaWebOauth() || flow.entryViaWebCode()) {
            buttons.add(Button.link(config.webPublicUrl() + "/onboard", "Open on web"));
        }
        return buttons.isEmpty() ? List.of() : List.of(ActionRow.of(buttons));
    }

    private void sendDm(User user, String content, List<ActionRow> components) {
        user.openPrivateChannel().queue(
                channel -> channel.sendMessage(content)
                        .setComponents(components)
                        .queue(
                                msg -> audit.write(AuditService.ACTOR_BOT, "onboarding_dm_sent", user.getId(),
                                        Map.of("messageId", msg.getId())),
                                err -> {
                                    log.warning("Failed to send onboarding DM to " + user.getId() + ": " + err.getMessage());
                                    audit.write(AuditService.ACTOR_BOT, "onboarding_dm_failed", user.getId(),
                                            Map.of("error", err.getMessage()));
                                }),
                err -> {
                    log.warning("Could not open DM channel for " + user.getId() + ": " + err.getMessage());
                    audit.write(AuditService.ACTOR_BOT, "onboarding_dm_failed", user.getId(),
                            Map.of("error", "open_dm:" + err.getMessage()));
                });
    }

    private void sendChannel(Guild guild, Member member, String channelId, String content,
                             List<ActionRow> components) {
        var channel = guild.getChannelById(TextChannel.class, channelId);
        if (channel == null) {
            log.warning("Configured delivery_channel_id " + channelId + " is not a text channel in guild");
            audit.write(AuditService.ACTOR_BOT, "onboarding_channel_missing", member.getId(),
                    Map.of("channelId", channelId));
            return;
        }
        String mentioned = member.getAsMention() + " " + content;
        channel.sendMessage(mentioned).setComponents(components).queue(
                msg -> audit.write(AuditService.ACTOR_BOT, "onboarding_channel_sent", member.getId(),
                        Map.of("channelId", channelId, "messageId", msg.getId())),
                err -> {
                    log.warning("Failed to post onboarding message in " + channelId + ": " + err.getMessage());
                    audit.write(AuditService.ACTOR_BOT, "onboarding_channel_failed", member.getId(),
                            Map.of("channelId", channelId, "error", err.getMessage()));
                });
    }
}
