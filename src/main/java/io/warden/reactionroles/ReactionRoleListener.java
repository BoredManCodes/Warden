package io.warden.reactionroles;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

public final class ReactionRoleListener extends ListenerAdapter {

    private final ReactionRoleService service;
    private final Logger log;

    public ReactionRoleListener(ReactionRoleService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith(ReactionRoleService.BUTTON_PREFIX)) return;
        String[] parts = id.substring(ReactionRoleService.BUTTON_PREFIX.length()).split(":");
        if (parts.length != 2) {
            event.reply("Invalid button.").setEphemeral(true).queue();
            return;
        }
        long groupId, optionId;
        try {
            groupId = Long.parseLong(parts[0]);
            optionId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            event.reply("Invalid button.").setEphemeral(true).queue();
            return;
        }
        if (event.getMember() == null) {
            event.reply("Guild only.").setEphemeral(true).queue();
            return;
        }
        String reply = service.onClick(event.getMember(), groupId, optionId);
        event.reply(reply).setEphemeral(true).queue();
    }
}
