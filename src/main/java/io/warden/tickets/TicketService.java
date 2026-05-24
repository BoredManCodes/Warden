package io.warden.tickets;

import io.warden.audit.AuditService;
import io.warden.data.dao.SettingsDao;
import io.warden.discord.WardenEmbeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns panel rendering and the lifecycle of a ticket: open, append messages,
 * status changes, DM round-trips. Tickets run in one of two delivery modes:
 *  - "dm"      : every message is exchanged via the reporter's DMs.
 *  - "channel" : a dedicated text channel is created under a configured
 *                Discord category and both sides post there.
 * Mode resolves from the per-category override; if that says "inherit",
 * the global default on TicketsConfig wins.
 */
public final class TicketService {

    public static final String OPEN_BUTTON_PREFIX = "ticket:open:"; // ticket:open:<categoryId>
    public static final String MODAL_PREFIX = "ticket:modal:";      // ticket:modal:<categoryId>
    public static final String MODAL_SUBJECT_FIELD = "subject";
    public static final String MODAL_BODY_FIELD = "body";
    public static final String CLOSE_BUTTON_PREFIX = "ticket:close:"; // ticket:close:<ticketId>

    private final TicketDao tickets;
    private final TicketCategoryDao categories;
    private final TicketPanelDao panels;
    private final TicketsConfigDao config;
    private final AuditService audit;
    private final SettingsDao settingsDao;
    private final Path attachmentsDir;
    private final TranscriptService transcripts;
    private final Logger log;
    private volatile String publicBaseUrl = "";

    public TicketService(TicketDao tickets, TicketCategoryDao categories,
                         TicketPanelDao panels, TicketsConfigDao config,
                         AuditService audit, SettingsDao settingsDao,
                         Path attachmentsDir, TranscriptService transcripts, Logger log) {
        this.tickets = tickets;
        this.categories = categories;
        this.panels = panels;
        this.config = config;
        this.audit = audit;
        this.settingsDao = settingsDao;
        this.attachmentsDir = attachmentsDir;
        this.transcripts = transcripts;
        this.log = log;
    }

    /** Set by WebService once the public base URL is known so DM links are absolute. */
    public void setPublicBaseUrl(String base) {
        this.publicBaseUrl = base == null ? "" : base;
    }

    public TranscriptService transcripts() { return transcripts; }

    public TicketDao tickets() { return tickets; }
    public TicketCategoryDao categories() { return categories; }
    public TicketPanelDao panels() { return panels; }
    public TicketsConfigDao config() { return config; }
    public Path attachmentsDir() { return attachmentsDir; }

    /* ------------------------- Panel rendering ------------------------- */

    public String postOrUpdatePanel(JDA jda, long panelId) {
        try {
            TicketPanel panel = panels.find(panelId).orElse(null);
            if (panel == null) return null;
            TextChannel ch = jda.getTextChannelById(panel.channelId());
            if (ch == null) {
                log.warning("ticket panel " + panelId + ": channel " + panel.channelId() + " not found");
                return null;
            }
            List<TicketCategory> cats = categories.listEnabled();
            MessageEmbed embed = renderPanelEmbed(panel);
            List<LayoutComponent> rows = renderPanelButtons(cats);
            if (panel.messageId() != null && !panel.messageId().isBlank()) {
                ch.editMessageEmbedsById(panel.messageId(), embed)
                        .setComponents(rows)
                        .queue(ok -> {}, err -> log.warning("ticket panel edit failed: " + err.getMessage()));
                return panel.messageId();
            }
            var sent = ch.sendMessageEmbeds(embed).setComponents(rows).complete();
            panels.setMessageId(panelId, sent.getId());
            return sent.getId();
        } catch (Exception e) {
            log.log(Level.WARNING, "postOrUpdatePanel failed", e);
            return null;
        }
    }

    private MessageEmbed renderPanelEmbed(TicketPanel panel) {
        return WardenEmbeds.brand(new EmbedBuilder()
                .setTitle(panel.title() == null || panel.title().isBlank() ? "Open a ticket" : panel.title())
                .setDescription(panel.description() == null ? "" : panel.description())
                .setColor(parseColor(panel.colorHex())))
                .build();
    }

