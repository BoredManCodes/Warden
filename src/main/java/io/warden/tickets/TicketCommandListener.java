package io.warden.tickets;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
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

public final class TicketCommandListener extends ListenerAdapter {

    private final TicketService service;
    private final String guildId;
    private final Logger log;

    public TicketCommandListener(TicketService service, String guildId, Logger log) {
        this.service = service;
        this.guildId = guildId;
        this.log = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) return;
        List<SlashCommandData> cmds = List.of(
                Commands.slash("ticket-panel", "Post or refresh the tickets panel in this channel")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addOptions(
                                new OptionData(OptionType.STRING, "title", "Panel title", false),
                                new OptionData(OptionType.STRING, "description", "Panel description", false))
        );
        guild.updateCommands().addCommands(cmds).queue(
                ok -> log.info("Ticket commands registered (" + cmds.size() + ")"),
                err -> log.warning("Ticket command registration failed: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!"ticket-panel".equals(event.getName())) return;
        if (event.getGuild() == null || !(event.getChannel() instanceof TextChannel ch)) {
            event.reply("Use this in a guild text channel.").setEphemeral(true).queue();
            return;
        }
        String title = opt(event, "title", null);
        String description = opt(event, "description", null);
        try {
            long id = service.panels().create(
                    ch.getId(),
                    title == null ? "Open a ticket" : title,
                    description == null
                            ? "Pick a category below to open a ticket. Staff will reply here in Discord and on the dashboard."
                            : description,
                    "#5865F2");
            if (id < 0) {
                event.reply("Failed to create panel.").setEphemeral(true).queue();
                return;
            }
            event.deferReply(true).queue();
            String msgId = service.postOrUpdatePanel(event.getJDA(), id);
            if (msgId == null) {
                event.getHook().sendMessage("Panel saved (#" + id + ") but posting to Discord failed. Check bot permissions and try again from the dashboard.")
                        .setEphemeral(true).queue();
            } else {
                event.getHook().sendMessage("Panel posted (#" + id + ", message " + msgId + "). Manage it on the dashboard at /dash/tickets/panels.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception e) {
            log.warning("/ticket-panel failed: " + e.getMessage());
            if (!event.isAcknowledged()) {
                event.reply("Failed: " + e.getMessage()).setEphemeral(true).queue();
            }
        }
    }

    private static String opt(SlashCommandInteractionEvent e, String key, String fallback) {
        OptionMapping m = e.getOption(key);
        return m == null ? fallback : m.getAsString();
    }
}
