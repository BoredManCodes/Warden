package io.warden.autoresponder;

import io.warden.discord.DiscordSrvBridge;
import io.warden.web.handlers.PapiBridge;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches Discord messages against {@link Autoresponder} rules and renders the
 * response text with placeholders. Compiled regex patterns are cached so the
 * hot path on every message doesn't re-parse the rule list every time.
 */
public final class AutoresponderService {

    private final AutoresponderDao dao;
    private final Supplier<DiscordSrvBridge> discordSrvSupplier;
    private final Logger log;

    /** id -> last-fired epoch ms, used to honour per-rule cooldowns. */
    private final Map<Long, Long> lastFired = new ConcurrentHashMap<>();

    /** id+pattern -> compiled regex; invalidated when the rule's updated_at moves. */
    private final Map<Long, RegexCacheEntry> regexCache = new ConcurrentHashMap<>();

    public AutoresponderService(AutoresponderDao dao, Supplier<DiscordSrvBridge> discordSrvSupplier, Logger log) {
        this.dao = dao;
        this.discordSrvSupplier = discordSrvSupplier == null ? () -> null : discordSrvSupplier;
        this.log = log;
    }

    public AutoresponderDao dao() { return dao; }

    public List<Autoresponder> rules() {
        try { return dao.listAll(); }
        catch (Exception e) {
            log.log(Level.WARNING, "autoresponder: failed to load rules", e);
            return List.of();
        }
    }

    /**
     * Find the first enabled rule whose pattern matches the message, honouring
     * channel/role allow + deny lists and per-rule cooldown. Returns the match
     * so the caller can render placeholders against the actual regex groups.
     */
    public Optional<Match> evaluate(Message message) {
        if (message == null || message.getAuthor().isBot()) return Optional.empty();
        String content = message.getContentRaw();
        if (content == null) return Optional.empty();
        String channelId = message.getChannel().getId();
        Member member = message.getMember();

        long now = System.currentTimeMillis();
        for (Autoresponder rule : rules()) {
            if (!rule.enabled()) continue;
            if (!channelAllowed(rule, channelId)) continue;
            if (!roleAllowed(rule, member)) continue;
            Long last = lastFired.get(rule.id());
            if (last != null && rule.cooldownSeconds() > 0
                    && now - last < rule.cooldownSeconds() * 1000L) {
                continue;
            }
            Matcher m = match(rule, content);
            if (m == null) continue;
            lastFired.put(rule.id(), now);
            return Optional.of(new Match(rule, m, captureGroups(m)));
        }
        return Optional.empty();
    }