    private List<LayoutComponent> renderPanelButtons(List<TicketCategory> cats) {
        List<LayoutComponent> rows = new ArrayList<>();
        List<Button> current = new ArrayList<>();
        for (TicketCategory cat : cats) {
            ButtonStyle style = parseStyle(cat.buttonStyle());
            Button b = Button.of(style, OPEN_BUTTON_PREFIX + cat.id(),
                    cat.name() == null || cat.name().isBlank() ? cat.slug() : cat.name());
            if (cat.emoji() != null && !cat.emoji().isBlank()) {
                try { b = b.withEmoji(Emoji.fromFormatted(cat.emoji())); }
                catch (Exception ignored) {}
            }
            current.add(b);
            if (current.size() == 5) {
                rows.add(ActionRow.of(current));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) rows.add(ActionRow.of(current));
        if (rows.isEmpty()) {
            rows.add(ActionRow.of(Button.of(ButtonStyle.SECONDARY, OPEN_BUTTON_PREFIX + "0", "No categories")
                    .asDisabled()));
        }
        return rows;
    }

    /* ------------------------- Ticket lifecycle ------------------------- */

    public long openTicket(JDA jda, long categoryId, String discordId, String username,
                           String subject, String body) {
        try {
            Optional<TicketCategory> catOpt = categories.find(categoryId);
            Long catRef = catOpt.map(TicketCategory::id).orElse(null);
            TicketsConfig cfg = config.get();

            String resolvedMode = resolveMode(catOpt.orElse(null), cfg);

            long id = tickets.create(catRef, discordId, username, subject, body, resolvedMode);
            if (id < 0) return -1;
            tickets.appendMessage(id, TicketMessage.KIND_USER, discordId, username, body);

            audit.write(AuditService.ACTOR_BOT, "ticket_opened", discordId,
                    java.util.Map.of(
                            "ticketId", id,
                            "category", catOpt.map(TicketCategory::slug).orElse(""),
                            "mode", resolvedMode,
                            "subject", subject));

            if (TicketsConfig.MODE_CHANNEL.equals(resolvedMode)) {
                createTicketChannel(jda, id, catOpt.orElse(null), cfg, discordId, username, subject, body);
            } else {
                if (cfg.dmReporterOnOpen()) sendOpenAckDm(jda, id, cfg, subject);
                notifyStaffChannel(jda, id, cfg);
            }
            return id;
        } catch (Exception e) {
            log.log(Level.WARNING, "openTicket failed", e);
            return -1;
        }
    }

    private String resolveMode(TicketCategory cat, TicketsConfig cfg) {
        if (cat != null) {
            String m = cat.normalisedDeliveryMode();
            if (TicketCategory.MODE_DM.equals(m)) return TicketsConfig.MODE_DM;
            if (TicketCategory.MODE_CHANNEL.equals(m)) return TicketsConfig.MODE_CHANNEL;
        }
        return cfg.normalisedDefaultMode();
    }

    private void createTicketChannel(JDA jda, long ticketId, TicketCategory cat, TicketsConfig cfg,
                                     String discordId, String username, String subject, String body) {
        if (jda == null) {
            log.warning("ticket " + ticketId + ": cannot create channel (jda not ready), falling back to DM");
            sendOpenAckDm(jda, ticketId, cfg, subject);
            return;
        }
        try {
            String categoryId = (cat != null && cat.channelCategoryId() != null && !cat.channelCategoryId().isBlank())
                    ? cat.channelCategoryId() : cfg.channelCategoryId();
            Guild guild = null;
            Category parent = null;
            if (categoryId != null && !categoryId.isBlank()) {
                parent = jda.getCategoryById(categoryId);
                if (parent != null) guild = parent.getGuild();
            }
            if (parent == null) {
                log.warning("ticket " + ticketId + ": Discord category id not set or not visible (" + categoryId
                        + "); falling back to DM");
                tickets.setTicketChannel(ticketId, "");
                tickets.touch(ticketId);
                sendOpenAckDm(jda, ticketId, cfg, subject);
                notifyStaffChannel(jda, ticketId, cfg);
                return;
            }

            String channelName = buildChannelName(ticketId, username);
            var action = parent.createTextChannel(channelName).setParent(parent);

            // Lock down: hide from @everyone, allow reporter + mod role
            if (guild != null) {
                Role everyone = guild.getPublicRole();
                action = action.addPermissionOverride(everyone, EnumSet.noneOf(Permission.class),
                        EnumSet.of(Permission.VIEW_CHANNEL));
                try {
                    var member = guild.retrieveMemberById(discordId).complete();
                    if (member != null) {
                        action = action.addPermissionOverride(member,
                                EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                        Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES),
                                EnumSet.noneOf(Permission.class));
                    }
                } catch (Exception ignored) {}
                try {
                    String modRoleId = settingsDao.get().modRoleId();
                    if (modRoleId != null && !modRoleId.isBlank()) {
                        Role modRole = guild.getRoleById(modRoleId);
                        if (modRole != null) {
                            action = action.addPermissionOverride(modRole,
                                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                            Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE,
                                            Permission.MESSAGE_ATTACH_FILES),
                                    EnumSet.noneOf(Permission.class));
                        }
                    }
                } catch (Exception ignored) {}
            }

            TextChannel created = action.complete();
            tickets.setTicketChannel(ticketId, created.getId());

            MessageEmbed openEmbed = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Ticket #" + ticketId + ": " + safe(subject))
                    .setDescription(trim(body, 3500))
                    .setColor(new Color(0x5865F2))
                    .setFooter("Opened by " + (username == null || username.isBlank() ? discordId : username))
                    .setTimestamp(java.time.Instant.now())
                    .addField("Category", cat == null ? "(uncategorised)" : safe(cat.name()), true))
                    .build();
            MessageCreateData msg = new MessageCreateBuilder()
                    .setContent("<@" + discordId + ">")
                    .setEmbeds(openEmbed)
                    .setComponents(ActionRow.of(
                            Button.of(ButtonStyle.DANGER, CLOSE_BUTTON_PREFIX + ticketId, "Close ticket")))
                    .build();
            created.sendMessage(msg).queue(ok -> {}, err -> log.warning("ticket open post failed: " + err.getMessage()));

