package io.warden.discord.listeners;

import io.warden.audit.AuditService;
import io.warden.config.WardenConfig;
import io.warden.discord.WardenEmbeds;
import io.warden.onboarding.LinkCodeService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches DMs to the bot for an 8-character link code (the one the user got
 * on /onboard) and pairs the guest browser session to the DMing Discord
 * account by claiming the code.
 *
 * Reply behaviour:
 *  - Claimed cleanly: success embed with a button back to /onboard.
 *  - Not a recognised code: stay quiet (we don't want to spam reactions to
 *    every casual DM the user sends the bot).
 *  - Code expired or already used: short DM saying so.
 */
public final class OnboardDmListener extends ListenerAdapter {

    /** Same 32-char alphabet the DAO uses (omits I, O, 0, 1). */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("\\b([A-HJ-NP-Z2-9]{8})\\b", Pattern.CASE_INSENSITIVE);

    private final WardenConfig config;
    private final LinkCodeService linkCodes;
    private final AuditService audit;
    private final Logger log;

    public OnboardDmListener(WardenConfig config, LinkCodeService linkCodes,
                             AuditService audit, Logger log) {
        this.config = config;
        this.linkCodes = linkCodes;
        this.audit = audit;
        this.log = log;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getChannelType() != ChannelType.PRIVATE) return;
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) return;

        String body = event.getMessage().getContentRaw();
        if (body == null || body.isBlank()) return;

        Matcher m = CODE_PATTERN.matcher(body);
        if (!m.find()) return;

        String code = m.group(1).toUpperCase();
        String discordId = event.getAuthor().getId();

        Optional<String> webSessionId;
        try {
            webSessionId = linkCodes.claim(code, discordId);
        } catch (Exception e) {
            log.log(Level.WARNING, "claim failed for code from " + discordId + ": " + e.getMessage(), e);
            MessageEmbed err = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Something went wrong")
                    .setDescription("Try again in a moment.")
                    .setColor(new Color(0xE53935)))
                    .build();
            event.getChannel().sendMessageEmbeds(err).queue();
            return;
        }

        if (webSessionId.isEmpty()) {
            audit.write(AuditService.ACTOR_BOT, "link_code_claim_miss", discordId,
                    Map.of("code", code));
            MessageEmbed miss = WardenEmbeds.brand(new EmbedBuilder()
                    .setTitle("Code didn't work")
                    .setDescription("It may have expired, already been used, or been mistyped. "
                            + "Pop back to the onboarding page and grab a fresh one.")
                    .setColor(new Color(0xE6A23C)))
                    .build();
            event.getChannel().sendMessageEmbeds(miss).queue();
            return;
        }

        audit.write(AuditService.ACTOR_BOT, "link_code_claimed", discordId,
                Map.of("code", code));

        String onboardUrl = config.webPublicUrl() + "/onboard";
        MessageEmbed embed = WardenEmbeds.brand(new EmbedBuilder()
                .setTitle("Linked!")
                .setDescription("Head back to the onboarding tab in your browser - it'll continue automatically. "
                        + "If you've closed it, the button below will bring you back to your spot.")
                .setColor(new Color(0x6B, 0x83, 0xFF)))
                .build();

        event.getChannel().sendMessage(new MessageCreateBuilder()
                .setEmbeds(embed)
                .setComponents(ActionRow.of(Button.link(onboardUrl, "Open onboarding")))
                .build()).queue();
    }
}
