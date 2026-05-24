package io.warden.discord;

import io.warden.onboarding.DecisionExecutor;
import io.warden.onboarding.model.Answer;
import io.warden.onboarding.model.Application;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.Settings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.warden.discord.flow.DmFlow;

/**
 * JDA-backed implementation of DecisionExecutor. Single-guild for v1 (looks up
 * the guild by the configured guild_id once per call so config reloads land).
 */
public final class JdaDecisionExecutor implements DecisionExecutor {

    private final DiscordService discord;
    private final String guildId;
    private final Logger log;

    public JdaDecisionExecutor(DiscordService discord, String guildId, Logger log) {
        this.discord = discord;
        this.guildId = guildId;
        this.log = log;
    }

    private Optional<Guild> guild() {
        JDA jda = discord.jda();
        if (jda == null) return Optional.empty();
        Guild g = jda.getGuildById(guildId);
        return Optional.ofNullable(g);
    }

    @Override
    public void assignRole(String discordId, String roleId, String reason) {
        guild().ifPresent(g -> {
            var role = g.getRoleById(roleId);
            if (role == null) { log.warning("assignRole: role " + roleId + " missing"); return; }
            g.retrieveMemberById(discordId).queue(
                    m -> g.addRoleToMember(m, role).reason(reason).queue(
                            ok -> {},
                            err -> log.warning("assignRole(" + discordId + "," + roleId + ") REST failed: " + err.getMessage())),
                    err -> log.warning("assignRole: member " + discordId + " not retrievable: " + err.getMessage()));
        });
    }

    @Override
    public void removeRole(String discordId, String roleId, String reason) {
        guild().ifPresent(g -> {
            var role = g.getRoleById(roleId);
            if (role == null) { log.warning("removeRole: role " + roleId + " missing"); return; }
            g.retrieveMemberById(discordId).queue(
                    m -> g.removeRoleFromMember(m, role).reason(reason).queue(
                            ok -> {},
                            err -> log.warning("removeRole(" + discordId + "," + roleId + ") REST failed: " + err.getMessage())),
                    err -> log.warning("removeRole: member " + discordId + " not retrievable: " + err.getMessage()));
        });
    }

    @Override
    public String guildName() {
        return guild().map(Guild::getName).orElse("the server");
    }

    @Override
    public void sendDm(String discordId, String content) {
        JDA jda = discord.jda();
        if (jda == null) return;
        jda.retrieveUserById(discordId).queue(
                user -> user.openPrivateChannel().queue(
                        ch -> ch.sendMessage(content).queue(
                                ok -> {},
                                err -> log.warning("sendDm to " + discordId + " failed: " + err.getMessage())),
                        err -> log.warning("sendDm openPrivateChannel for " + discordId + " failed: " + err.getMessage())),
                err -> log.warning("sendDm retrieveUser " + discordId + " failed: " + err.getMessage()));
    }

    @Override
    public void sendChannel(String channelId, String content) {
        guild().ifPresent(g -> {
            var ch = g.getChannelById(TextChannel.class, channelId);
            if (ch == null) { log.warning("sendChannel: " + channelId + " not a text channel"); return; }
            ch.sendMessage(content).queue(
                    ok -> {},
                    err -> log.warning("sendChannel " + channelId + " failed: " + err.getMessage()));
        });
    }

    @Override
    public void kickMember(String discordId, String reason) {
        guild().ifPresent(g -> g.retrieveMemberById(discordId).queue(
                m -> g.kick(m).reason(reason).queue(
                        ok -> {},
                        err -> log.warning("kick(" + discordId + ") failed: " + err.getMessage())),
                err -> log.warning("kick: member " + discordId + " not retrievable: " + err.getMessage())));
    }

    @Override
    public void banMember(String discordId, String reason) {
        guild().ifPresent(g -> g.retrieveMemberById(discordId).queue(
                m -> g.ban(m, 0, java.util.concurrent.TimeUnit.SECONDS).reason(reason).queue(
                        ok -> {},
                        err -> log.warning("ban(" + discordId + ") failed: " + err.getMessage())),
                err -> log.warning("ban: member " + discordId + " not retrievable: " + err.getMessage())));
    }

