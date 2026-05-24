package io.warden.web.handlers;

import io.warden.discord.DiscordService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Small helper that exposes the current guild's roles/channels for picker dropdowns. */
public final class GuildLookup {

    public record Option(String id, String name) {}

    /** A server custom emoji entry for the emoji picker. */
    public record EmojiOption(String id, String name, boolean animated, String imageUrl, String mention) {}

    private final DiscordService discord;
    private final String guildId;

    public GuildLookup(DiscordService discord, String guildId) {
        this.discord = discord;
        this.guildId = guildId;
    }

    public List<Option> roles() {
        return guild().map(g -> g.getRoles().stream()
                        .filter(r -> !r.isPublicRole()) // skip @everyone
                        .sorted(Comparator.comparing(r -> r.getName().toLowerCase()))
                        .map(r -> new Option(r.getId(), r.getName()))
                        .toList())
                .orElse(List.of());
    }

    public List<Option> textChannels() {
        return guild().map(g -> g.getChannels().stream()
                        .filter(c -> c instanceof TextChannel)
                        .sorted(Comparator.comparing(c -> c.getName().toLowerCase()))
                        .map(c -> new Option(c.getId(), "#" + c.getName()))
                        .toList())
                .orElse(List.of());
    }

    /** Server custom emojis, sorted by name. Animated emojis use the {@code <a:name:id>} mention form. */
    public List<EmojiOption> customEmojis() {
        return guild().map(g -> g.getEmojis().stream()
                        .sorted(Comparator.comparing(e -> e.getName().toLowerCase()))
                        .map(GuildLookup::toEmojiOption)
                        .toList())
                .orElse(List.of());
    }

    private static EmojiOption toEmojiOption(RichCustomEmoji e) {
        String mention = (e.isAnimated() ? "<a:" : "<:") + e.getName() + ":" + e.getId() + ">";
        return new EmojiOption(e.getId(), e.getName(), e.isAnimated(), e.getImageUrl(), mention);
    }

    /** Discord category channels (the containers under which text channels live). */
    public List<Option> channelCategories() {
        return guild().map(g -> g.getCategories().stream()
                        .sorted(Comparator.comparing(c -> c.getName().toLowerCase()))
                        .map(c -> new Option(c.getId(), c.getName()))
                        .toList())
                .orElse(List.of());
    }

    public boolean discordConnected() {
        return guild().isPresent();
    }

    public String guildId() { return guildId; }

    private Optional<Guild> guild() {
        JDA jda = discord.jda();
        if (jda == null) return Optional.empty();
        return Optional.ofNullable(jda.getGuildById(guildId));
    }

