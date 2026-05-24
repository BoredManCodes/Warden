package io.warden.feedback;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Logger;

public final class FeedbackCommandListener extends ListenerAdapter {

    private final FeedbackService service;
    private final String guildId;
    private final Logger log;

    public FeedbackCommandListener(FeedbackService service, String guildId, Logger log) {
        this.service = service;
        this.guildId = guildId;
        this.log = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) return;
        List<SlashCommandData> cmds = List.of(
                Commands.slash("feedback", "Submit feedback or a suggestion")
                        .addOptions(
                                new OptionData(OptionType.STRING, "title",
                                        "Short title (optional - opens a fuller form if omitted)", false))
        );
        guild.updateCommands().addCommands(cmds).queue(
                ok -> log.info("Feedback commands registered (" + cmds.size() + ")"),
                err -> log.warning("Feedback command registration failed: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!"feedback".equals(event.getName())) return;
        try {
            FeedbackConfig cfg = service.config().get();
            if (!cfg.openViaCommand()) {
                event.reply("The /feedback command is currently disabled.").setEphemeral(true).queue();
                return;
            }
            if (cfg.channelId() == null || cfg.channelId().isBlank()) {
                event.reply("The feedback channel hasn't been configured. Ask a mod.").setEphemeral(true).queue();
                return;
            }
            OptionMapping titleOpt = event.getOption("title");
            String prefill = titleOpt == null ? "" : titleOpt.getAsString();

            TextInput title = TextInput.create(FeedbackListener.MODAL_TITLE, "Title", TextInputStyle.SHORT)
                    .setRequired(true)
                    .setMinLength(3)
                    .setMaxLength(150)
                    .setValue(prefill.isBlank() ? null : prefill)
                    .setPlaceholder("One-line summary of your idea or feedback")
                    .build();
            TextInput body = TextInput.create(FeedbackListener.MODAL_BODY, "Details", TextInputStyle.PARAGRAPH)
                    .setRequired(true)
                    .setMinLength(5)
                    .setMaxLength(3500)
                    .setPlaceholder("Explain it in detail. The community will vote on it.")
                    .build();
            Modal modal = Modal.create(FeedbackListener.MODAL_ID, "Submit feedback")
                    .addActionRow(title)
                    .addActionRow(body)
                    .build();
            event.replyModal(modal).queue();
        } catch (Exception e) {
            log.warning("/feedback failed: " + e.getMessage());
            if (!event.isAcknowledged()) {
                event.reply("Failed: " + e.getMessage()).setEphemeral(true).queue();
            }
        }
    }
}
