package io.warden.autoresponder;

import io.warden.discord.WardenEmbeds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches guild messages, runs them through {@link AutoresponderService}, and
 * dispatches whichever rule fires. Responses can be plain content, branded
 * embeds, or content + embed with extra images.
 */
public final class AutoresponderListener extends ListenerAdapter {

    private final AutoresponderService service;
    private final Logger log;

    public AutoresponderListener(AutoresponderService service, Logger log) {
        this.service = service;
        this.log = log;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        AutoresponderService.Match match;
        try {
            var result = service.evaluate(event.getMessage());
            if (result.isEmpty()) return;
            match = result.get();
        } catch (Exception e) {
            log.log(Level.WARNING, "autoresponder evaluate failed", e);
            return;
        }

        Autoresponder rule = match.rule();
        Message trigger = event.getMessage();

        if (rule.deleteTrigger()) {
            try { trigger.delete().queue(ok -> {}, err -> {}); }
            catch (Exception ignored) {}
        }

        MessageCreateData data;
        try { data = build(rule, match, trigger); }
        catch (Exception e) {
            log.log(Level.WARNING, "autoresponder build failed for rule " + rule.id(), e);
            return;
        }
        if (data == null) return;

        try {
            if (rule.replyToTrigger() && !rule.deleteTrigger()) {
                trigger.reply(data)
                        .mentionRepliedUser(rule.mentionAuthor())
                        .queue(ok -> {}, err -> log.log(Level.FINE,
                                "autoresponder send failed: " + err.getMessage()));
            } else {
                event.getChannel().sendMessage(data)
                        .queue(ok -> {}, err -> log.log(Level.FINE,
                                "autoresponder send failed: " + err.getMessage()));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "autoresponder send failed", e);
        }
    }

    private MessageCreateData build(Autoresponder rule, AutoresponderService.Match match, Message trigger) {
        MessageCreateBuilder b = new MessageCreateBuilder();
        String renderedContent = service.render(rule.content(), match, trigger);
        if (rule.isEmbed()) {
            EmbedBuilder eb = new EmbedBuilder();
            String title = service.render(rule.embedTitle(), match, trigger);
            String desc = service.render(rule.embedDescription(), match, trigger);
            if (title != null && !title.isBlank()) eb.setTitle(truncate(title, MessageEmbed.TITLE_MAX_LENGTH));
            if (desc != null && !desc.isBlank()) eb.setDescription(truncate(desc, MessageEmbed.DESCRIPTION_MAX_LENGTH));
            Color color = parseColor(rule.embedColor());
            if (color != null) eb.setColor(color);
            String img = service.render(rule.embedImageUrl(), match, trigger);
            if (img != null && !img.isBlank()) trySetImage(eb, img);
            String thumb = service.render(rule.embedThumbnailUrl(), match, trigger);
            if (thumb != null && !thumb.isBlank()) trySetThumbnail(eb, thumb);
            String authorName = service.render(rule.embedAuthorName(), match, trigger);
            String authorIcon = service.render(rule.embedAuthorIcon(), match, trigger);
            if (authorName != null && !authorName.isBlank()) {
                trySetAuthor(eb, truncate(authorName, MessageEmbed.AUTHOR_MAX_LENGTH), null, authorIcon);
            }
            String footerText = service.render(rule.embedFooterText(), match, trigger);
            String footerIcon = service.render(rule.embedFooterIcon(), match, trigger);
            if (footerText != null && !footerText.isBlank()) {
                trySetFooter(eb, truncate(footerText, MessageEmbed.TEXT_MAX_LENGTH), footerIcon);
            }
            List<MessageEmbed> embeds = new ArrayList<>();
            embeds.add(WardenEmbeds.brand(eb).build());
            for (String extra : service.renderExtraImages(rule, match, trigger)) {
                if (embeds.size() >= 10) break;
                EmbedBuilder x = new EmbedBuilder().setColor(color);
                if (trySetImage(x, extra)) embeds.add(x.build());
            }
            b.setEmbeds(embeds);
            if (renderedContent != null && !renderedContent.isBlank()) {
                b.setContent(truncate(renderedContent, Message.MAX_CONTENT_LENGTH));
            }
        } else {
            String body = renderedContent;
            List<String> imgs = service.renderExtraImages(rule, match, trigger);
            if (!imgs.isEmpty()) {
                StringBuilder sb = new StringBuilder(body == null ? "" : body);
                for (String url : imgs) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(url);
                }
                body = sb.toString();
            }
            if (body == null || body.isBlank()) return null;
            b.setContent(truncate(body, Message.MAX_CONTENT_LENGTH));
        }

        if (b.isEmpty()) return null;
        return b.build();
    }

    private static boolean trySetImage(EmbedBuilder eb, String url) {
        try { eb.setImage(url); return true; }
        catch (Exception e) { return false; }
    }

    private static void trySetThumbnail(EmbedBuilder eb, String url) {
        try { eb.setThumbnail(url); } catch (Exception ignored) {}
    }

    private static void trySetAuthor(EmbedBuilder eb, String name, String url, String iconUrl) {
        try {
            String icon = (iconUrl == null || iconUrl.isBlank()) ? null : iconUrl;
            eb.setAuthor(name, url, icon);
        } catch (Exception ignored) {
            try { eb.setAuthor(name); } catch (Exception ignored2) {}
        }
    }

    private static void trySetFooter(EmbedBuilder eb, String text, String iconUrl) {
        try {
            String icon = (iconUrl == null || iconUrl.isBlank()) ? null : iconUrl;
            eb.setFooter(text, icon);
        } catch (Exception ignored) {
            try { eb.setFooter(text); } catch (Exception ignored2) {}
        }
    }

    private static Color parseColor(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.isEmpty()) return null;
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6 && h.length() != 8) return null;
        try {
            int rgb = (int) Long.parseLong(h, 16);
            return new Color(rgb & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1));
    }
}
