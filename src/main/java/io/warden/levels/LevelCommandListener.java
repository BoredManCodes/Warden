package io.warden.levels;

import io.warden.config.WardenConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

/**
 * Adds /rank and /leaderboard slash commands. Top-N pulled from the level_users
 * table; ranks are computed by counting users with higher XP.
 */
public final class LevelCommandListener extends ListenerAdapter {

    private final LevelService levels;
    private final WardenConfig config;
    private final Logger log;

    public LevelCommandListener(LevelService levels, WardenConfig config, Logger log) {
        this.levels = levels;
        this.config = config;
        this.log = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(config.discordGuildId());
        if (guild == null) return;
        guild.upsertCommand(Commands.slash("rank", "Show your XP / level")
                        .addOptions(new OptionData(OptionType.USER, "user", "Member (defaults to you)", false)))
                .queue(ok -> {}, err -> log.warning("/rank registration: " + err.getMessage()));
        guild.upsertCommand(Commands.slash("leaderboard", "Top members by XP")
                        .addOptions(new OptionData(OptionType.INTEGER, "limit", "How many (max 25)", false)))
                .queue(ok -> {}, err -> log.warning("/leaderboard registration: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "rank" -> handleRank(event);
            case "leaderboard" -> handleLeaderboard(event);
            default -> {}
        }
    }

    private void handleRank(SlashCommandInteractionEvent e) {
        User target = e.getOption("user", e.getUser(), OptionMapping::getAsUser);
        try {
            var record = levels.userDao().find(target.getId()).orElse(null);
            if (record == null || record.xp() == 0) {
                e.reply(target.getEffectiveName() + " has no XP yet.").setEphemeral(true).queue();
                return;
            }
            int rank = levels.userDao().rank(target.getId());
            int level = record.level();
            long nextLevelXp = LevelConfig.xpForLevel(level + 1);
            long thisLevelXp = LevelConfig.xpForLevel(level);
            long into = record.xp() - thisLevelXp;
            long span = Math.max(1, nextLevelXp - thisLevelXp);
            int pct = (int) Math.min(100, into * 100 / span);
            String bar = bar(pct);
            e.reply("**" + target.getEffectiveName() + "** - Level **" + level + "**, Rank **#" + rank + "**\n"
                    + "XP: " + record.xp() + " (" + into + " / " + span + ")\n"
                    + bar + " " + pct + "%").queue();
        } catch (Exception ex) {
            e.reply("Lookup failed: " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleLeaderboard(SlashCommandInteractionEvent e) {
        int limit = (int) Math.min(25, Math.max(1, e.getOption("limit", 10L, OptionMapping::getAsLong)));
        try {
            var top = levels.userDao().top(limit);
            if (top.isEmpty()) {
                e.reply("No leaderboard data yet.").setEphemeral(true).queue();
                return;
            }
            StringBuilder s = new StringBuilder("**Leaderboard**\n");
            int rank = 1;
            for (var u : top) {
                s.append("`#").append(rank).append("` <@").append(u.discordId()).append(">")
                        .append(" - level **").append(u.level()).append("** (").append(u.xp()).append(" XP)\n");
                rank++;
                if (s.length() > 1800) break;
            }
            e.reply(s.toString()).setAllowedMentions(java.util.List.of()).queue();
        } catch (Exception ex) {
            e.reply("Lookup failed: " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private static String bar(int pct) {
        int filled = pct / 5;
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 20; i++) b.append(i < filled ? "#" : "-");
        b.append("]");
        return b.toString();
    }
}