    /** Substitute the standard {placeholder} tokens and PAPI %tokens% into a string. */
    public String render(String template, Match match, Message message) {
        if (template == null) return "";
        String out = template;
        if (match != null) {
            for (Map.Entry<String, String> e : match.groups().entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        if (message != null) {
            User author = message.getAuthor();
            Member member = message.getMember();
            String effective = member != null ? member.getEffectiveName() : author.getName();
            out = out
                    .replace("{user}", effective)
                    .replace("{user_mention}", author.getAsMention())
                    .replace("{user_id}", author.getId())
                    .replace("{user_tag}", author.getName())
                    .replace("{username}", author.getName())
                    .replace("{channel}", "<#" + message.getChannel().getId() + ">")
                    .replace("{channel_name}", message.getChannel().getName())
                    .replace("{channel_id}", message.getChannel().getId())
                    .replace("{server}", message.isFromGuild() && message.getGuild() != null
                            ? message.getGuild().getName() : "")
                    .replace("{message}", message.getContentRaw() == null ? "" : message.getContentRaw())
                    .replace("{message_id}", message.getId());
        }
        out = expandPapi(out, message);
        return out;
    }

    private String expandPapi(String s, Message message) {
        if (s == null || s.isEmpty() || s.indexOf('%') < 0) return s;
        if (!PapiBridge.available()) return s;
        OfflinePlayer player = resolvePlayer(message);
        StringBuilder result = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('%', i);
            if (open < 0) { result.append(s, i, s.length()); break; }
            result.append(s, i, open);
            int close = s.indexOf('%', open + 1);
            if (close < 0) { result.append(s, open, s.length()); break; }
            String token = s.substring(open, close + 1);
            String inner = s.substring(open + 1, close);
            String resolved = null;
            if (!inner.isEmpty() && !inner.contains(" ")) {
                try { resolved = PapiBridge.resolve(token, player); } catch (Throwable ignored) {}
            }
            result.append(resolved == null ? token : resolved);
            i = close + 1;
        }
        return result.toString();
    }

    private OfflinePlayer resolvePlayer(Message message) {
        if (message == null) return null;
        DiscordSrvBridge bridge = discordSrvSupplier.get();
        if (bridge == null || !bridge.isPresent()) return null;
        try {
            Optional<UUID> uuid = bridge.uuidFor(message.getAuthor().getId());
            if (uuid.isEmpty()) return null;
            return Bukkit.getOfflinePlayer(uuid.get());
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean channelAllowed(Autoresponder rule, String channelId) {
        if (rule.denyChannelIds().contains(channelId)) return false;
        if (rule.allowChannelIds().isEmpty()) return true;
        return rule.allowChannelIds().contains(channelId);
    }

    private boolean roleAllowed(Autoresponder rule, Member member) {
        if (member == null) {
            return rule.allowRoleIds().isEmpty();
        }
        for (Role r : member.getRoles()) {
            if (rule.denyRoleIds().contains(r.getId())) return false;
        }
        if (rule.allowRoleIds().isEmpty()) return true;
        for (Role r : member.getRoles()) {
            if (rule.allowRoleIds().contains(r.getId())) return true;
        }
        return false;
    }

    private Matcher match(Autoresponder rule, String content) {
        String mode = rule.matchMode() == null ? "contains" : rule.matchMode().toLowerCase();
        String pattern = rule.pattern() == null ? "" : rule.pattern();
        if (pattern.isEmpty()) return null;
        boolean ci = rule.caseInsensitive();
        String haystack = ci ? content.toLowerCase(java.util.Locale.ROOT) : content;
        String needle = ci ? pattern.toLowerCase(java.util.Locale.ROOT) : pattern;
        switch (mode) {
            case "exact" -> {
                if (haystack.trim().equals(needle.trim())) {
                    Pattern p = Pattern.compile(Pattern.quote(pattern),
                            ci ? Pattern.CASE_INSENSITIVE : 0);
                    return p.matcher(content);
                }
                return null;
            }
            case "prefix" -> {
                if (haystack.startsWith(needle)) {
                    Pattern p = Pattern.compile(Pattern.quote(pattern),
                            ci ? Pattern.CASE_INSENSITIVE : 0);
                    return p.matcher(content);
                }
                return null;
            }
            case "suffix" -> {
                if (haystack.endsWith(needle)) {
                    Pattern p = Pattern.compile(Pattern.quote(pattern),
                            ci ? Pattern.CASE_INSENSITIVE : 0);
                    return p.matcher(content);
                }
                return null;
            }
            case "regex" -> {
                Pattern p = compileCached(rule);
                if (p == null) return null;
                Matcher m = p.matcher(content);
                return m.find() ? m : null;
            }
            default -> {
                if (haystack.contains(needle)) {
                    Pattern p = Pattern.compile(Pattern.quote(pattern),
                            ci ? Pattern.CASE_INSENSITIVE : 0);
                    return p.matcher(content);
                }
                return null;
            }
        }
    }

    private Pattern compileCached(Autoresponder rule) {
        RegexCacheEntry hit = regexCache.get(rule.id());
        if (hit != null && hit.updatedAt == rule.updatedAt()) return hit.pattern;
        try {
            int flags = rule.caseInsensitive() ? Pattern.CASE_INSENSITIVE : 0;
            Pattern p = Pattern.compile(rule.pattern(), flags);
            regexCache.put(rule.id(), new RegexCacheEntry(rule.updatedAt(), p));
            return p;
        } catch (Exception e) {
            log.log(Level.WARNING, "autoresponder: bad regex on rule " + rule.id() + ": " + e.getMessage());
            regexCache.put(rule.id(), new RegexCacheEntry(rule.updatedAt(), null));
            return null;
        }
    }

    private Map<String, String> captureGroups(Matcher m) {
        Map<String, String> out = new HashMap<>();
        out.put("match", safeGroup(m, 0));
        for (int i = 1; i <= m.groupCount(); i++) {
            out.put("match." + i, safeGroup(m, i));
            out.put(String.valueOf(i), safeGroup(m, i));
        }
        return out;
    }

    private static String safeGroup(Matcher m, int idx) {
        try {
            String g = m.group(idx);
            return g == null ? "" : g;
        } catch (Exception e) {
            return "";
        }
    }

    /** Cached compiled regex tied to the rule's updated_at so edits invalidate it. */
    private record RegexCacheEntry(long updatedAt, Pattern pattern) {}

    public record Match(Autoresponder rule, Matcher matcher, Map<String, String> groups) {}

    /** Return the list of extra image URLs after rendering placeholders. */
    public List<String> renderExtraImages(Autoresponder rule, Match match, Message message) {
        List<String> raw = rule.extraImageUrls();
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String url : raw) {
            String r = render(url, match, message);
            if (r != null && !r.isBlank()) out.add(r);
        }
        return out;
    }
}
