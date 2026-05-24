package io.warden.reactionroles;

import io.warden.audit.AuditService;
import io.warden.discord.WardenEmbeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the panel message (embed + buttons) for a reaction-role group and
 * applies role changes when a button is clicked. Reactions are not used; we
 * lean on buttons exclusively for reliability and a nicer UI.
 */
public final class ReactionRoleService {

    public static final String BUTTON_PREFIX = "rr:";

    private final ReactionRoleDao dao;
    private final AuditService audit;
    private final Logger log;

    public ReactionRoleService(ReactionRoleDao dao, AuditService audit, Logger log) {
        this.dao = dao;
        this.audit = audit;
        this.log = log;
    }

    public ReactionRoleDao dao() { return dao; }

    /** Publish or refresh the panel message for a group; returns the new message id. */
    public String postOrUpdate(JDA jda, long groupId) {
        try {
            ReactionRoleGroup group = dao.findById(groupId).orElse(null);
            if (group == null) return null;
            TextChannel ch = jda.getTextChannelById(group.channelId());
            if (ch == null) return null;
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(group.title() == null || group.title().isBlank() ? group.name() : group.title())
                    .setDescription(group.description() == null ? "" : group.description())
                    .setColor(parseColor(group.colorHex()));
            for (ReactionRoleOption opt : group.options()) {
                if (opt.description() != null && !opt.description().isBlank()) {
                    embed.addField(opt.label(), opt.description(), false);
                }
            }
            List<LayoutComponent> rows = buildButtons(group);
            if (group.messageId() != null && !group.messageId().isBlank()) {
                ch.editMessageEmbedsById(group.messageId(), WardenEmbeds.brand(embed).build())
                        .setComponents(rows)
                        .queue(ok -> {}, err -> log.warning("rr edit failed: " + err.getMessage()));
                return group.messageId();
            }
            net.dv8tion.jda.api.entities.Message sent = ch.sendMessageEmbeds(WardenEmbeds.brand(embed).build())
                    .setComponents(rows).complete();
            dao.setMessageId(groupId, sent.getId());
            return sent.getId();
        } catch (Exception e) {
            log.log(Level.WARNING, "rr post failed", e);
            return null;
        }
    }

    private static List<LayoutComponent> buildButtons(ReactionRoleGroup group) {
        List<LayoutComponent> rows = new ArrayList<>();
        List<Button> current = new ArrayList<>();
        for (ReactionRoleOption opt : group.options()) {
            Button b = Button.of(ButtonStyle.SECONDARY,
                    BUTTON_PREFIX + group.id() + ":" + opt.id(),
                    opt.label() == null || opt.label().isBlank() ? "Role" : opt.label());
            if (opt.emoji() != null && !opt.emoji().isBlank()) {
                try { b = b.withEmoji(Emoji.fromFormatted(opt.emoji())); }
                catch (Exception ignored) {}
            }
            current.add(b);
            if (current.size() == 5) {
                rows.add(ActionRow.of(current));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) rows.add(ActionRow.of(current));
        return rows;
    }

    /** Process a button click. Returns the user-facing reply text. */
    public String onClick(Member member, long groupId, long optionId) {
        try {
            ReactionRoleGroup group = dao.findById(groupId).orElse(null);
            if (group == null) return "This panel no longer exists.";
            ReactionRoleOption target = null;
            for (ReactionRoleOption o : group.options()) {
                if (o.id() == optionId) { target = o; break; }
            }
            if (target == null) return "That option no longer exists.";

            if (group.requiredRole() != null && !group.requiredRole().isBlank()) {
                boolean has = false;
                for (Role r : member.getRoles()) {
                    if (r.getId().equals(group.requiredRole())) { has = true; break; }
                }
                if (!has) return "You need a specific role to use this panel.";
            }

            Role role = member.getGuild().getRoleById(target.roleId());
            if (role == null) return "Role not found - ask staff to fix this panel.";
            boolean hasRole = member.getRoles().contains(role);
            ReactionRoleGroup.Mode mode = group.parsedMode();

            switch (mode) {
                case VERIFY, BINDING -> {
                    if (hasRole) return "You already have that role.";
                    member.getGuild().addRoleToMember(member, role).queue(ok -> {}, err -> {});
                    return "Added " + role.getName() + ".";
                }
                case REVERSED -> {
                    if (hasRole) {
                        member.getGuild().addRoleToMember(member, role).queue(ok -> {}, err -> {});
                        return "Reversed: kept " + role.getName() + ".";
                    } else {
                        member.getGuild().removeRoleFromMember(member, role).queue(ok -> {}, err -> {});
                        return "Reversed: removed " + role.getName() + ".";
                    }
                }
                case UNIQUE -> {
                    if (hasRole) {
                        member.getGuild().removeRoleFromMember(member, role).queue(ok -> {}, err -> {});
                        return "Removed " + role.getName() + ".";
                    }
                    for (ReactionRoleOption o : group.options()) {
                        if (o.id() == optionId) continue;
                        Role other = member.getGuild().getRoleById(o.roleId());
                        if (other != null && member.getRoles().contains(other)) {
                            member.getGuild().removeRoleFromMember(member, other).queue(ok -> {}, err -> {});
                        }
                    }
                    member.getGuild().addRoleToMember(member, role).queue(ok -> {}, err -> {});
                    return "Switched to " + role.getName() + ".";
                }
                case LIMIT -> {
                    if (hasRole) {
                        member.getGuild().removeRoleFromMember(member, role).queue(ok -> {}, err -> {});
                        return "Removed " + role.getName() + ".";
                    }
                    int held = 0;
                    for (ReactionRoleOption o : group.options()) {
                        Role rOpt = member.getGuild().getRoleById(o.roleId());
                        if (rOpt != null && member.getRoles().contains(rOpt)) held++;
                    }
                    if (group.maxSelections() > 0 && held >= group.maxSelections()) {
                        return "You've reached the limit of " + group.maxSelections()
                                + " role(s) from this panel. Remove one first.";
                    }
                    member.getGuild().addRoleToMember(member, role).queue(ok -> {}, err -> {});
                    return "Added " + role.getName() + ".";
                }
                default -> {
                    if (hasRole) {
                        member.getGuild().removeRoleFromMember(member, role).queue(ok -> {}, err -> {});
                        return "Removed " + role.getName() + ".";
                    }
                    member.getGuild().addRoleToMember(member, role).queue(ok -> {}, err -> {});
                    return "Added " + role.getName() + ".";
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "rr click failed", e);
            return "Something went wrong - try again.";
        }
    }

    private static Color parseColor(String hex) {
        try {
            String h = hex == null ? "#5865F2" : hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 6) {
                return new Color(Integer.parseInt(h, 16));
            }
        } catch (Exception ignored) {}
        return new Color(0x5865F2);
    }
}
