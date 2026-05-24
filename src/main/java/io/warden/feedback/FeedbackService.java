package io.warden.feedback;

import io.warden.audit.AuditService;
import io.warden.discord.WardenEmbeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts feedback embeds, handles up/down votes, and applies staff status /
 * response changes (which re-render the embed and DM the reporter when
 * configured).
 */
public final class FeedbackService {

    public static final String VOTE_UP_PREFIX = "fb:up:";       // fb:up:<id>
    public static final String VOTE_DOWN_PREFIX = "fb:down:";   // fb:down:<id>

    private final FeedbackDao dao;
    private final FeedbackConfigDao configDao;
    private final AuditService audit;
    private final Logger log;

    public FeedbackService(FeedbackDao dao, FeedbackConfigDao configDao,
                           AuditService audit, Logger log) {
        this.dao = dao;
        this.configDao = configDao;
        this.audit = audit;
        this.log = log;
    }

    public FeedbackDao dao() { return dao; }
    public FeedbackConfigDao config() { return configDao; }

    /* ---------------------- Create from a member ---------------------- */

    /**
     * Result of an open-attempt: either an id (>0), or a reason why it
     * couldn't be opened (channel unset, duplicate, etc.).
     */
    public record OpenResult(long id, String error) {
        public boolean ok() { return id > 0; }
        public static OpenResult ok(long id) { return new OpenResult(id, null); }
        public static OpenResult err(String msg) { return new OpenResult(-1, msg); }
    }

    public OpenResult open(JDA jda, String discordId, String username,
                           String title, String body) {
        try {
            FeedbackConfig cfg = configDao.get();
            if (cfg.channelId() == null || cfg.channelId().isBlank()) {
                return OpenResult.err("The feedback channel isn't configured yet. Ask a mod.");
            }
            if (cfg.requireUniquePerUser() && dao.userHasOpenFeedback(discordId)) {
                return OpenResult.err("You already have an open feedback item. Wait for staff to respond first.");
            }
            long id = dao.create(discordId, username, title, body);
            if (id < 0) return OpenResult.err("Could not save feedback. Try again later.");
            dao.appendNote(id, FeedbackNote.KIND_USER, discordId, username, body);
            audit.write(AuditService.ACTOR_BOT, "feedback_opened", discordId,
                    java.util.Map.of("feedbackId", id, "title", title));
            postOrUpdateEmbed(jda, id);
            return OpenResult.ok(id);
        } catch (Exception e) {
            log.log(Level.WARNING, "feedback open failed", e);
            return OpenResult.err("Internal error.");
        }
    }

    /* ---------------------- Embed rendering ---------------------- */

    /** Post or refresh the embed for a feedback item in the configured channel. */
    public void postOrUpdateEmbed(JDA jda, long feedbackId) {
        if (jda == null) return;
        try {
            Feedback f = dao.find(feedbackId).orElse(null);
            if (f == null) return;
            FeedbackConfig cfg = configDao.get();
            String channelId = (f.channelId() != null && !f.channelId().isBlank())
                    ? f.channelId() : cfg.channelId();
            if (channelId == null || channelId.isBlank()) return;
            TextChannel ch = jda.getTextChannelById(channelId);
            if (ch == null) {
                log.warning("feedback embed: channel " + channelId + " not found for #" + feedbackId);
                return;
            }
            MessageEmbed embed = renderEmbed(f);
            List<LayoutComponent> rows = renderButtons(f, cfg);

            if (f.messageId() != null && !f.messageId().isBlank()
                    && (f.channelId() == null || f.channelId().isBlank() || f.channelId().equals(channelId))) {
                ch.editMessageEmbedsById(f.messageId(), embed).setComponents(rows)
                        .queue(ok -> {}, err -> log.warning("feedback embed edit failed: " + err.getMessage()));
                if (f.channelId() == null || f.channelId().isBlank()) {
                    dao.setMessage(feedbackId, channelId, f.messageId());
                }
                return;
            }
            var sent = ch.sendMessageEmbeds(embed).setComponents(rows).complete();
            dao.setMessage(feedbackId, channelId, sent.getId());
        } catch (Exception e) {
            log.log(Level.WARNING, "postOrUpdateEmbed failed", e);
        }
    }

    private MessageEmbed renderEmbed(Feedback f) throws Exception {
        FeedbackDao.VoteTally tally = dao.tally(f.id());
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("#" + f.id() + ": " + truncate(f.title(), 200))
                .setColor(f.status().color());
        StringBuilder desc = new StringBuilder();
        desc.append(truncate(f.body(), 2000));
        if (f.staffResponse() != null && !f.staffResponse().isBlank()) {
            desc.append("\n\n**Staff response**\n").append(truncate(f.staffResponse(), 1000));
        }
        eb.setDescription(desc.toString());
        eb.addField("Status", f.status().label(), true);
        eb.addField("Score", tally.net() + "  (" + tally.up() + "  /  " + tally.down() + ")", true);
        String reporter = f.discordUsername() == null || f.discordUsername().isBlank()
                ? f.discordId() : f.discordUsername();
        eb.setFooter("Submitted by " + reporter);
        eb.setTimestamp(java.time.Instant.ofEpochMilli(f.createdAt()));
        return WardenEmbeds.brand(eb).build();
    }