    /** Friendly "#name" for a Discord channel id, or empty if the bot can't see it. */
    public Optional<String> channelName(String channelId) {
        if (channelId == null || channelId.isBlank()) return Optional.empty();
        JDA jda = discord.jda();
        if (jda == null) return Optional.empty();
        try {
            GuildChannel ch = jda.getGuildChannelById(channelId);
            if (ch == null) return Optional.empty();
            return Optional.of("#" + ch.getName());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** Friendly display name for a Discord user id (guild member preferred), or empty. */
    public Optional<String> userName(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        JDA jda = discord.jda();
        if (jda == null) return Optional.empty();
        try {
            Guild g = jda.getGuildById(guildId);
            if (g != null) {
                Member m = g.getMemberById(userId);
                if (m != null) {
                    String global = m.getUser().getGlobalName();
                    if (global != null && !global.isBlank()) return Optional.of(global);
                    return Optional.of(m.getEffectiveName());
                }
            }
            User u = jda.getUserById(userId);
            if (u != null) {
                String global = u.getGlobalName();
                if (global != null && !global.isBlank()) return Optional.of(global);
                return Optional.of(u.getName());
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /** Friendly role name for a Discord role id, or empty. */
    public Optional<String> roleName(String roleId) {
        if (roleId == null || roleId.isBlank()) return Optional.empty();
        JDA jda = discord.jda();
        if (jda == null) return Optional.empty();
        try {
            Role r = jda.getRoleById(roleId);
            if (r != null) return Optional.of(r.getName());
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    public static List<Option> withDefaults(List<Option> opts, String currentlySelectedId) {
        if (currentlySelectedId != null && !currentlySelectedId.isBlank()
                && opts.stream().noneMatch(o -> o.id().equals(currentlySelectedId))) {
            List<Option> out = new ArrayList<>(opts.size() + 1);
            out.add(new Option(currentlySelectedId, "(currently set; not visible to bot: " + currentlySelectedId + ")"));
            out.addAll(opts);
            return out;
        }
        return opts;
    }

    /* ------------------------- Shared form-builders ------------------------- */

    /**
     * Render a Bootstrap &lt;select&gt; bound to a guild role or channel option list.
     * Includes a blank "(unset)" option. The caller still has to escape its
     * surrounding markup; {@code label} and {@code tip} are escaped here.
     */
    public static String selectField(String name, String label,
                                     List<Option> opts, String selected, String tip) {
        StringBuilder s = new StringBuilder();
        s.append("<label class=form-label for=\"f_").append(name).append("\">")
                .append(Layout.escape(label)).append(Layout.infoIcon(tip)).append("</label>");
        s.append("<select class=form-select id=\"f_").append(name)
                .append("\" name=\"").append(name).append("\">");
        s.append("<option value=\"\">(unset)</option>");
        boolean foundCurrent = false;
        for (Option o : opts) {
            if (selected != null && o.id().equals(selected)) foundCurrent = true;
        }
        if (!foundCurrent && selected != null && !selected.isBlank()) {
            s.append("<option value=\"").append(Layout.escape(selected)).append("\" selected>")
                    .append("(currently set; not visible to bot: ").append(Layout.escape(selected))
                    .append(")</option>");
        }
        for (Option o : opts) {
            s.append("<option value=\"").append(Layout.escape(o.id())).append("\"")
                    .append(o.id().equals(selected) ? " selected" : "").append(">")
                    .append(Layout.escape(o.name())).append("</option>");
        }
        s.append("</select>");
        return s.toString();
    }

    /** Same as {@link #selectField} but renders only the &lt;select&gt; with no label wrapper. */
    public static String selectInline(String name, List<Option> opts, String selected,
                                      String cssClass, String placeholder) {
        StringBuilder s = new StringBuilder();
        s.append("<select class=\"").append(cssClass == null ? "form-select" : cssClass)
                .append("\" name=\"").append(name).append("\">");
        s.append("<option value=\"\">").append(Layout.escape(placeholder == null ? "(select)" : placeholder)).append("</option>");
        boolean foundCurrent = false;
        for (Option o : opts) {
            if (selected != null && o.id().equals(selected)) foundCurrent = true;
        }
        if (!foundCurrent && selected != null && !selected.isBlank()) {
            s.append("<option value=\"").append(Layout.escape(selected)).append("\" selected>")
                    .append("(unknown: ").append(Layout.escape(selected)).append(")</option>");
        }
        for (Option o : opts) {
            s.append("<option value=\"").append(Layout.escape(o.id())).append("\"")
                    .append(o.id().equals(selected) ? " selected" : "").append(">")
                    .append(Layout.escape(o.name())).append("</option>");
        }
        s.append("</select>");
        return s.toString();
    }

    /**
     * Multi-checkbox picker for CSV role/channel lists. Renders the standard
     * Warden {@code .multicheck} container so it scrolls and matches other pages.
     * Selected IDs not present in {@code opts} are shown with a "not visible to
     * bot" label and stay checked so a save doesn't drop them silently.
     */
    public static String multiCheckField(String name, String label,
                                          List<Option> opts, List<String> selected, String tip) {
        java.util.Set<String> sel = (selected == null)
                ? java.util.Set.of()
                : new java.util.LinkedHashSet<>(selected);
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Option o : opts) known.add(o.id());

        StringBuilder s = new StringBuilder();
        s.append("<label class=form-label>")
                .append(Layout.escape(label)).append(Layout.infoIcon(tip)).append("</label>");
        s.append("<div class=multicheck>");
        for (String sid : sel) {
            if (sid != null && !sid.isBlank() && !known.contains(sid)) {
                String id = "mc_" + name + "_" + Integer.toHexString(sid.hashCode());
                s.append("<div class=form-check>")
                        .append("<input class=form-check-input type=checkbox name=\"").append(name)
                        .append("\" value=\"").append(Layout.escape(sid))
                        .append("\" id=\"").append(id).append("\" checked>")
                        .append("<label class=form-check-label for=\"").append(id).append("\">")
                        .append("(currently set; not visible to bot: ")
                        .append(Layout.escape(sid)).append(")</label></div>");
            }
        }
        for (Option o : opts) {
            boolean checked = sel.contains(o.id());
            String id = "mc_" + name + "_" + Integer.toHexString(o.id().hashCode());
            s.append("<div class=form-check>")
                    .append("<input class=form-check-input type=checkbox name=\"").append(name)
                    .append("\" value=\"").append(Layout.escape(o.id())).append("\" id=\"").append(id).append("\"")
                    .append(checked ? " checked" : "").append(">")
                    .append("<label class=form-check-label for=\"").append(id).append("\">")
                    .append(Layout.escape(o.name())).append("</label></div>");
        }
        if (opts.isEmpty() && sel.isEmpty()) {
            s.append("<p class=\"text-secondary small mb-0\">No options available. ")
                    .append("Connect Discord (bot token + guild id in <code>plugins/Warden/config.yml</code>) to populate this list.</p>");
        }
        s.append("</div>");
        return s.toString();
    }
}
