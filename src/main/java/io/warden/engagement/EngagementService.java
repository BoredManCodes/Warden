package io.warden.engagement;

import io.warden.audit.AuditService;
import io.warden.discord.WardenEmbeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns poll + giveaway message rendering, vote/entry processing, and the
 * scheduled close/draw of finished items. Everything is button-driven so the
 * usual Discord rate-limit dance for reactions doesn't apply.
 */
public final class EngagementService {

    public static final String POLL_BUTTON_PREFIX = "poll:";
    public static final String GIVEAWAY_BUTTON_PREFIX = "give:";

    private final PollDao polls;
    private final GiveawayDao giveaways;
    private final ReminderDao reminders;
    private final AuditService audit;
    private final Logger log;

    public EngagementService(PollDao polls, GiveawayDao giveaways, ReminderDao reminders,
                             AuditService audit, Logger log) {
        this.polls = polls;
        this.giveaways = giveaways;
        this.reminders = reminders;
        this.audit = audit;
        this.log = log;
    }

    public PollDao polls() { return polls; }
    public GiveawayDao giveaways() { return giveaways; }
    public ReminderDao reminders() { return reminders; }

    /* ============================== Polls ============================== */

    public long createPoll(JDA jda, String channelId, String creatorId, String question,
                           List<String> options, boolean anonymous, boolean multiChoice,
                           Long endsAt) {
        try {
            long id = polls.create(channelId, creatorId, question, options, anonymous, multiChoice, endsAt);
            if (id < 0) return -1;
            postPoll(jda, id);
            return id;
        } catch (Exception e) {
            log.log(Level.WARNING, "createPoll failed", e);
            return -1;
        }
    }

    public void postPoll(JDA jda, long pollId) {
        try {
            Poll p = polls.find(pollId).orElse(null);
            if (p == null) return;
            TextChannel ch = jda.getTextChannelById(p.channelId());
            if (ch == null) return;
            EmbedBuilder eb = renderPollEmbed(p);
            List<LayoutComponent> rows = pollButtons(p);
            if (p.messageId() != null && !p.messageId().isBlank()) {
                ch.editMessageEmbedsById(p.messageId(), WardenEmbeds.brand(eb).build()).setComponents(rows)
                        .queue(ok -> {}, err -> {});
            } else {
                var sent = ch.sendMessageEmbeds(WardenEmbeds.brand(eb).build()).setComponents(rows).complete();
                polls.setMessageId(pollId, sent.getId());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "postPoll failed", e);
        }
    }

    public String onPollClick(JDA jda, String userId, long pollId, int optionIndex) {
        try {
            Poll p = polls.find(pollId).orElse(null);
            if (p == null) return "Poll not found.";
            if (!p.open(System.currentTimeMillis())) return "Poll is closed.";
            if (optionIndex < 0 || optionIndex >= p.options().size()) return "Invalid option.";
            boolean had = polls.hasVoted(pollId, userId, optionIndex);
            if (had && p.multiChoice()) {
                polls.removeVote(pollId, userId, optionIndex);
            } else {
                polls.recordVote(pollId, userId, optionIndex, p.multiChoice());
            }
            postPoll(jda, pollId);
            return had && p.multiChoice() ? "Vote removed." : "Vote recorded.";
        } catch (Exception e) {
            log.log(Level.WARNING, "pollClick failed", e);
            return "Failed to record vote.";
        }
    }

    public void closePollsDue(JDA jda) {
        try {
            for (Poll p : polls.dueForClose(System.currentTimeMillis())) {
                polls.close(p.id());
                postPoll(jda, p.id());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "closePollsDue failed", e);
        }
    }