            if (cfg.dmReporterOnOpen() && cfg.openAckMessage() != null && !cfg.openAckMessage().isBlank()) {
                MessageEmbed dm = WardenEmbeds.brand(new EmbedBuilder()
                        .setTitle("Ticket #" + ticketId + " opened")
                        .setDescription(cfg.openAckMessage()
                                + "\n\nA private channel has been created for this ticket: <#" + created.getId() + ">")
                        .setColor(new Color(0x5865F2)))
                        .build();
                dmUserEmbed(jda, discordId, dm);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "createTicketChannel failed for #" + ticketId, e);
            try { tickets.touch(ticketId); } catch (Exception ignored) {}
        }
    }

    private static String buildChannelName(long ticketId, String username) {
        String slug = username == null ? "" : username.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
        if (slug.length() > 20) slug = slug.substring(0, 20);
        if (slug.isBlank()) slug = "user";
        return "ticket-" + ticketId + "-" + slug;
    }

    private void sendOpenAckDm(JDA jda, long ticketId, TicketsConfig cfg, String subject) {
        if (cfg.openAckMessage() == null || cfg.openAckMessage().isBlank()) return;
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return;
            MessageEmbed embed = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Ticket #" + ticketId + " opened")
                    .setDescription(cfg.openAckMessage())
                    .addField("Subject", safe(subject), false)
                    .setColor(new Color(0x5865F2))
                    .setTimestamp(java.time.Instant.now()))
                    .build();
            dmUserEmbed(jda, t.discordId(), embed);
        } catch (Exception e) {
            log.log(Level.WARNING, "sendOpenAckDm failed", e);
        }
    }

    /**
     * Record a staff-only internal note on a ticket. Internal notes are stored
     * alongside regular messages but tagged with {@link TicketMessage#KIND_INTERNAL}
     * so the dashboard can render them with a distinct style and the transcript
     * renderer can omit them. They are never delivered to the reporter (no DM,
     * no post into the ticket channel where the reporter has read access).
     */
    public boolean postInternalNote(long ticketId, String staffId, String staffName, String body) {
        if (body == null || body.isBlank()) return false;
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return false;
            long msgId = tickets.appendMessage(ticketId, TicketMessage.KIND_INTERNAL,
                    staffId, staffName, body);
            tickets.touch(ticketId);
            audit.write(AuditService.ACTOR_WEB, "ticket_internal_note", t.discordId(),
                    java.util.Map.of("ticketId", ticketId, "messageId", msgId,
                            "staffId", staffId == null ? "" : staffId,
                            "staffName", staffName == null ? "" : staffName));
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "postInternalNote failed", e);
            return false;
        }
    }

    /** Staff reply from the dashboard. */
    public boolean replyAsStaff(JDA jda, long ticketId, String staffId, String staffName,
                                String body, List<TicketMessage.Attachment> attachments) {
        if ((body == null || body.isBlank()) && (attachments == null || attachments.isEmpty())) return false;
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return false;
            TicketsConfig cfg = config.get();
            if (cfg.closedLockReplies() && t.status().terminal()) return false;

            long msgId = tickets.appendMessage(ticketId, TicketMessage.KIND_STAFF, staffId, staffName,
                    body == null ? "" : body, attachments);
            tickets.touch(ticketId);
            audit.write(AuditService.ACTOR_WEB, "ticket_reply", t.discordId(),
                    java.util.Map.of("ticketId", ticketId, "messageId", msgId,
                            "staffId", staffId, "staffName", staffName,
                            "attachments", attachments == null ? 0 : attachments.size()));

            if (cfg.dmReporterOnReply() || t.isChannelMode()) {
                deliverStaffMessage(jda, t, staffName, body, attachments);
            }
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "replyAsStaff failed", e);
            return false;
        }
    }

    private void deliverStaffMessage(JDA jda, Ticket t, String staffName,
                                     String body, List<TicketMessage.Attachment> attachments) {
        if (jda == null) return;
        String staffLabel = staffName == null || staffName.isBlank() ? "Staff" : staffName;
        MessageEmbed embed = WardenEmbeds.brand(new EmbedBuilder()
                .setAuthor(staffLabel + " (staff)")
                .setDescription(body == null || body.isBlank() ? "(no text)" : trim(body, 3500))
                .setColor(new Color(0x3BA55D))
                .setFooter("Ticket #" + t.id())
                .setTimestamp(java.time.Instant.now()))
                .build();
        if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
            TextChannel ch = jda.getTextChannelById(t.channelId());
            if (ch != null) {
                sendEmbedWithAttachments(ch, embed, attachments);
                return;
            }
            // channel deleted? fall through to DM
        }
        // DM path
        jda.retrieveUserById(t.discordId()).queue(
                u -> u.openPrivateChannel().queue(
                        pc -> sendEmbedWithAttachments(pc, embed, attachments),
                        err -> {}),
                err -> {});
    }

    public boolean changeStatus(JDA jda, long ticketId, TicketStatus next,
                                String staffId, String staffName) {
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return false;
            if (t.status() == next) return true;
            tickets.setStatus(ticketId, next);
            String note = (staffName == null || staffName.isBlank() ? "Staff" : staffName)
                    + " set status to " + next.label() + ".";
            tickets.appendMessage(ticketId, TicketMessage.KIND_SYSTEM, staffId, staffName, note);
            audit.write(AuditService.ACTOR_WEB, "ticket_status", t.discordId(),
                    java.util.Map.of("ticketId", ticketId, "status", next.wire(),
                            "staffId", staffId, "staffName", staffName));

            TicketsConfig cfg = config.get();
            MessageEmbed statusEmbed = WardenEmbeds.brand(new EmbedBuilder()
                    .setAuthor("System")
                    .setTitle("Status: " + next.label())
                    .setDescription("Ticket #" + ticketId + " is now **" + next.label() + "**.")
                    .setColor(statusColor(next))
                    .setTimestamp(java.time.Instant.now()))
                    .build();
            if (jda != null) {
                if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
                    TextChannel ch = jda.getTextChannelById(t.channelId());
                    if (ch != null) ch.sendMessageEmbeds(statusEmbed).queue(ok -> {}, err -> {});
                    if (next.terminal()) archiveTicketChannel(jda, t);
                }
                if (cfg.dmReporterOnStatus()) dmUserEmbed(jda, t.discordId(), statusEmbed);
            }

            // On close always re-render the transcript so it captures every reply
            // that happened since the last generation, then DM the reporter a link.
            if (next == TicketStatus.CLOSED && transcripts != null) {
                Ticket refreshed = tickets.find(ticketId).orElse(t);
                String token = transcripts.generate(jda, refreshed);
                if (token != null && !token.isBlank() && jda != null) {
                    MessageEmbed dm = renderTranscriptDm(ticketId, t.subject(), token);
                    dmUserEmbed(jda, t.discordId(), dm);
                }
            }
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "changeStatus failed", e);
            return false;
        }
    }

    private MessageEmbed renderTranscriptDm(long ticketId, String subject, String token) {
        String url;
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            url = "/tickets/transcript/" + token;
        } else {
            String base = publicBaseUrl.endsWith("/")
                    ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
            url = base + "/tickets/transcript/" + token;
        }
        return WardenEmbeds.brand(new EmbedBuilder()
                .setTitle("Ticket #" + ticketId + " transcript")
                .setDescription("Your ticket has been closed. You can review the full conversation here:\n"
                        + url + "\n\nThe link is private to you, please don't share it.")
                .addField("Subject", safe(subject), false)
                .setColor(new Color(0x5865F2))
                .setTimestamp(java.time.Instant.now()))
                .build();
    }

    private void archiveTicketChannel(JDA jda, Ticket t) {
        try {
            TextChannel ch = jda.getTextChannelById(t.channelId());
            if (ch == null) return;
            // Remove send permission from reporter; keep visible so staff can read history.
            Guild g = ch.getGuild();
            try {
                var member = g.retrieveMemberById(t.discordId()).complete();
                if (member != null) {
                    ch.upsertPermissionOverride(member)
                            .deny(Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES)
                            .queue(ok -> {}, err -> {});
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public boolean assign(long ticketId, String staffId, String staffName) {
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return false;
            tickets.setAssignee(ticketId, staffId, staffName);
            tickets.appendMessage(ticketId, TicketMessage.KIND_SYSTEM, staffId, staffName,
                    (staffName == null || staffName.isBlank() ? "Staff" : staffName) + " took this ticket.");
            audit.write(AuditService.ACTOR_WEB, "ticket_assign", t.discordId(),
                    java.util.Map.of("ticketId", ticketId, "staffId", staffId, "staffName", staffName));
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "assign failed", e);
            return false;
        }
    }

    /* ------------------------- Participants ------------------------- */

    public enum ParticipantResult {
        OK,
        NOT_FOUND,
        NOT_A_CHANNEL_TICKET,
        CHANNEL_MISSING,
        USER_NOT_FOUND,
        ALREADY_ADDED,
        NOT_A_PARTICIPANT,
        IS_REPORTER,
        FAILED
    }

    public record ParticipantOutcome(ParticipantResult result, String addedUserName) {
        public static ParticipantOutcome of(ParticipantResult r) { return new ParticipantOutcome(r, null); }
    }

    /**
     * Resolve the Discord channel a participant should be added to for the
     * given ticket. Channel-mode tickets (including ones that migrated from
     * DM) use {@code channelId}; otherwise empty so callers can prompt to
     * migrate first.
     */
    public Optional<String> participantChannelId(Ticket t) {
        if (t == null) return Optional.empty();
        if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
            return Optional.of(t.channelId());
        }
        return Optional.empty();
    }

    /**
     * Snapshot of users who currently have an explicit VIEW_CHANNEL override
     * on a channel-mode ticket, minus the reporter. Empty when the ticket has
     * no associated channel.
     */
    public record Participant(String userId, String userName) {}

    public List<Participant> listParticipants(JDA jda, Ticket t) {
        List<Participant> out = new ArrayList<>();
        if (jda == null || t == null) return out;
        Optional<String> chId = participantChannelId(t);
        if (chId.isEmpty()) return out;
        TextChannel ch = jda.getTextChannelById(chId.get());
        if (ch == null) return out;
        String reporterId = t.discordId() == null ? "" : t.discordId();
        for (var override : ch.getMemberPermissionOverrides()) {
            String mid = override.getId();
            if (mid == null || mid.equals(reporterId)) continue;
            if (!override.getAllowed().contains(Permission.VIEW_CHANNEL)) continue;
            String name;
            try {
                var member = override.getMember();
                if (member == null) {
                    name = "User " + mid;
                } else if (member.getUser().getGlobalName() != null && !member.getUser().getGlobalName().isBlank()) {
                    name = member.getUser().getGlobalName();
                } else {
                    name = member.getEffectiveName();
                }
            } catch (Exception ex) {
                name = "User " + mid;
            }
            out.add(new Participant(mid, name));
        }
        out.sort((a, b) -> a.userName().compareToIgnoreCase(b.userName()));
        return out;
    }

    /**
     * Grant a Discord user view/post permissions on the ticket's channel and
     * log it as a system message + audit entry. Returns a structured result so
     * the dashboard can render a friendly error (missing channel, ticket not
     * found, etc.).
     */
    public ParticipantOutcome addParticipant(JDA jda, long ticketId, String userId,
                                             String staffId, String staffName) {
        if (userId == null || userId.isBlank()) return ParticipantOutcome.of(ParticipantResult.USER_NOT_FOUND);
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return ParticipantOutcome.of(ParticipantResult.NOT_FOUND);
            if (userId.equals(t.discordId())) return ParticipantOutcome.of(ParticipantResult.IS_REPORTER);
            Optional<String> chId = participantChannelId(t);
            if (chId.isEmpty()) return ParticipantOutcome.of(ParticipantResult.NOT_A_CHANNEL_TICKET);
            if (jda == null) return ParticipantOutcome.of(ParticipantResult.FAILED);
            TextChannel ch = jda.getTextChannelById(chId.get());
            if (ch == null) return ParticipantOutcome.of(ParticipantResult.CHANNEL_MISSING);

            var member = ch.getGuild().retrieveMemberById(userId).complete();
            if (member == null) return ParticipantOutcome.of(ParticipantResult.USER_NOT_FOUND);

            var existing = ch.getPermissionOverride(member);
            if (existing != null && existing.getAllowed().contains(Permission.VIEW_CHANNEL)) {
                return ParticipantOutcome.of(ParticipantResult.ALREADY_ADDED);
            }

            ch.upsertPermissionOverride(member)
                    .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                            Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES)
                    .complete();

            String displayName = member.getUser().getGlobalName() != null
                    && !member.getUser().getGlobalName().isBlank()
                    ? member.getUser().getGlobalName() : member.getEffectiveName();

            String actor = staffName == null || staffName.isBlank() ? "Staff" : staffName;
            String note = actor + " added " + displayName + " to this ticket.";
            tickets.appendMessage(ticketId, TicketMessage.KIND_SYSTEM, staffId, staffName, note);
            tickets.touch(ticketId);

            try {
                MessageEmbed addEmbed = WardenEmbeds.brand(new EmbedBuilder()
                        .setAuthor("Participant added")
                        .setDescription(":inbox_tray: " + note)
                        .setColor(new Color(0x3BA55D))
                        .setTimestamp(java.time.Instant.now()))
                        .build();
                // Mention stays in content so the new participant actually gets pinged.
                ch.sendMessage("<@" + userId + ">")
                        .setEmbeds(addEmbed)
                        .queue(ok -> {}, err -> {});
            } catch (Exception ignored) {}

            audit.write(AuditService.ACTOR_WEB, "ticket_participant_add", t.discordId(),
                    java.util.Map.of(
                            "ticketId", ticketId,
                            "userId", userId,
                            "userName", displayName,
                            "staffId", staffId == null ? "" : staffId,
                            "staffName", actor));

            return new ParticipantOutcome(ParticipantResult.OK, displayName);
        } catch (Exception e) {
            log.log(Level.WARNING, "addParticipant failed", e);
            return ParticipantOutcome.of(ParticipantResult.FAILED);
        }
    }

    public ParticipantOutcome removeParticipant(JDA jda, long ticketId, String userId,
                                                String staffId, String staffName) {
        if (userId == null || userId.isBlank()) return ParticipantOutcome.of(ParticipantResult.USER_NOT_FOUND);
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return ParticipantOutcome.of(ParticipantResult.NOT_FOUND);
            if (userId.equals(t.discordId())) return ParticipantOutcome.of(ParticipantResult.IS_REPORTER);
            Optional<String> chId = participantChannelId(t);
            if (chId.isEmpty()) return ParticipantOutcome.of(ParticipantResult.NOT_A_CHANNEL_TICKET);
            if (jda == null) return ParticipantOutcome.of(ParticipantResult.FAILED);
            TextChannel ch = jda.getTextChannelById(chId.get());
            if (ch == null) return ParticipantOutcome.of(ParticipantResult.CHANNEL_MISSING);

            var member = ch.getGuild().retrieveMemberById(userId).complete();
            if (member == null) return ParticipantOutcome.of(ParticipantResult.USER_NOT_FOUND);

            var existing = ch.getPermissionOverride(member);
            if (existing == null || !existing.getAllowed().contains(Permission.VIEW_CHANNEL)) {
                return ParticipantOutcome.of(ParticipantResult.NOT_A_PARTICIPANT);
            }

            existing.delete().complete();

            String displayName = member.getUser().getGlobalName() != null
                    && !member.getUser().getGlobalName().isBlank()
                    ? member.getUser().getGlobalName() : member.getEffectiveName();

            String actor = staffName == null || staffName.isBlank() ? "Staff" : staffName;
            String note = actor + " removed " + displayName + " from this ticket.";
            tickets.appendMessage(ticketId, TicketMessage.KIND_SYSTEM, staffId, staffName, note);
            tickets.touch(ticketId);

            try {
                MessageEmbed removeEmbed = WardenEmbeds.brand(new EmbedBuilder()
                        .setAuthor("Participant removed")
                        .setDescription(":outbox_tray: " + note)
                        .setColor(new Color(0xE6A23C))
                        .setTimestamp(java.time.Instant.now()))
                        .build();
                ch.sendMessageEmbeds(removeEmbed).queue(ok -> {}, err -> {});
            } catch (Exception ignored) {}

            audit.write(AuditService.ACTOR_WEB, "ticket_participant_remove", t.discordId(),
                    java.util.Map.of(
                            "ticketId", ticketId,
                            "userId", userId,
                            "userName", displayName,
                            "staffId", staffId == null ? "" : staffId,
                            "staffName", actor));

            return new ParticipantOutcome(ParticipantResult.OK, displayName);
        } catch (Exception e) {
            log.log(Level.WARNING, "removeParticipant failed", e);
            return ParticipantOutcome.of(ParticipantResult.FAILED);
        }
    }

    /* ------------------------- Inbound user messages ------------------------- */

    /** Record a user's reply (DM or in their ticket channel). Returns the ticket id appended to, or -1. */
    public long recordInboundUserMessage(Ticket t, String body,
                                         List<TicketMessage.Attachment> attachments) {
        return recordInboundUserMessage(null, t, body, attachments);
    }

    public long recordInboundUserMessage(JDA jda, Ticket t, String body,
                                         List<TicketMessage.Attachment> attachments) {
        if (t == null) return -1;
        try {
            tickets.appendMessage(t.id(), TicketMessage.KIND_USER, t.discordId(),
                    t.discordUsername(), body == null ? "" : body, attachments);
            tickets.touch(t.id());
            audit.write(AuditService.ACTOR_BOT, "ticket_user_reply", t.discordId(),
                    java.util.Map.of("ticketId", t.id(),
                            "attachments", attachments == null ? 0 : attachments.size()));
            return t.id();
        } catch (Exception e) {
            log.log(Level.WARNING, "recordInboundUserMessage failed", e);
            return -1;
        }
    }

    private String avatarForUser(JDA jda, String discordId) {
        if (jda == null || discordId == null || discordId.isBlank()) return null;
        try {
            var user = jda.retrieveUserById(discordId).complete();
            return user == null ? null : user.getEffectiveAvatarUrl();
        } catch (Exception ignored) {
            return null;
        }
    }

    /* ------------------------- Migrate DM ticket to channel ------------------------- */

    public enum MigrateResult { OK, ALREADY_MIGRATED, NOT_FOUND, NO_CATEGORY, FAILED }

    /**
     * Promote a DM-mode ticket into a dedicated Discord channel and switch it
     * to channel mode. A temporary webhook is created so the existing message
     * history can be replayed with each line attributed to its original
     * author; the webhook is deleted once backfill completes. Future replies
     * behave like any native channel-mode ticket: staff replies from the
     * dashboard post directly to the channel, and the reporter's posts in the
     * channel feed back to the dashboard.
     */
    public MigrateResult migrateToChannel(JDA jda, long ticketId, String triggeredBy) {
        if (jda == null) return MigrateResult.FAILED;
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return MigrateResult.NOT_FOUND;
            if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
                return MigrateResult.ALREADY_MIGRATED;
            }

            TicketsConfig cfg = config.get();
            Optional<TicketCategory> catOpt = t.categoryId() == null
                    ? Optional.empty() : categories.find(t.categoryId());
            String parentCategoryId = catOpt
                    .map(TicketCategory::channelCategoryId)
                    .filter(s -> s != null && !s.isBlank())
                    .orElseGet(cfg::channelCategoryId);
            if (parentCategoryId == null || parentCategoryId.isBlank()) {
                log.warning("migrateToChannel #" + ticketId + ": no Discord category configured");
                return MigrateResult.NO_CATEGORY;
            }
            Category parent = jda.getCategoryById(parentCategoryId);
            if (parent == null) {
                log.warning("migrateToChannel #" + ticketId + ": Discord category " + parentCategoryId + " not visible");
                return MigrateResult.NO_CATEGORY;
            }
            Guild guild = parent.getGuild();

            String channelName = buildChannelName(ticketId,
                    t.discordUsername() == null || t.discordUsername().isBlank()
                            ? t.discordId() : t.discordUsername());
            var action = parent.createTextChannel(channelName).setParent(parent);

            Role everyone = guild.getPublicRole();
            action = action.addPermissionOverride(everyone,
                    EnumSet.noneOf(Permission.class),
                    EnumSet.of(Permission.VIEW_CHANNEL));
            try {
                var member = guild.retrieveMemberById(t.discordId()).complete();
                if (member != null) {
                    action = action.addPermissionOverride(member,
                            EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                    Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES),
                            EnumSet.noneOf(Permission.class));
                }
            } catch (Exception ignored) {}
            try {
                String modRoleId = settingsDao.get().modRoleId();
                if (modRoleId != null && !modRoleId.isBlank()) {
                    Role modRole = guild.getRoleById(modRoleId);
                    if (modRole != null) {
                        action = action.addPermissionOverride(modRole,
                                EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                        Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE,
                                        Permission.MESSAGE_ATTACH_FILES),
                                EnumSet.noneOf(Permission.class));
                    }
                }
            } catch (Exception ignored) {}

            TextChannel created = action.complete();
            Webhook hook;
            try {
                hook = created.createWebhook("Warden Ticket Backfill").complete();
            } catch (Exception e) {
                log.log(Level.WARNING, "migrateToChannel webhook create failed", e);
                created.delete().queue(ok -> {}, err -> {});
                return MigrateResult.FAILED;
            }

            // Wire up the channel + webhook + switch mode before backfill so that
            // any reply landing mid-migration routes through the new channel.
            tickets.setMirror(ticketId, created.getId(), hook.getId(), hook.getToken());
            tickets.setTicketChannel(ticketId, created.getId());
            tickets.setMode(ticketId, TicketsConfig.MODE_CHANNEL);
            Ticket refreshed = tickets.find(ticketId).orElse(t);

            audit.write(AuditService.ACTOR_WEB, "ticket_migrate_to_channel", t.discordId(),
                    java.util.Map.of(
                            "ticketId", ticketId,
                            "channelId", created.getId(),
                            "triggeredBy", triggeredBy == null ? "" : triggeredBy));

            MessageEmbed backfillBanner = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Ticket #" + ticketId + ": " + safe(refreshed.subject()))
                    .setDescription("Migrating from DM. Backfilling conversation history below.")
                    .setColor(new Color(0x5865F2))
                    .setTimestamp(java.time.Instant.now()))
                    .build();
            created.sendMessageEmbeds(backfillBanner).queue(ok -> {}, err -> {});

            backfillMirror(jda, refreshed);

            // Backfill done: drop the webhook and clear the bookkeeping fields
            // so future replies use native channel posts only.
            try { hook.delete().queue(ok -> {}, err -> {}); } catch (Exception ignored) {}
            try { tickets.setMirror(ticketId, "", "", ""); } catch (Exception ignored) {}

            MessageEmbed migratedEmbed = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Ticket #" + ticketId + " migrated from DM")
                    .setDescription("This ticket now lives in this channel. Reply here and staff will see "
                            + "your messages on the dashboard.")
                    .addField("Subject", safe(refreshed.subject()), false)
                    .setColor(new Color(0x5865F2))
                    .setTimestamp(java.time.Instant.now()))
                    .build();
            try {
                created.sendMessage("<@" + t.discordId() + ">")
                        .setEmbeds(migratedEmbed)
                        .setComponents(ActionRow.of(
                                Button.of(ButtonStyle.DANGER, CLOSE_BUTTON_PREFIX + ticketId, "Close ticket")))
                        .queue(ok -> {}, err -> log.warning("ticket migrate post failed: " + err.getMessage()));
            } catch (Exception ignored) {}

            String channelLink = "https://discord.com/channels/" + guild.getId() + "/" + created.getId();
            MessageEmbed dm = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Ticket #" + ticketId + " moved to a channel")
                    .setDescription("Your ticket has moved out of DMs into a private Discord channel. "
                            + "Please continue the conversation there:\n" + channelLink)
                    .addField("Subject", safe(refreshed.subject()), false)
                    .setColor(new Color(0x5865F2))
                    .setTimestamp(java.time.Instant.now()))
                    .build();
            dmUserEmbed(jda, t.discordId(), dm);

            try {
                tickets.appendMessage(ticketId, TicketMessage.KIND_SYSTEM, "", "",
                        "Ticket migrated to channel <#" + created.getId() + "> by "
                                + (triggeredBy == null || triggeredBy.isBlank() ? "staff" : triggeredBy) + ".");
                tickets.touch(ticketId);
            } catch (Exception ignored) {}

            return MigrateResult.OK;
        } catch (Exception e) {
            log.log(Level.WARNING, "migrateToChannel failed", e);
            return MigrateResult.FAILED;
        }
    }

    private void backfillMirror(JDA jda, Ticket t) {
        try {
            String url = WebhookPoster.webhookUrl(t.mirrorWebhookId(), t.mirrorWebhookToken());
            List<TicketMessage> msgs = tickets.messages(t.id());
            String userAvatar = avatarForUser(jda, t.discordId());
            String userName = t.discordUsername() == null || t.discordUsername().isBlank()
                    ? t.discordId() : t.discordUsername();
            for (TicketMessage m : msgs) {
                // Internal notes are staff-only and must never appear in a
                // channel the reporter has read access to.
                if (m.isInternal()) continue;
                String body = m.body() == null ? "" : m.body();
                List<TicketMessage.Attachment> atts = m.attachments();
                StringBuilder content = new StringBuilder(body);
                List<WebhookPoster.FilePart> files = collectBackfillFiles(content, atts);
                String who;
                String avatar;
                switch (m.authorKind()) {
                    case TicketMessage.KIND_STAFF -> {
                        who = (m.authorName() == null || m.authorName().isBlank() ? "Staff" : m.authorName()) + " (staff)";
                        avatar = avatarForUser(jda, m.authorId());
                    }
                    case TicketMessage.KIND_SYSTEM -> {
                        who = "System";
                        avatar = null;
                    }
                    default -> {
                        who = userName;
                        avatar = userAvatar;
                    }
                }
                String text = content.length() == 0 && files.isEmpty() ? "(no text)" : content.toString();
                if (files.isEmpty()) {
                    WebhookPoster.send(url, who, avatar, text, log);
                } else {
                    WebhookPoster.sendWithFiles(url, who, avatar, text, files,
                            BACKFILL_MAX_FILE_BYTES, log);
                }
                try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "mirror backfill failed for #" + t.id(), e);
        }
    }

    /**
     * For each attachment: prefer the locally-stored file (uploaded as a real
     * Discord attachment via multipart), else fall back to inlining the
     * Discord CDN URL so Discord embeds it.
     */
    private List<WebhookPoster.FilePart> collectBackfillFiles(StringBuilder content,
                                                              List<TicketMessage.Attachment> attachments) {
        List<WebhookPoster.FilePart> files = new ArrayList<>();
        if (attachments == null || attachments.isEmpty()) return files;
        for (TicketMessage.Attachment a : attachments) {
            if (a == null) continue;
            String name = a.name() == null || a.name().isBlank() ? "file" : a.name();
            if (a.storedPath() != null && !a.storedPath().isBlank()) {
                Path p = Path.of(a.storedPath());
                if (Files.exists(p)) {
                    files.add(new WebhookPoster.FilePart(name, p));
                    continue;
                }
            }
            if (a.discordUrl() != null && !a.discordUrl().isBlank()) {
                if (content.length() > 0) content.append("\n");
                content.append(a.discordUrl());
            }
        }
        return files;
    }

    private static final long BACKFILL_MAX_FILE_BYTES = 24L * 1024 * 1024;

    /* ------------------------- Staff channel notify ------------------------- */

    private void notifyStaffChannel(JDA jda, long ticketId, TicketsConfig cfg) {
        if (jda == null) return;
        try {
            String chId = cfg.staffChannelId();
            if (chId == null || chId.isBlank()) return;
            TextChannel ch = jda.getTextChannelById(chId);
            if (ch == null) return;

            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return;
            Optional<TicketCategory> cat = t.categoryId() == null
                    ? Optional.empty()
                    : categories.find(t.categoryId());

            EmbedBuilder eb = new EmbedBuilder()
                    .setTitle("New ticket #" + t.id() + ": " + safe(t.subject()))
                    .setColor(new Color(0xE67E22));
            StringBuilder body = new StringBuilder();
            body.append("From: <@").append(t.discordId()).append(">");
            if (t.discordUsername() != null && !t.discordUsername().isBlank()) {
                body.append(" (").append(t.discordUsername()).append(")");
            }
            body.append("\nCategory: ").append(cat.map(TicketCategory::name).orElse("(uncategorised)"));
            body.append("\nMode: ").append(t.mode());
            body.append("\n\n").append(trim(t.body(), 1500));
            eb.setDescription(body.toString());
            eb.setFooter("Manage on the dashboard");
            eb.setTimestamp(java.time.Instant.ofEpochMilli(t.createdAt()));

            ch.sendMessageEmbeds(WardenEmbeds.brand(eb).build()).queue(
                    sent -> {
                        try { tickets.setStaffMessage(t.id(), sent.getChannel().getId(), sent.getId()); }
                        catch (Exception ignored) {}
                    },
                    err -> log.warning("ticket staff notify failed: " + err.getMessage()));
        } catch (Exception e) {
            log.log(Level.WARNING, "notifyStaffChannel failed", e);
        }
    }

    /* ------------------------- Discord send helpers ------------------------- */

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

    private void sendEmbedWithAttachments(MessageChannel ch, MessageEmbed embed,
                                          List<TicketMessage.Attachment> attachments) {
        try {
            MessageCreateBuilder b = new MessageCreateBuilder().setEmbeds(embed);
            if (attachments != null && !attachments.isEmpty()) {
                List<FileUpload> uploads = new ArrayList<>();
                for (TicketMessage.Attachment a : attachments) {
                    FileUpload u = openUpload(a);
                    if (u != null) uploads.add(u);
                }
                if (!uploads.isEmpty()) b.setFiles(uploads);
            }
            ch.sendMessage(b.build()).queue(ok -> {}, err -> {});
        } catch (Exception e) {
            log.log(Level.WARNING, "sendEmbedWithAttachments failed", e);
        }
    }

    private FileUpload openUpload(TicketMessage.Attachment a) {
        if (a == null) return null;
        String name = a.name() == null || a.name().isBlank() ? "file" : a.name();
        try {
            if (a.storedPath() != null && !a.storedPath().isBlank()) {
                Path p = Path.of(a.storedPath());
                if (Files.exists(p)) {
                    return FileUpload.fromData(p, name);
                }
            }
            // No local copy; can't easily re-upload from a Discord URL without an HTTP fetch.
            return null;
        } catch (Exception e) {
            log.log(Level.WARNING, "openUpload failed for " + name, e);
            return null;
        }
    }

    /* ------------------------- Attachment storage ------------------------- */

    /** Save a staff/dashboard upload into the attachments dir; return the metadata to store. */
    public TicketMessage.Attachment storeUploadedFile(long ticketId, String filename,
                                                      InputStream content, long size) throws IOException {
        Path dir = attachmentsDir.resolve(String.valueOf(ticketId));
        Files.createDirectories(dir);
        String safeName = sanitiseFilename(filename);
        Path target = uniquePath(dir, safeName);
        try (InputStream in = content) {
            Files.copy(in, target);
        }
        return new TicketMessage.Attachment(safeName, target.toString(), "", size);
    }

    /** Download a Discord-hosted attachment (from a user DM/channel reply) into the attachments dir. */
    public TicketMessage.Attachment storeDiscordAttachment(long ticketId, String filename,
                                                           String discordUrl, InputStream content,
                                                           long size) throws IOException {
        TicketMessage.Attachment a = storeUploadedFile(ticketId, filename, content, size);
        return new TicketMessage.Attachment(a.name(), a.storedPath(),
                discordUrl == null ? "" : discordUrl, size);
    }

    private static Path uniquePath(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) return candidate;
        String stem = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            stem = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (true) {
            Path c = dir.resolve(stem + "-" + i + ext);
            if (!Files.exists(c)) return c;
            i++;
        }
    }

    private static String sanitiseFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        String trimmed = name.trim();
        // Strip any directory components
        int sep = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (sep >= 0) trimmed = trimmed.substring(sep + 1);
        trimmed = trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
        if (trimmed.length() > 120) {
            int dot = trimmed.lastIndexOf('.');
            String ext = dot > 0 ? trimmed.substring(dot) : "";
            trimmed = trimmed.substring(0, Math.min(100, trimmed.length())) + ext;
        }
        if (trimmed.isBlank()) trimmed = "file";
        return trimmed;
    }

    /* ------------------------- Helpers ------------------------- */

    public static ButtonStyle parseStyle(String s) {
        if (s == null) return ButtonStyle.SECONDARY;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "PRIMARY" -> ButtonStyle.PRIMARY;
            case "SUCCESS" -> ButtonStyle.SUCCESS;
            case "DANGER" -> ButtonStyle.DANGER;
            default -> ButtonStyle.SECONDARY;
        };
    }

    private static Color parseColor(String hex) {
        try {
            String h = hex == null ? "#5865F2" : hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 6) return new Color(Integer.parseInt(h, 16));
        } catch (Exception ignored) {}
        return new Color(0x5865F2);
    }

    private static Color statusColor(TicketStatus s) {
        return switch (s) {
            case OPEN -> new Color(0x5865F2);
            case IN_PROGRESS -> new Color(0xE6A23C);
            case RESOLVED -> new Color(0x3BA55D);
            case CLOSED -> new Color(0x808080);
        };
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