    @Override
    public Optional<String> postModReviewEmbed(
            String channelId, Application app, List<Question> questions, List<Answer> answers,
            Settings settings, String discordUsername, String reasoning, double confidence
    ) {
        Optional<Guild> gOpt = guild();
        if (gOpt.isEmpty()) return Optional.empty();
        var ch = gOpt.get().getChannelById(TextChannel.class, channelId);
        if (ch == null) {
            log.warning("postModReviewEmbed: channel " + channelId + " not found");
            return Optional.empty();
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("New application - needs review")
                .setColor(new Color(0xF6, 0xB7, 0x3C))
                .setDescription("**Applicant:** " + discordUsername + " (<@" + app.discordId() + ">)\n"
                        + "Application id: `" + app.id() + "`");
        Map<Long, Question> qById = new HashMap<>();
        for (var q : questions) qById.put(q.id(), q);
        int shown = 0;
        for (var a : answers) {
            if (shown >= 20) break; // discord embed field limit is 25; leave room for meta fields
            var q = qById.get(a.questionId());
            if (q == null) continue;
            String val = a.value().display();
            if (val.length() > 1000) val = val.substring(0, 999) + "…";
            eb.addField(clip("Q: " + q.prompt(), 256), val.isEmpty() ? "_(blank)_" : val, false);
            shown++;
        }
        if (reasoning != null && !reasoning.isBlank()) {
            eb.addField("LLM reasoning", clip(reasoning, 1024), false);
        }
        if (confidence >= 0) {
            eb.addField("LLM confidence", String.format("%.2f", confidence), true);
        }
        eb.setFooter("Use the buttons below to decide.");

        ActionRow buttons = ActionRow.of(
                Button.success("mod:approve:" + app.id(), "Approve"),
                Button.danger("mod:deny:" + app.id(), "Deny"));

        // Send sync via JDA's blocking complete; this code runs on our bg executor anyway.
        try {
            CompletableFuture<net.dv8tion.jda.api.entities.Message> fut =
                    ch.sendMessageEmbeds(WardenEmbeds.brand(eb).build()).setComponents(buttons).submit();
            var msg = fut.get();
            return Optional.of(msg.getId());
        } catch (Exception e) {
            log.log(Level.WARNING, "postModReviewEmbed failed for app " + app.id(), e);
            return Optional.empty();
        }
    }

    @Override
    public void markModReviewEmbedDecided(String channelId, String messageId, String finalDecision, String decidedBy, String note) {
        guild().ifPresent(g -> {
            var ch = g.getChannelById(TextChannel.class, channelId);
            if (ch == null) return;
            ch.retrieveMessageById(messageId).queue(
                    msg -> {
                        if (msg.getEmbeds().isEmpty()) return;
                        MessageEmbed original = msg.getEmbeds().get(0);
                        Color colour = "approve".equalsIgnoreCase(finalDecision)
                                ? new Color(0x4C, 0xAF, 0x50) : new Color(0xE5, 0x39, 0x35);
                        EmbedBuilder eb = new EmbedBuilder(original)
                                .setColor(colour)
                                .setTitle((original.getTitle() == null ? "Application" : original.getTitle())
                                        + " - " + finalDecision.toUpperCase())
                                .setFooter("Decided by " + decidedBy
                                        + (note == null || note.isBlank() ? "" : " · " + clip(note, 200)));
                        msg.editMessageEmbeds(WardenEmbeds.brand(eb).build()).setComponents().queue(
                                ok -> {},
                                err -> log.warning("markModReviewEmbedDecided edit failed: " + err.getMessage()));
                    },
                    err -> log.warning("markModReviewEmbedDecided: message " + messageId + " not retrievable: " + err.getMessage()));
        });
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // referenced from MOD listener (M6) to make sure DmFlow constants stay in sync
    @SuppressWarnings("unused")
    private static final String DUMMY_LINK = DmFlow.MODAL_VALUE_ID;

    @SuppressWarnings("unused")
    private static User none(JDA jda) { return null; }
}
