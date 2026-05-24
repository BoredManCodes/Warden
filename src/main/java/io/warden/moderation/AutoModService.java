package io.warden.moderation;

import io.warden.audit.AuditService;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Scans guild messages against the configured auto-mod rules. Pure rule
 * evaluation lives here; the actual JDA actions (delete, mute, etc.) are
 * issued by the listener that owns this service.
 */
public final class AutoModService {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b((?:https?://|www\\.)[\\w.-]+(?:/\\S*)?)");
    private static final Pattern INVITE_PATTERN = Pattern.compile(
            "(?i)(?:discord\\.gg|discord(?:app)?\\.com/invite)/([\\w-]+)");
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:\\w+:\\d+>");
    private static final Pattern UNICODE_EMOJI = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F02F}\\x{1F0A0}-\\x{1F0FF}]");
    private static final Pattern ZALGO_PATTERN = Pattern.compile("[\\u0300-\\u036f\\u0489\\u1ab0-\\u1aff\\u1dc0-\\u1dff\\u20d0-\\u20ff\\ufe20-\\ufe2f]");

    private final AutomodConfigDao configDao;
    private final WarningDao warnings;
    private final AuditService audit;
    private final Logger log;

    private final Map<String, Deque<Long>> recentMessages = new ConcurrentHashMap<>();

    public AutoModService(AutomodConfigDao configDao, WarningDao warnings, AuditService audit, Logger log) {
        this.configDao = configDao;
        this.warnings = warnings;
        this.audit = audit;
        this.log = log;
    }

    /**
     * Evaluate a message and return the first violation, or null if everything
     * is clean. Returning a Violation tells the listener to delete the message
     * and decide what (if any) follow-up to apply.
     */
    public Violation evaluate(Message msg) {
        if (msg == null || msg.getAuthor().isBot()) return null;
        AutomodConfig cfg;
        try { cfg = configDao.get(); } catch (Exception e) {
            log.log(Level.WARNING, "automod: config load failed", e);
            return null;
        }
        if (!cfg.enabled()) return null;

        Member member = msg.getMember();
        if (member != null && member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER)) return null;
        if (member != null) {
            for (Role r : member.getRoles()) {
                if (cfg.exemptRoleIds().contains(r.getId())) return null;
            }
        }
        String channelId = msg.getChannel().getId();
        if (cfg.exemptChannelIds().contains(channelId)) return null;

        String content = msg.getContentRaw();
        if (content == null) content = "";

        if (cfg.spamEnabled()) {
            Deque<Long> q = recentMessages.computeIfAbsent(msg.getAuthor().getId(),
                    k -> new ArrayDeque<>());
            long now = System.currentTimeMillis();
            long windowStart = now - cfg.spamWindowSeconds() * 1000L;
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst() < windowStart) q.pollFirst();
                q.addLast(now);
                if (q.size() >= cfg.spamThreshold()) {
                    q.clear();
                    return new Violation("spam", "Sent " + cfg.spamThreshold()
                            + " messages in " + cfg.spamWindowSeconds() + "s", cfg);
                }
            }
        }
        if (cfg.capsEnabled() && content.length() >= cfg.capsMinLength()) {
            int letters = 0, upper = 0;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    if (Character.isUpperCase(c)) upper++;
                }
            }
            if (letters > 0 && (upper * 100) / letters >= cfg.capsPercent()) {
                return new Violation("caps", "Message is mostly uppercase", cfg);
            }
        }
        if (cfg.badWordsEnabled() && !cfg.badWordsList().isBlank()) {
            String lower = content.toLowerCase(java.util.Locale.ROOT);
            for (String raw : cfg.badWordsList().split("[,\\n]")) {
                String w = raw.trim().toLowerCase(java.util.Locale.ROOT);
                if (w.isEmpty()) continue;
                if (lower.contains(w)) {
                    return new Violation("bad_word", "Banned word matched: " + w, cfg);
                }
            }
        }
        if (cfg.invitesEnabled() && INVITE_PATTERN.matcher(content).find()) {
            return new Violation("invite", "Discord invite link", cfg);
        }
        if (cfg.linksEnabled()) {
            var m = URL_PATTERN.matcher(content);
            while (m.find()) {
                String url = m.group();
                if (!isAllowlisted(url, cfg.linksAllowlist())) {
                    return new Violation("link", "Disallowed link: " + url, cfg);
                }
            }
        }
        if (cfg.massMentionEnabled()) {
            int total = msg.getMentions().getUsers().size()
                    + msg.getMentions().getRoles().size();
            if (total >= cfg.massMentionThreshold()) {
                return new Violation("mass_mention", "Too many mentions (" + total + ")", cfg);
            }
        }
        if (cfg.emojiFloodEnabled()) {
            int emojis = countEmojis(content);
            if (emojis >= cfg.emojiFloodThreshold()) {
                return new Violation("emoji_flood", "Too many emojis (" + emojis + ")", cfg);
            }
        }
        if (cfg.zalgoEnabled()) {
            int combining = 0, len = content.length();
            for (int i = 0; i < len; i++) {
                if (ZALGO_PATTERN.matcher(String.valueOf(content.charAt(i))).matches()) combining++;
            }
            if (combining > 8 || (len > 0 && combining * 100 / Math.max(1, len) > 30)) {
                return new Violation("zalgo", "Zalgo text", cfg);
            }
        }
        return null;
    }

    private static boolean isAllowlisted(String url, String allowlist) {
        if (allowlist == null || allowlist.isBlank()) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        for (String raw : allowlist.split("[,\\n]")) {
            String d = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (d.isEmpty()) continue;
            if (lower.contains(d)) return true;
        }
        return false;
    }

    private static int countEmojis(String s) {
        int n = 0;
        var cm = CUSTOM_EMOJI.matcher(s);
        while (cm.find()) n++;
        var um = UNICODE_EMOJI.matcher(s);
        while (um.find()) n++;
        return n;
    }

    public AutomodConfigDao configDao() { return configDao; }
    public WarningDao warningDao() { return warnings; }

    /** Reason returned to the listener when a rule fires. */
    public record Violation(String rule, String reason, AutomodConfig cfg) {}
}