    private EmbedBuilder renderPollEmbed(Poll p) throws Exception {
        Map<Integer, Integer> tally = polls.tally(p.id());
        int total = tally.values().stream().mapToInt(Integer::intValue).sum();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("Poll: " + p.question());
        eb.setColor(new Color(0x5865F2));
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < p.options().size(); i++) {
            int votes = tally.getOrDefault(i, 0);
            int pct = total == 0 ? 0 : (votes * 100) / total;
            body.append("**").append(i + 1).append(".** ").append(p.options().get(i))
                    .append(" - ").append(votes).append(" vote").append(votes == 1 ? "" : "s")
                    .append(" (").append(pct).append("%)\n");
            int filled = pct / 5;
            body.append("`");
            for (int b = 0; b < 20; b++) body.append(b < filled ? "#" : " ");
            body.append("`\n\n");
        }
        eb.setDescription(body.toString());
        StringBuilder footer = new StringBuilder("Total votes: " + total);
        if (p.multiChoice()) footer.append(" - multi choice");
        if (p.anonymous()) footer.append(" - anonymous");
        if (p.endsAt() != null) {
            footer.append(" - ");
            if (p.open(System.currentTimeMillis())) {
                footer.append("ends <t:").append(p.endsAt() / 1000).append(":R>");
            } else footer.append("closed");
        }
        eb.setFooter(footer.toString());
        return eb;
    }

    private List<LayoutComponent> pollButtons(Poll p) {
        if (!p.open(System.currentTimeMillis())) return List.of();
        List<LayoutComponent> rows = new ArrayList<>();
        List<Button> cur = new ArrayList<>();
        for (int i = 0; i < p.options().size(); i++) {
            cur.add(pollOptionButton(p.id(), i, p.options().get(i)));
            if (cur.size() == 5) {
                rows.add(ActionRow.of(cur));
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) rows.add(ActionRow.of(cur));
        return rows;
    }

    /**
     * Render a single vote button. If the option text starts with a custom-emoji
     * mention ({@code <:name:id>} or {@code <a:name:id>}) we peel it off and
     * attach it via {@link Button#withEmoji} so the icon renders on the button
     * instead of showing the literal mention text. The remaining label falls
     * back to the option's 1-based index when the mention was the entire string.
     */
    private static Button pollOptionButton(long pollId, int index, String optionText) {
        String customId = POLL_BUTTON_PREFIX + pollId + ":" + index;
        String text = optionText == null ? "" : optionText;
        java.util.regex.Matcher m = CUSTOM_EMOJI_PREFIX.matcher(text);
        if (m.find()) {
            boolean animated = "a".equals(m.group(1));
            String name = m.group(2);
            long emojiId;
            try { emojiId = Long.parseLong(m.group(3)); }
            catch (NumberFormatException e) { return Button.of(ButtonStyle.SECONDARY, customId, truncate(text, 80)); }
            String rest = text.substring(m.end()).stripLeading();
            String label = rest.isEmpty() ? String.valueOf(index + 1) : truncate(rest, 80);
            return Button.of(ButtonStyle.SECONDARY, customId, label)
                    .withEmoji(Emoji.fromCustom(name, emojiId, animated));
        }
        return Button.of(ButtonStyle.SECONDARY, customId, truncate(text, 80));
    }

    private static final java.util.regex.Pattern CUSTOM_EMOJI_PREFIX =
            java.util.regex.Pattern.compile("^<(a?):([A-Za-z0-9_~]{1,64}):(\\d{15,25})>");

    /* ============================== Giveaways ============================== */

    public long createGiveaway(JDA jda, String channelId, String creatorId, String prize,
                               String description, int winners, String requiredRole, long endsAt) {
        try {
            long id = giveaways.create(channelId, creatorId, prize, description, winners, requiredRole, endsAt);
            if (id < 0) return -1;
            postGiveaway(jda, id);
            return id;
        } catch (Exception e) {
            log.log(Level.WARNING, "createGiveaway failed", e);
            return -1;
        }
    }

    public void postGiveaway(JDA jda, long id) {
        try {
            Giveaway g = giveaways.find(id).orElse(null);
            if (g == null) return;
            TextChannel ch = jda.getTextChannelById(g.channelId());
            if (ch == null) return;
            int entries = giveaways.entryCount(id);
            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("Giveaway: " + g.prize())
                    .setColor(new Color(0xE67E22));
            StringBuilder body = new StringBuilder();
            if (g.description() != null && !g.description().isBlank()) {
                body.append(g.description()).append("\n\n");
            }
            body.append("Winners: **").append(g.winners()).append("**\n");
            body.append("Entries: **").append(entries).append("**\n");
            if (g.requiredRole() != null && !g.requiredRole().isBlank()) {
                body.append("Required role: <@&").append(g.requiredRole()).append(">\n");
            }
            if (g.open(System.currentTimeMillis())) {
                body.append("Ends <t:").append(g.endsAt() / 1000).append(":R>");
            } else if (g.drawnAt() != null) {
                if (g.winnerIds().isEmpty()) {
                    body.append("No eligible entries.");
                } else {
                    body.append("Winners: ");
                    for (int i = 0; i < g.winnerIds().size(); i++) {
                        if (i > 0) body.append(", ");
                        body.append("<@").append(g.winnerIds().get(i)).append(">");
                    }
                }
            } else if (g.cancelledAt() != null) {
                body.append("Cancelled.");
            }
            eb.setDescription(body.toString());
            List<LayoutComponent> rows = giveawayButtons(g);
            if (g.messageId() != null && !g.messageId().isBlank()) {
                ch.editMessageEmbedsById(g.messageId(), WardenEmbeds.brand(eb).build()).setComponents(rows)
                        .queue(ok -> {}, err -> {});
            } else {
                var sent = ch.sendMessageEmbeds(WardenEmbeds.brand(eb).build()).setComponents(rows).complete();
                giveaways.setMessageId(id, sent.getId());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "postGiveaway failed", e);
        }
    }

    public String onGiveawayClick(JDA jda, net.dv8tion.jda.api.entities.Member member, long id, String action) {
        try {
            Giveaway g = giveaways.find(id).orElse(null);
            if (g == null) return "Giveaway not found.";
            if (!g.open(System.currentTimeMillis())) return "This giveaway has ended.";
            if (g.requiredRole() != null && !g.requiredRole().isBlank()) {
                boolean has = false;
                for (Role r : member.getRoles()) {
                    if (r.getId().equals(g.requiredRole())) { has = true; break; }
                }
                if (!has) return "You need the required role to enter.";
            }
            if ("enter".equals(action)) {
                boolean added = giveaways.enter(id, member.getId());
                postGiveaway(jda, id);
                return added ? "Entered. Good luck." : "You're already entered.";
            } else if ("leave".equals(action)) {
                boolean removed = giveaways.leave(id, member.getId());
                postGiveaway(jda, id);
                return removed ? "Left the giveaway." : "You weren't entered.";
            }
            return "Unknown action.";
        } catch (Exception e) {
            log.log(Level.WARNING, "giveawayClick failed", e);
            return "Action failed.";
        }
    }

    public void drawGiveawaysDue(JDA jda) {
        try {
            for (Giveaway g : giveaways.dueForDraw(System.currentTimeMillis())) {
                drawWinners(jda, g.id());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "drawGiveawaysDue failed", e);
        }
    }

    public List<String> drawWinners(JDA jda, long id) {
        try {
            Giveaway g = giveaways.find(id).orElse(null);
            if (g == null) return List.of();
            List<String> pool = giveaways.entries(id);
            Guild guild = jda.getGuildById(jda.getGuilds().isEmpty() ? "" : jda.getGuilds().get(0).getId());
            if (g.requiredRole() != null && !g.requiredRole().isBlank() && guild != null) {
                pool = filterByRole(guild, pool, g.requiredRole());
            }
            Collections.shuffle(pool, ThreadLocalRandom.current());
            List<String> winners = pool.subList(0, Math.min(g.winners(), pool.size()));
            giveaways.markDrawn(id, winners);
            postGiveaway(jda, id);
            // Announce in the channel.
            TextChannel ch = jda.getTextChannelById(g.channelId());
            if (ch != null) {
                StringBuilder s = new StringBuilder();
                if (winners.isEmpty()) {
                    s.append("No eligible entries for **").append(g.prize()).append("**.");
                } else {
                    s.append("Congratulations ");
                    for (int i = 0; i < winners.size(); i++) {
                        if (i > 0) s.append(", ");
                        s.append("<@").append(winners.get(i)).append(">");
                    }
                    s.append(" - you won **").append(g.prize()).append("**!");
                }
                ch.sendMessage(s.toString()).queue(ok -> {}, err -> {});
            }
            return new ArrayList<>(winners);
        } catch (Exception e) {
            log.log(Level.WARNING, "drawWinners failed", e);
            return List.of();
        }
    }

    private static List<String> filterByRole(Guild guild, List<String> pool, String roleId) {
        List<String> out = new ArrayList<>();
        for (String id : pool) {
            var m = guild.getMemberById(id);
            if (m == null) continue;
            for (Role r : m.getRoles()) {
                if (r.getId().equals(roleId)) { out.add(id); break; }
            }
        }
        return out;
    }

    private List<LayoutComponent> giveawayButtons(Giveaway g) {
        if (!g.open(System.currentTimeMillis())) return List.of();
        return List.of(ActionRow.of(
                Button.of(ButtonStyle.SUCCESS, GIVEAWAY_BUTTON_PREFIX + g.id() + ":enter", "Enter"),
                Button.of(ButtonStyle.SECONDARY, GIVEAWAY_BUTTON_PREFIX + g.id() + ":leave", "Leave")));
    }

    /* ============================== Reminders ============================== */

    public void fireReminders(JDA jda) {
        try {
            for (ReminderDao.Reminder r : reminders.dueFor(System.currentTimeMillis())) {
                deliver(jda, r);
                reminders.markDelivered(r.id());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "fireReminders failed", e);
        }
    }

    private void deliver(JDA jda, ReminderDao.Reminder r) {
        try {
            var embed = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Reminder")
                    .setDescription(r.message() == null ? "" : r.message())
                    .setColor(new Color(0x5865F2))
                    .setTimestamp(java.time.Instant.now()))
                    .build();
            if (r.channelId() != null && !r.channelId().isBlank()) {
                TextChannel ch = jda.getTextChannelById(r.channelId());
                if (ch != null) {
                    // Keep the mention as content so the recipient gets a real ping.
                    ch.sendMessage("<@" + r.discordId() + ">")
                            .setEmbeds(embed)
                            .queue(ok -> {}, err -> {});
                    return;
                }
            }
            jda.retrieveUserById(r.discordId()).queue(
                    u -> u.openPrivateChannel().queue(
                            pc -> pc.sendMessageEmbeds(embed).queue(ok -> {}, err -> {}),
                            err -> {}),
                    err -> {});
        } catch (Exception e) {
            log.log(Level.WARNING, "reminder deliver failed", e);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "...";
    }
}
