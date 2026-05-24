package io.warden.moderation;

import io.warden.data.dao.SettingsDao;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Logger;

/**
 * Slash commands: /warn /unwarn /warnings /kick /ban /tempban /timeout /mute /unmute /slowmode /purge.
 * Registered once on ready; the listener intercepts each invocation and dispatches to ModerationService.
 */
public final class ModCommandListener extends ListenerAdapter {

    private final ModerationService moderation;
    private final WarningDao warnings;
    private final SettingsDao settings;
    private final Logger log;
    private final String guildId;

    public ModCommandListener(ModerationService moderation, WarningDao warnings,
                              SettingsDao settings, String guildId, Logger log) {
        this.moderation = moderation;
        this.warnings = warnings;
        this.settings = settings;
        this.guildId = guildId;
        this.log = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) return;
        List<SlashCommandData> cmds = List.of(
                Commands.slash("warn", "Warn a member")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true),
                                new OptionData(OptionType.STRING, "reason", "Reason", false)),
                Commands.slash("warnings", "List a member's warnings")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true)),
                Commands.slash("unwarn", "Clear a warning by ID")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                        .addOptions(new OptionData(OptionType.INTEGER, "id", "Warning ID", true)),
                Commands.slash("kick", "Kick a member")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.KICK_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true),
                                new OptionData(OptionType.STRING, "reason", "Reason", false)),
                Commands.slash("ban", "Ban a member")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true),
                                new OptionData(OptionType.STRING, "reason", "Reason", false),
                                new OptionData(OptionType.INTEGER, "delete_days", "Delete N days of messages (0-7)", false)),
                Commands.slash("tempban", "Ban a member for a duration")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true),
                                new OptionData(OptionType.STRING, "duration", "e.g. 30m 12h 7d", true),
                                new OptionData(OptionType.STRING, "reason", "Reason", false)),
                Commands.slash("timeout", "Timeout a member")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true),
                                new OptionData(OptionType.STRING, "duration", "e.g. 10m 1h", true),
                                new OptionData(OptionType.STRING, "reason", "Reason", false)),
                Commands.slash("mute", "Mute a member (uses gated role if no muted role)")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true),
                                new OptionData(OptionType.STRING, "duration", "e.g. 10m 1h", true),
                                new OptionData(OptionType.STRING, "reason", "Reason", false)),
                Commands.slash("unmute", "Remove the muted role from a member")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                        .addOptions(new OptionData(OptionType.USER, "user", "Member", true)),
                Commands.slash("slowmode", "Set slowmode on the current channel")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL))
                        .addOptions(new OptionData(OptionType.INTEGER, "seconds", "0 to disable, max 21600", true)),
                Commands.slash("purge", "Delete the last N messages in this channel")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                        .addOptions(new OptionData(OptionType.INTEGER, "count", "How many (1-100)", true))
        );
        guild.updateCommands().addCommands(cmds).queue(
                ok -> log.info("Mod commands registered (" + cmds.size() + ")"),
                err -> log.warning("Mod command registration failed: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        String name = event.getName();
        switch (name) {
            case "warn"     -> handleWarn(event);
            case "warnings" -> handleListWarnings(event);
            case "unwarn"   -> handleUnwarn(event);
            case "kick"     -> handleKick(event);
            case "ban"      -> handleBan(event);
            case "tempban"  -> handleTempban(event);
            case "timeout"  -> handleTimeout(event);
            case "mute"     -> handleMute(event);
            case "unmute"   -> handleUnmute(event);
            case "slowmode" -> handleSlowmode(event);
            case "purge"    -> handlePurge(event);
            default -> { /* not ours */ }
        }
    }

    private void handleWarn(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) { e.reply("Guild only.").setEphemeral(true).queue(); return; }
        User user = e.getOption("user", OptionMapping::getAsUser);
        String reason = optString(e, "reason", "");
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        long id = moderation.warn(user.getId(), e.getUser().getId(), reason, 1);
        e.reply("Warned " + user.getAsMention() + " (id " + id + "). Reason: " + escape(reason))
                .setEphemeral(true).queue();
    }

    private void handleListWarnings(SlashCommandInteractionEvent e) {
        User user = e.getOption("user", OptionMapping::getAsUser);
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        try {
            var list = warnings.listFor(user.getId());
            if (list.isEmpty()) {
                e.reply(user.getAsMention() + " has no warnings.").setEphemeral(true).queue();
                return;
            }
            StringBuilder s = new StringBuilder("**Warnings for " + user.getAsMention() + "**\n");
            for (var w : list) {
                s.append("`#").append(w.id()).append("` ");
                if (w.clearedAt() != null) s.append("~~");
                s.append("<t:").append(w.createdAt() / 1000).append(":R>");
                if (w.reason() != null && !w.reason().isBlank()) {
                    s.append(" - ").append(escape(w.reason()));
                }
                if (w.clearedAt() != null) s.append("~~");
                s.append("\n");
                if (s.length() > 1700) break;
            }
            e.reply(s.toString()).setEphemeral(true).queue();
        } catch (Exception ex) {
            e.reply("Lookup failed: " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleUnwarn(SlashCommandInteractionEvent e) {
        Long id = e.getOption("id", OptionMapping::getAsLong);
        if (id == null) { e.reply("ID required.").setEphemeral(true).queue(); return; }
        try {
            warnings.clear(id);
            e.reply("Cleared warning #" + id).setEphemeral(true).queue();
        } catch (Exception ex) {
            e.reply("Clear failed: " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleKick(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        User user = e.getOption("user", OptionMapping::getAsUser);
        String reason = optString(e, "reason", "");
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        moderation.kick(e.getGuild(), user.getId(), e.getUser().getId(), reason);
        e.reply("Kicked " + user.getAsMention()).setEphemeral(true).queue();
    }

    private void handleBan(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        User user = e.getOption("user", OptionMapping::getAsUser);
        String reason = optString(e, "reason", "");
        int days = (int) Math.min(7, Math.max(0, optLong(e, "delete_days", 0)));
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        moderation.ban(e.getGuild(), user.getId(), e.getUser().getId(), reason, days);
        e.reply("Banned " + user.getAsMention()).setEphemeral(true).queue();
    }

    private void handleTempban(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        User user = e.getOption("user", OptionMapping::getAsUser);
        String dur = optString(e, "duration", "");
        String reason = optString(e, "reason", "");
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        int seconds = DurationParser.parse(dur);
        if (seconds <= 0) { e.reply("Bad duration. Use 10m, 1h, 7d.").setEphemeral(true).queue(); return; }
        moderation.tempban(e.getGuild(), user.getId(), e.getUser().getId(), reason, seconds);
        e.reply("Tempbanned " + user.getAsMention() + " for " + dur).setEphemeral(true).queue();
    }

    private void handleTimeout(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        User user = e.getOption("user", OptionMapping::getAsUser);
        String dur = optString(e, "duration", "");
        String reason = optString(e, "reason", "");
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        int seconds = DurationParser.parse(dur);
        if (seconds <= 0) { e.reply("Bad duration.").setEphemeral(true).queue(); return; }
        // Discord caps timeout at 28 days.
        if (seconds > 28 * 86400) seconds = 28 * 86400;
        moderation.timeout(e.getGuild(), user.getId(), e.getUser().getId(), reason, seconds);
        e.reply("Timed out " + user.getAsMention() + " for " + dur).setEphemeral(true).queue();
    }

    private void handleMute(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        User user = e.getOption("user", OptionMapping::getAsUser);
        String dur = optString(e, "duration", "");
        String reason = optString(e, "reason", "");
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        int seconds = DurationParser.parse(dur);
        if (seconds <= 0) { e.reply("Bad duration.").setEphemeral(true).queue(); return; }
        String mutedRoleId = "";
        try { mutedRoleId = settings.get().gatedRoleId(); } catch (Exception ignored) {}
        moderation.mute(e.getGuild(), user.getId(), e.getUser().getId(), reason, seconds, mutedRoleId);
        e.reply("Muted " + user.getAsMention() + " for " + dur).setEphemeral(true).queue();
    }

    private void handleUnmute(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        User user = e.getOption("user", OptionMapping::getAsUser);
        if (user == null) { e.reply("User required.").setEphemeral(true).queue(); return; }
        String mutedRoleId = "";
        try { mutedRoleId = settings.get().gatedRoleId(); } catch (Exception ignored) {}
        moderation.unmute(e.getGuild(), user.getId(), e.getUser().getId(), mutedRoleId);
        e.reply("Unmuted " + user.getAsMention()).setEphemeral(true).queue();
    }

    private void handleSlowmode(SlashCommandInteractionEvent e) {
        Long seconds = e.getOption("seconds", OptionMapping::getAsLong);
        if (seconds == null) { e.reply("seconds required.").setEphemeral(true).queue(); return; }
        int s = (int) Math.min(21600, Math.max(0, seconds));
        if (!(e.getChannel() instanceof GuildChannel ch)) {
            e.reply("Guild channel required.").setEphemeral(true).queue();
            return;
        }
        moderation.setSlowmode(ch, s, e.getUser().getId());
        e.reply("Slowmode set to " + s + "s").setEphemeral(true).queue();
    }

    private void handlePurge(SlashCommandInteractionEvent e) {
        Long count = e.getOption("count", OptionMapping::getAsLong);
        if (count == null) { e.reply("count required.").setEphemeral(true).queue(); return; }
        int n = (int) Math.min(100, Math.max(1, count));
        if (!(e.getChannel() instanceof net.dv8tion.jda.api.entities.channel.middleman.MessageChannel mch)) {
            e.reply("Channel does not support purge.").setEphemeral(true).queue();
            return;
        }
        e.deferReply(true).queue();
        mch.getIterableHistory().takeAsync(n).thenAccept(msgs -> {
            if (msgs.isEmpty()) {
                e.getHook().sendMessage("Nothing to purge.").setEphemeral(true).queue();
                return;
            }
            if (mch instanceof net.dv8tion.jda.api.entities.channel.concrete.TextChannel tc) {
                tc.purgeMessages(msgs);
                e.getHook().sendMessage("Purged " + msgs.size() + " messages.").setEphemeral(true).queue();
            } else {
                for (var m : msgs) m.delete().queue(ok -> {}, err -> {});
                e.getHook().sendMessage("Deleted " + msgs.size() + " messages.").setEphemeral(true).queue();
            }
        }).exceptionally(err -> {
            e.getHook().sendMessage("Purge failed: " + err.getMessage()).setEphemeral(true).queue();
            return null;
        });
    }

    private static String optString(SlashCommandInteractionEvent e, String key, String fallback) {
        var opt = e.getOption(key);
        return opt == null ? fallback : opt.getAsString();
    }

    private static long optLong(SlashCommandInteractionEvent e, String key, long fallback) {
        var opt = e.getOption(key);
        return opt == null ? fallback : opt.getAsLong();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("@", "@​").replace("`", "'");
    }
}