    private List<LayoutComponent> renderButtons(Feedback f, FeedbackConfig cfg) {
        boolean lock = cfg.lockedWhenResolved() && f.status().terminal();
        FeedbackDao.VoteTally tally;
        try { tally = dao.tally(f.id()); }
        catch (Exception e) { tally = new FeedbackDao.VoteTally(0, 0); }
        Button up = Button.of(ButtonStyle.SUCCESS, VOTE_UP_PREFIX + f.id(),
                "Upvote (" + tally.up() + ")");
        Button down = Button.of(ButtonStyle.DANGER, VOTE_DOWN_PREFIX + f.id(),
                "Downvote (" + tally.down() + ")");
        if (lock) {
            up = up.asDisabled();
            down = down.asDisabled();
        }
        return List.of(ActionRow.of(up, down));
    }

    /* ---------------------- Vote handling ---------------------- */

    public String onVote(JDA jda, String discordId, long feedbackId, int direction) {
        try {
            Feedback f = dao.find(feedbackId).orElse(null);
            if (f == null) return "That feedback no longer exists.";
            FeedbackConfig cfg = configDao.get();
            if (cfg.lockedWhenResolved() && f.status().terminal()) {
                return "Voting is closed because this feedback is " + f.status().label().toLowerCase() + ".";
            }
            if (f.discordId() != null && f.discordId().equals(discordId)) {
                return "You can't vote on your own feedback.";
            }
            FeedbackDao.VoteTally tally = dao.toggleVote(feedbackId, discordId, direction);
            dao.touch(feedbackId);
            postOrUpdateEmbed(jda, feedbackId);
            return "Vote recorded. Current score: " + tally.net()
                    + " (" + tally.up() + " up, " + tally.down() + " down).";
        } catch (Exception e) {
            log.log(Level.WARNING, "feedback vote failed", e);
            return "Could not record vote.";
        }
    }

    /* ---------------------- Staff actions ---------------------- */

    public boolean changeStatus(JDA jda, long id, FeedbackStatus next,
                                String staffId, String staffName) {
        try {
            Feedback f = dao.find(id).orElse(null);
            if (f == null) return false;
            if (f.status() == next) return true;
            dao.setStatus(id, next);
            dao.appendNote(id, FeedbackNote.KIND_SYSTEM, staffId, staffName,
                    (staffName == null || staffName.isBlank() ? "Staff" : staffName)
                            + " set status to " + next.label());
            audit.write(AuditService.ACTOR_WEB, "feedback_status", f.discordId(),
                    java.util.Map.of("feedbackId", id, "status", next.wire(),
                            "staffId", staffId, "staffName", staffName));
            postOrUpdateEmbed(jda, id);
            FeedbackConfig cfg = configDao.get();
            if (cfg.dmReporterOnStatus()) {
                MessageEmbed dm = WardenEmbeds.brand(new EmbedBuilder()
                        .setTitle("Feedback #" + id + " status updated")
                        .setDescription("Your feedback **" + truncate(f.title(), 200)
                                + "** was set to **" + next.label() + "**.")
                        .addField("Status", next.label(), true)
                        .setColor(next.color())
                        .setTimestamp(java.time.Instant.now()))
                        .build();
                dmUserEmbed(jda, f.discordId(), dm);
            }
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "feedback changeStatus failed", e);
            return false;
        }
    }

    /**
     * Update the public staff response shown on the embed and DM the reporter.
     */
    public boolean setStaffResponse(JDA jda, long id, String response,
                                    String staffId, String staffName) {
        try {
            Feedback f = dao.find(id).orElse(null);
            if (f == null) return false;
            dao.setStaffResponse(id, response);
            dao.appendNote(id, FeedbackNote.KIND_STAFF, staffId, staffName,
                    response == null ? "" : response);
            audit.write(AuditService.ACTOR_WEB, "feedback_response", f.discordId(),
                    java.util.Map.of("feedbackId", id, "staffId", staffId, "staffName", staffName));
            postOrUpdateEmbed(jda, id);
            FeedbackConfig cfg = configDao.get();
            if (cfg.dmReporterOnResponse() && response != null && !response.isBlank()) {
                String who = (staffName == null || staffName.isBlank()) ? "Staff" : staffName;
                MessageEmbed dm = WardenEmbeds.brand(new EmbedBuilder()
                        .setTitle("Staff replied to feedback #" + id)
                        .setDescription(truncate(response, 3500))
                        .addField("Feedback", truncate(f.title(), 200), false)
                        .addField("Responder", who, true)
                        .addField("Status", f.status().label(), true)
                        .setColor(f.status().color())
                        .setTimestamp(java.time.Instant.now()))
                        .build();
                dmUserEmbed(jda, f.discordId(), dm);
            }
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "feedback setStaffResponse failed", e);
            return false;
        }
    }

    public boolean delete(JDA jda, long id, String staffId, String staffName) {
        try {
            Feedback f = dao.find(id).orElse(null);
            if (f == null) return false;
            if (jda != null && f.channelId() != null && !f.channelId().isBlank()
                    && f.messageId() != null && !f.messageId().isBlank()) {
                TextChannel ch = jda.getTextChannelById(f.channelId());
                if (ch != null) {
                    ch.deleteMessageById(f.messageId()).queue(ok -> {}, err -> {});
                }
            }
            dao.delete(id);
            audit.write(AuditService.ACTOR_WEB, "feedback_deleted", f.discordId(),
                    java.util.Map.of("feedbackId", id, "staffId", staffId, "staffName", staffName));
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "feedback delete failed", e);
            return false;
        }
    }

    /* ---------------------- Helpers ---------------------- */

    private void dmUserEmbed(JDA jda, String discordId, MessageEmbed embed) {
        if (jda == null || discordId == null || discordId.isBlank()) return;
        try {
            jda.retrieveUserById(discordId).queue(
                    u -> u.openPrivateChannel().queue(
                            pc -> pc.sendMessageEmbeds(embed).queue(ok -> {}, err -> {}),
                            err -> {}),
                    err -> {});
        } catch (Exception ignored) {}
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
