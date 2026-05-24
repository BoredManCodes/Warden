package io.warden.discord.listeners;

import io.warden.Services;
import io.warden.WardenPlugin;
import io.warden.debug.DebugService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discord slash command: /warden-debug
 *
 * Only the guild owner or a member with the Config Admin role may invoke it.
 * Replies ephemerally so the share URL (which contains the decryption key) is
 * not visible to other channel members.
 */
public final class DebugCommandListener extends ListenerAdapter {

    private final Services     services;
    private final DebugService debugService;
    private final String       guildId;
    private final Logger       log;

    public DebugCommandListener(WardenPlugin plugin, Services services, String guildId, Logger log) {
        this.services     = services;
        this.debugService = new DebugService(plugin, services, services.debugReportDao);
        this.guildId      = guildId;
        this.log          = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) return;
        guild.upsertCommand(
                Commands.slash("warden-debug", "Generate an encrypted Warden debug report")
                        .addOption(OptionType.STRING, "label", "Optional label for this report (e.g. 'post-restart')", false)
        ).queue(
                ok  -> {},
                err -> log.warning("[debug] /warden-debug registration failed: " + err.getMessage())
        );
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!"warden-debug".equals(event.getName())) return;

        Member member = event.getMember();
        Guild  guild  = event.getGuild();
        if (member == null || guild == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        if (!isAuthorized(member, guild)) {
            event.reply("❌ You need the **Config Admin** role or server ownership to generate debug reports.")
                    .setEphemeral(true).queue();
            return;
        }

        // Defer immediately - generation involves a DB write and may take a moment.
        event.deferReply(true).queue();

        String label = event.getOption("label") != null
                ? event.getOption("label").getAsString()
                : "";

        services.bgExecutor.submit(() -> {
            try {
                var result = debugService.generate(label.isBlank() ? null : label);
                String url  = debugService.viewerUrl(result.id(), result.keyB64Url());

                boolean llmConfigured = false;
                try {
                    var s = services.settingsDao.get();
                    llmConfigured = s.llmApiKey() != null && !s.llmApiKey().isBlank();
                } catch (Exception ignored) {}

                MessageEmbed embed = new EmbedBuilder()
                        .setTitle("Debug Report Generated", null)
                        .setColor(new Color(0x5865F2))
                        .setDescription(
                                "Your encrypted debug report is ready.\n"
                                + "The link below is the **only copy** of the decryption key. Keep it safe.")
                        .addField("Report ID", "`" + result.id() + "`", false)
                        .addField("Share URL", url, false)
                        .addField("AI Analysis",
                                llmConfigured
                                        ? "Running in the background - refresh the viewer in a few seconds."
                                        : "Skipped (no LLM API key configured).",
                                false)
                        .setFooter("Only you can see this message • Warden Debug")
                        .build();

                event.getHook().sendMessageEmbeds(embed).queue();
            } catch (Exception e) {
                log.log(Level.WARNING, "[debug] Failed to generate debug report via Discord command", e);
                event.getHook().sendMessage("❌ Failed to generate report: " + e.getMessage()).queue();
            }
        });
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    private boolean isAuthorized(Member member, Guild guild) {
        // Guild owner always has access.
        Member owner = guild.getOwner();
        if (owner != null && owner.getIdLong() == member.getIdLong()) return true;

        // Config admin role from Warden settings.
        try {
            String roleId = services.settingsDao.get().configAdminRoleId();
            if (roleId != null && !roleId.isBlank()) {
                return member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId));
            }
        } catch (Exception ignored) {}

        return false;
    }
}
