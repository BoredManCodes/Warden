package io.warden.discord.listeners;

import io.warden.config.WardenConfig;
import io.warden.data.dao.SettingsDao;
import io.warden.onboarding.DecisionService;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles mod-side button clicks on the application-review embed posted by
 * JdaDecisionExecutor.postModReviewEmbed.
 *
 * Custom-ids:
 *   mod:approve:&lt;applicationId&gt;     - single-click approve
 *   mod:deny:&lt;applicationId&gt;        - opens a modal asking for an optional reason
 *   mod:denyform:&lt;applicationId&gt;    - modal submission containing the reason
 */
public final class ModButtonListener extends ListenerAdapter {

    private static final String DENY_REASON_INPUT_ID = "reason";
    private static final String DENY_USER_DM_INPUT_ID = "user_dm";

    private final WardenConfig config;
    private final SettingsDao settingsDao;
    private final DecisionService decisions;
    private final Logger log;

    public ModButtonListener(
            WardenConfig config,
            SettingsDao settingsDao,
            DecisionService decisions,
            Logger log
    ) {
        this.config = config;
        this.settingsDao = settingsDao;
        this.decisions = decisions;
        this.log = log;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("mod:")) return;
        if (event.getGuild() == null) return; // mod actions only valid in-guild

        if (!isModerator(event)) {
            event.reply("You don't have permission to review applications.").setEphemeral(true).queue();
            return;
        }

        String[] parts = id.split(":", -1);
        if (parts.length < 3) {
            event.reply("Malformed mod-action button.").setEphemeral(true).queue();
            return;
        }
        long appId;
        try {
            appId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            event.reply("Malformed application id.").setEphemeral(true).queue();
            return;
        }
        String modId = event.getUser().getId();

        switch (parts[1]) {
            case "approve" -> {
                event.deferReply(true).queue();
                try {
                    decisions.applyManualApprove(appId, modId, "");
                    event.getHook().sendMessage("Approved application " + appId + ".").queue();
                } catch (Exception e) {
                    log.log(Level.WARNING, "manual approve failed for app " + appId, e);
                    event.getHook().sendMessage("Approve failed: " + e.getMessage()).queue();
                }
            }
            case "deny" -> {
                TextInput reason = TextInput.create(DENY_REASON_INPUT_ID, "Internal reason (optional)", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Visible in the audit log and mod review embed. Not shown to the user.")
                        .setRequired(false)
                        .setMaxLength(1000)
                        .build();
                TextInput userDm = TextInput.create(DENY_USER_DM_INPUT_ID, "Message to user / DM (optional)", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Sent to the user as a DM. Overrides the default deny DM template.")
                        .setRequired(false)
                        .setMaxLength(1000)
                        .build();
                Modal modal = Modal.create("mod:denyform:" + appId, "Deny application " + appId)
                        .addComponents(ActionRow.of(reason), ActionRow.of(userDm))
                        .build();
                event.replyModal(modal).queue();
            }
            default -> {
                // Unknown mod subcommand - ignore.
            }
        }
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id == null || !id.startsWith("mod:denyform:")) return;
        if (event.getGuild() == null) return;

        if (!isModerator(event)) {
            event.reply("You don't have permission to review applications.").setEphemeral(true).queue();
            return;
        }

        long appId;
        try {
            appId = Long.parseLong(id.substring("mod:denyform:".length()));
        } catch (NumberFormatException e) {
            event.reply("Malformed modal id.").setEphemeral(true).queue();
            return;
        }
        var input = event.getValue(DENY_REASON_INPUT_ID);
        String reason = input == null ? "" : input.getAsString();
        var dmInput = event.getValue(DENY_USER_DM_INPUT_ID);
        String userDm = dmInput == null ? null : dmInput.getAsString();

        event.deferReply(true).queue();
        try {
            decisions.applyManualDeny(appId, event.getUser().getId(), reason, userDm);
            event.getHook().sendMessage("Denied application " + appId + ".").queue();
        } catch (Exception e) {
            log.log(Level.WARNING, "manual deny failed for app " + appId, e);
            event.getHook().sendMessage("Deny failed: " + e.getMessage()).queue();
        }
    }

    /**
     * Mod gate: settings.mod_role_id ?? config.bootstrap_mod_role_id.
     * Owner/admin in the guild also counts (Discord ADMINISTRATOR permission).
     */
    private boolean isModerator(net.dv8tion.jda.api.interactions.Interaction event) {
        var member = event.getMember();
        if (member == null) return false;
        if (member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) return true;
        String modRoleId = "";
        try {
            modRoleId = settingsDao.get().modRoleId();
        } catch (Exception ignored) {}
        if (modRoleId == null || modRoleId.isBlank()) modRoleId = config.bootstrapModRoleId();
        if (modRoleId == null || modRoleId.isBlank()) return false;
        final String roleId = modRoleId;
        return member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId));
    }
}
