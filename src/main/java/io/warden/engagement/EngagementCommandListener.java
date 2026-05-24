package io.warden.engagement;

import io.warden.moderation.DurationParser;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class EngagementCommandListener extends ListenerAdapter {

    private final EngagementService service;
    private final String guildId;
    private final Logger log;

    public EngagementCommandListener(EngagementService service, String guildId, Logger log) {
        this.service = service;
        this.guildId = guildId;
        this.log = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) return;
        List<SlashCommandData> cmds = List.of(
                Commands.slash("poll", "Start a poll")
                        .addOptions(
                                new OptionData(OptionType.STRING, "question", "Question", true),
                                new OptionData(OptionType.STRING, "options", "Comma-separated options (2-10)", true),
                                new OptionData(OptionType.STRING, "duration", "e.g. 1h, 24h, 7d", false),
                                new OptionData(OptionType.BOOLEAN, "anonymous", "Hide voter identities", false),
                                new OptionData(OptionType.BOOLEAN, "multi", "Allow multiple choices", false)),
                Commands.slash("giveaway", "Start a giveaway")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addOptions(
                                new OptionData(OptionType.STRING, "prize", "Prize", true),
                                new OptionData(OptionType.STRING, "duration", "e.g. 1h, 24h, 7d", true),
                                new OptionData(OptionType.INTEGER, "winners", "How many winners (default 1)", false),
                                new OptionData(OptionType.ROLE, "role", "Required role", false),
                                new OptionData(OptionType.STRING, "description", "Description", false)),
                Commands.slash("reroll", "Re-draw a finished giveaway")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addOptions(new OptionData(OptionType.INTEGER, "id", "Giveaway ID", true)),
                Commands.slash("remind", "Set a reminder")
                        .addOptions(
                                new OptionData(OptionType.STRING, "when", "e.g. 10m, 1h, 7d", true),
                                new OptionData(OptionType.STRING, "message", "What to remind you about", true),
                                new OptionData(OptionType.BOOLEAN, "here", "Post in this channel instead of DM", false)),
                Commands.slash("reminders", "List your active reminders")
        );
        guild.updateCommands().addCommands(cmds).queue(
                ok -> log.info("Engagement commands registered (" + cmds.size() + ")"),
                err -> log.warning("Engagement command registration failed: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "poll" -> handlePoll(event);
            case "giveaway" -> handleGiveaway(event);
            case "reroll" -> handleReroll(event);
            case "remind" -> handleRemind(event);
            case "reminders" -> handleListReminders(event);
            default -> {}
        }
    }

    private void handlePoll(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        String q = optString(e, "question", "");
        String rawOptions = optString(e, "options", "");
        if (q.isBlank() || rawOptions.isBlank()) {
            e.reply("Question and options required.").setEphemeral(true).queue();
            return;
        }
        List<String> options = new ArrayList<>();
        for (String s : rawOptions.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) options.add(t);
        }
        if (options.size() < 2) {
            e.reply("Need at least 2 options.").setEphemeral(true).queue();
            return;
        }
        if (options.size() > 10) {
            e.reply("Max 10 options.").setEphemeral(true).queue();
            return;
        }
        Long endsAt = null;
        String dur = optString(e, "duration", "");
        if (!dur.isBlank()) {
            int s = DurationParser.parse(dur);
            if (s <= 0) {
                e.reply("Bad duration.").setEphemeral(true).queue();
                return;
            }
            endsAt = System.currentTimeMillis() + s * 1000L;
        }
        boolean anonymous = e.getOption("anonymous", false, OptionMapping::getAsBoolean);
        boolean multi = e.getOption("multi", false, OptionMapping::getAsBoolean);
        long id = service.createPoll(e.getJDA(), e.getChannel().getId(), e.getUser().getId(),
                q, options, anonymous, multi, endsAt);
        if (id < 0) {
            e.reply("Failed to create poll.").setEphemeral(true).queue();
            return;
        }
        e.reply("Poll #" + id + " created.").setEphemeral(true).queue();
    }

    private void handleGiveaway(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) return;
        String prize = optString(e, "prize", "");
        String dur = optString(e, "duration", "");
        if (prize.isBlank() || dur.isBlank()) {
            e.reply("Prize and duration required.").setEphemeral(true).queue();
            return;
        }
        int seconds = DurationParser.parse(dur);
        if (seconds <= 0) {
            e.reply("Bad duration.").setEphemeral(true).queue();
            return;
        }
        int winners = (int) Math.max(1, e.getOption("winners", 1L, OptionMapping::getAsLong));
        var roleOpt = e.getOption("role");
        String requiredRole = roleOpt == null ? "" : roleOpt.getAsRole().getId();
        String desc = optString(e, "description", "");
        long endsAt = System.currentTimeMillis() + seconds * 1000L;
        long id = service.createGiveaway(e.getJDA(), e.getChannel().getId(), e.getUser().getId(),
                prize, desc, winners, requiredRole, endsAt);
        if (id < 0) {
            e.reply("Failed to create giveaway.").setEphemeral(true).queue();
            return;
        }
        e.reply("Giveaway #" + id + " started.").setEphemeral(true).queue();
    }

    private void handleReroll(SlashCommandInteractionEvent e) {
        Long id = e.getOption("id", OptionMapping::getAsLong);
        if (id == null) { e.reply("id required.").setEphemeral(true).queue(); return; }
        var winners = service.drawWinners(e.getJDA(), id);
        if (winners.isEmpty()) {
            e.reply("No eligible entries to reroll.").setEphemeral(true).queue();
        } else {
            e.reply("Rerolled (" + winners.size() + " winner(s)).").setEphemeral(true).queue();
        }
    }

    private void handleRemind(SlashCommandInteractionEvent e) {
        String when = optString(e, "when", "");
        String msg = optString(e, "message", "");
        boolean here = e.getOption("here", false, OptionMapping::getAsBoolean);
        if (when.isBlank() || msg.isBlank()) {
            e.reply("when and message required.").setEphemeral(true).queue();
            return;
        }
        int s = DurationParser.parse(when);
        if (s <= 0) { e.reply("Bad duration.").setEphemeral(true).queue(); return; }
        long firesAt = System.currentTimeMillis() + s * 1000L;
        try {
            long id = service.reminders().create(e.getUser().getId(),
                    here ? e.getChannel().getId() : "", msg, firesAt);
            e.reply("Reminder #" + id + " set for <t:" + (firesAt / 1000) + ":R>.").setEphemeral(true).queue();
        } catch (Exception ex) {
            e.reply("Failed: " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleListReminders(SlashCommandInteractionEvent e) {
        try {
            var list = service.reminders().listFor(e.getUser().getId());
            if (list.isEmpty()) {
                e.reply("No active reminders.").setEphemeral(true).queue();
                return;
            }
            StringBuilder s = new StringBuilder("**Your reminders**\n");
            for (var r : list) {
                s.append("`#").append(r.id()).append("` ")
                        .append("<t:").append(r.firesAt() / 1000).append(":R> - ")
                        .append(r.message()).append("\n");
                if (s.length() > 1700) break;
            }
            e.reply(s.toString()).setEphemeral(true).queue();
        } catch (Exception ex) {
            e.reply("Failed: " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private static String optString(SlashCommandInteractionEvent e, String key, String fallback) {
        var opt = e.getOption(key);
        return opt == null ? fallback : opt.getAsString();
    }
}
