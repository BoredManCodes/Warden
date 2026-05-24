package io.warden.alerts;

import io.warden.audit.AuditService;
import io.warden.discord.DiscordService;
import io.warden.discord.WardenEmbeds;
import io.warden.onboarding.Template;
import io.warden.web.handlers.PapiBridge;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.awt.Color;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders alerts (Discord message + console / sudo-player commands) when an
 * event fires. Two entry points:
 * <ul>
 *   <li>{@link #fire(AlertEvent, AlertContext)} - look up enabled alerts for a
 *       built-in event key and dispatch each. Used by the Discord listener and
 *       the server-lifecycle wiring.</li>
 *   <li>{@link #fireAlert(Alert, AlertContext)} - dispatch one specific alert.
 *       Used by {@link AlertManager} after it's already grouped alerts by
 *       Bukkit event class.</li>
 * </ul>
 *
 * Everything is best-effort: a single failed alert never blocks the others or
 * the originating event.
 */
public final class AlertService {

    private final AlertDao dao;
    private final DiscordService discord;
    private final AuditService audit;
    private final Plugin plugin;
    private final Logger log;

    public AlertService(AlertDao dao, DiscordService discord, AuditService audit,
                        Plugin plugin, Logger log) {
        this.dao = dao;
        this.discord = discord;
        this.audit = audit;
        this.plugin = plugin;
        this.log = log;
    }

    public AlertDao dao() { return dao; }

    /** Fire every enabled alert wired to {@code event} via the built-in key path. */
    public void fire(AlertEvent event, AlertContext ctx) {
        if (event == null) return;
        if (ctx == null) ctx = new AlertContext();
        List<Alert> alerts;
        try {
            alerts = dao.listEnabledForEvent(event.key());
        } catch (Exception e) {
            log.log(Level.WARNING, "alerts: failed to load alerts for event " + event.key(), e);
            return;
        }
        if (alerts.isEmpty()) return;
        for (Alert a : alerts) {
            // Custom-trigger alerts dispatch via AlertManager; skip them on the built-in path.
            if (a.isCustomTrigger()) continue;
            fireAlert(a, ctx);
        }
    }

    /** Public test-fire used by the dashboard's "Test" button. */
    public void testFire(Alert alert, AlertContext ctx) {
        fireAlert(alert, ctx == null ? new AlertContext() : ctx);
    }

    /** Dispatch a single alert. Conditions are evaluated before any side effects. */
    public void fireAlert(Alert a, AlertContext ctx) {
        if (a == null) return;
        if (ctx == null) ctx = new AlertContext();
        try {
            if (!conditionsPass(a, ctx)) return;
            dispatch(a, ctx);
        } catch (Exception e) {
            log.log(Level.WARNING, "alerts: dispatch failed for alert #" + a.id() + " (" + a.name() + ")", e);
        }
    }

    private boolean conditionsPass(Alert a, AlertContext ctx) {
        String raw = a.conditions();
        if (raw == null || raw.isBlank()) return true;
        StandardEvaluationContext spel = buildSpelContext(ctx);
        for (String line : raw.split("\\r?\\n")) {
            String c = line == null ? "" : line.trim();
            if (c.isEmpty() || c.startsWith("#")) continue;
            if (!SpelTemplate.evaluateCondition(c, spel)) {
                return false;
            }
        }
        return true;
    }

    private void dispatch(Alert a, AlertContext ctx) {
        OfflinePlayer player = resolvePlayer(a, ctx);
        if (a.channelId() != null && !a.channelId().isBlank()
                && (hasContent(a.messageContent()) || a.embedEnabled())) {
            postDiscord(a, ctx, player);
        }
        if (hasContent(a.consoleCommands()) || hasContent(a.asPlayerCommands())) {
            if (Bukkit.isPrimaryThread()) {
                runCommands(a, ctx, player);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> runCommands(a, ctx, player));
            }
        }
    }

    private OfflinePlayer resolvePlayer(Alert a, AlertContext ctx) {
        if (ctx.player() != null) return ctx.player();
        if (a.papiPlayerUuid() != null && !a.papiPlayerUuid().isBlank()) {
            try {
                return Bukkit.getOfflinePlayer(UUID.fromString(a.papiPlayerUuid().trim()));
            } catch (Exception ignored) {
                // fall through to null
            }
        }
        return null;
    }

    private void postDiscord(Alert a, AlertContext ctx, OfflinePlayer player) {
        if (discord == null) return;
        JDA jda = discord.jda();
        if (jda == null) {
            log.fine("alerts: skipping Discord post for #" + a.id() + " - JDA not ready");
            return;
        }
        TextChannel ch = jda.getTextChannelById(a.channelId());
        if (ch == null) {
            log.warning("alerts: alert #" + a.id() + " (" + a.name()
                    + ") targets channel " + a.channelId() + " which the bot cannot see");
            return;
        }
        StandardEvaluationContext spel = buildSpelContext(ctx);
        MessageCreateBuilder mb = new MessageCreateBuilder();
        if (hasContent(a.messageContent())) {
            mb.setContent(renderTrim(a, a.messageContent(), ctx, player, spel, 2000));
        }
        if (a.embedEnabled()) {
            MessageEmbed embed = buildEmbed(a, ctx, player, spel);
            if (embed != null) mb.setEmbeds(embed);
        }
        if (mb.isEmpty()) return;
        try {
            ch.sendMessage(mb.build()).queue(ok -> {}, err -> {
                log.warning("alerts: Discord send failed for #" + a.id() + ": " + err.getMessage());
            });
        } catch (Exception e) {
            log.log(Level.WARNING, "alerts: build message failed for #" + a.id(), e);
        }
    }

    private MessageEmbed buildEmbed(Alert a, AlertContext ctx, OfflinePlayer player,
                                    StandardEvaluationContext spel) {
        EmbedBuilder eb = new EmbedBuilder();
        String title = renderTrim(a, a.embedTitle(), ctx, player, spel, 256);
        if (!title.isBlank()) eb.setTitle(title);
        String desc = renderTrim(a, a.embedDescription(), ctx, player, spel, 4096);
        if (!desc.isBlank()) eb.setDescription(desc);
        eb.setColor(parseColor(a.embedColorHex()));
        String thumb = render(a, a.embedThumbnail(), ctx, player, spel);
        if (isUrl(thumb)) eb.setThumbnail(thumb);
        String image = render(a, a.embedImage(), ctx, player, spel);
        if (isUrl(image)) eb.setImage(image);
        String footer = renderTrim(a, a.embedFooter(), ctx, player, spel, 2048);
        if (!footer.isBlank()) eb.setFooter(footer);
        String authorName = renderTrim(a, a.embedAuthorName(), ctx, player, spel, 256);
        if (!authorName.isBlank()) {
            String authorIcon = render(a, a.embedAuthorIconUrl(), ctx, player, spel);
            eb.setAuthor(authorName, null, isUrl(authorIcon) ? authorIcon : null);
        }
        for (AlertEmbedField f : a.embedFields()) {
            String fName = renderTrim(a, f.name(), ctx, player, spel, 256);
            String fValue = renderTrim(a, f.value(), ctx, player, spel, 1024);
            if (fName.isBlank() && fValue.isBlank()) continue;
            if (fName.isBlank()) fName = "​";
            if (fValue.isBlank()) fValue = "​";
            eb.addField(fName, fValue, f.inline());
        }
        if (eb.isEmpty()) return null;
        return WardenEmbeds.brand(eb).build();
    }

    private void runCommands(Alert a, AlertContext ctx, OfflinePlayer player) {
        StandardEvaluationContext spel = buildSpelContext(ctx);
        for (String raw : splitLines(a.consoleCommands())) {
            String resolved = render(a, raw, ctx, player, spel);
            if (resolved.isBlank()) continue;
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            } catch (Exception e) {
                log.log(Level.WARNING, "alerts: console command failed (" + resolved + ")", e);
            }
        }
        if (hasContent(a.asPlayerCommands())) {
            Player online = (player != null && player.getUniqueId() != null)
                    ? Bukkit.getPlayer(player.getUniqueId()) : null;
            if (online == null) {
                log.fine("alerts: as-player commands skipped for #" + a.id()
                        + " - no online player attached");
            } else {
                for (String raw : splitLines(a.asPlayerCommands())) {
                    String resolved = render(a, raw, ctx, player, spel);
                    if (resolved.isBlank()) continue;
                    try {
                        Bukkit.dispatchCommand(online, resolved);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "alerts: as-player command failed (" + resolved + ")", e);
                    }
                }
            }
        }
    }

    private static String[] splitLines(String s) {
        if (s == null || s.isBlank()) return new String[0];
        String[] parts = s.split("\\r?\\n");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.startsWith("/")) p = p.substring(1);
            parts[i] = p;
        }
        return parts;
    }

    private static String render(Alert a, String template, AlertContext ctx,
                                 OfflinePlayer player, StandardEvaluationContext spel) {
        if (template == null || template.isEmpty()) return "";
        String afterVars = a.expressionsEnabled()
                ? SpelTemplate.render(template, spel)
                : Template.render(template, ctx.vars());
        if (afterVars == null || afterVars.isEmpty()) return "";
        String afterPapi = PapiBridge.resolve(afterVars, player);
        return afterPapi == null ? afterVars : afterPapi;
    }

    private static String renderTrim(Alert a, String template, AlertContext ctx,
                                     OfflinePlayer player, StandardEvaluationContext spel, int max) {
        String out = render(a, template, ctx, player, spel);
        if (out.length() > max) out = out.substring(0, max);
        return out;
    }

    private static StandardEvaluationContext buildSpelContext(AlertContext ctx) {
        StandardEvaluationContext spel = new StandardEvaluationContext(ctx.toSpelRoot());
        // Expose every {var} entry as a SpEL variable too (#player, #world, ...),
        // so DSRV-imported templates that mix {event.player.name} with the
        // simpler #player style both work in expressions mode.
        for (var e : ctx.vars().entrySet()) {
            spel.setVariable(e.getKey(), e.getValue());
        }
        return spel;
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isUrl(String s) {
        if (s == null || s.isBlank()) return false;
        String low = s.toLowerCase(java.util.Locale.ROOT);
        return low.startsWith("http://") || low.startsWith("https://");
    }

    private static Color parseColor(String hex) {
        try {
            String h = hex == null ? "#5865F2" : hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 6) return new Color(Integer.parseInt(h, 16));
        } catch (Exception ignored) {}
        return new Color(0x5865F2);
    }
}
