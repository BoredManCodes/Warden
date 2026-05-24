package io.warden.tickets;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders the full conversation of a ticket into a static HTML file styled to
 * resemble the Discord client (message gutter with avatar, header line, body,
 * grouped messages from the same author within a couple of minutes, day
 * dividers, attachment chips, etc.). Transcripts are written under the same
 * attachments directory the tickets module already uses so a single mount
 * point covers everything.
 *
 * The output is meant to be opened directly by reporters via a tokenised
 * link, so it's a single self-contained document with inline CSS - no
 * external dependencies, no JS, safe to view offline.
 */
public final class TranscriptService {

    private static final DateTimeFormatter DAY_DIVIDER =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MSG_TIME =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter MSG_FULL =
            DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a", Locale.ENGLISH);

    private static final long GROUP_WINDOW_MS = 7 * 60 * 1000L; // 7 min: matches Discord's grouping feel.

    private static final SecureRandom RAND = new SecureRandom();

    private final TicketDao tickets;
    private final Path attachmentsDir;
    private final Logger log;

    public TranscriptService(TicketDao tickets, Path attachmentsDir, Logger log) {
        this.tickets = tickets;
        this.attachmentsDir = attachmentsDir;
        this.log = log;
    }

    /** Generate a transcript for a ticket if one is missing. Returns the new (or existing) URL token. */
    public String generateIfMissing(JDA jda, long ticketId) {
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return "";
            if (t.hasTranscript()) return t.transcriptToken();
            return generate(jda, t);
        } catch (Exception e) {
            log.log(Level.WARNING, "transcript generateIfMissing failed for #" + ticketId, e);
            return "";
        }
    }

    /** Force-render a transcript, overwriting any previous file/token. */
    public String generate(JDA jda, Ticket t) {
        try {
            List<TicketMessage> all = tickets.messages(t.id());
            // Internal staff notes are intentionally omitted: the transcript is
            // shared with the reporter, so staff-only commentary must never leak.
            List<TicketMessage> msgs = new java.util.ArrayList<>(all.size());
            for (TicketMessage m : all) {
                if (!m.isInternal()) msgs.add(m);
            }
            Map<String, AuthorInfo> authors = resolveAuthors(jda, t, msgs);
            String html = renderHtml(t, msgs, authors);

            Path dir = attachmentsDir.resolve(String.valueOf(t.id()));
            Files.createDirectories(dir);
            Path file = dir.resolve("transcript.html");
            Files.writeString(file, html, StandardCharsets.UTF_8);

            String token = t.transcriptToken() == null || t.transcriptToken().isBlank()
                    ? newToken() : t.transcriptToken();
            tickets.setTranscript(t.id(), file.toString(), token, System.currentTimeMillis());
            return token;
        } catch (Exception e) {
            log.log(Level.WARNING, "transcript generate failed for #" + t.id(), e);
            return "";
        }
    }

    private static String newToken() {
        byte[] buf = new byte[18];
        RAND.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /* ----------------------------- Author resolution ----------------------------- */

    /** Identity + avatar URL gathered for each speaker so we can draw the gutter. */
    private record AuthorInfo(String name, String discriminatorTag, String avatarUrl, String roleLabel, int colorRgb) {}

    private Map<String, AuthorInfo> resolveAuthors(JDA jda, Ticket t, List<TicketMessage> msgs) {
        Map<String, AuthorInfo> out = new HashMap<>();
        // Reporter
        out.put(authorKey(TicketMessage.KIND_USER, t.discordId()),
                resolveUser(jda, t.discordId(),
                        t.discordUsername() == null || t.discordUsername().isBlank()
                                ? "User" : t.discordUsername(),
                        "Reporter", 0x5865F2));
        // System (no avatar fetch; use a stable bot-style label).
        out.put(authorKey(TicketMessage.KIND_SYSTEM, ""),
                new AuthorInfo("System", "BOT", null, "Bot", 0x9aa2ad));

        for (TicketMessage m : msgs) {
            String key = authorKey(m.authorKind(), m.authorId());
            if (out.containsKey(key)) continue;
            switch (m.authorKind()) {
                case TicketMessage.KIND_STAFF -> out.put(key, resolveUser(jda, m.authorId(),
                        m.authorName() == null || m.authorName().isBlank() ? "Staff" : m.authorName(),
                        "Staff", 0xE67E22));
                case TicketMessage.KIND_SYSTEM -> { /* covered above */ }
                default -> out.put(key, resolveUser(jda, m.authorId(),
                        m.authorName() == null || m.authorName().isBlank() ? "User" : m.authorName(),
                        "Reporter", 0x5865F2));
            }
        }
        return out;
    }

    private static String authorKey(String kind, String id) {
        if (TicketMessage.KIND_SYSTEM.equals(kind)) return "system";
        return (kind == null ? "" : kind) + ":" + (id == null ? "" : id);
    }

    private AuthorInfo resolveUser(JDA jda, String discordId, String fallbackName,
                                   String roleLabel, int color) {
        String name = fallbackName;
        String avatarUrl = null;
        String discTag = "";
        if (jda != null && discordId != null && !discordId.isBlank()) {
            try {
                User u = jda.retrieveUserById(discordId).complete();
                if (u != null) {
                    String eff = u.getEffectiveName();
                    if (eff != null && !eff.isBlank()) name = eff;
                    avatarUrl = u.getEffectiveAvatarUrl();
                    String discriminator = u.getDiscriminator();
                    if (discriminator != null && !"0000".equals(discriminator)) {
                        discTag = "#" + discriminator;
                    }
                }
            } catch (Exception ignored) {
                // Offline / rate-limited / user left guild - fall back to stored name + generated avatar.
            }
        }
        if (avatarUrl == null || avatarUrl.isBlank()) {
            avatarUrl = defaultAvatarUrl(discordId);
        }
        return new AuthorInfo(name, discTag, avatarUrl, roleLabel, color);
    }

    /** Reproduces Discord's default-avatar URL scheme (5 colours, indexed by id). */
    private static String defaultAvatarUrl(String discordId) {
        int idx = 0;
        if (discordId != null && !discordId.isBlank()) {
            try {
                long n = Long.parseLong(discordId);
                idx = (int) Math.floorMod(n >> 22, 6L);
            } catch (NumberFormatException ignored) {
                idx = Math.floorMod(discordId.hashCode(), 6);
            }
        }
        return "https://cdn.discordapp.com/embed/avatars/" + idx + ".png";
    }

    /* ----------------------------- HTML rendering ----------------------------- */

    private static String renderHtml(Ticket t, List<TicketMessage> msgs, Map<String, AuthorInfo> authors) {
        StringBuilder h = new StringBuilder(16384);
        h.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">");
        h.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        h.append("<title>Ticket #").append(t.id()).append(" transcript</title>");
        h.append("<style>").append(CSS).append("</style></head><body>");

        h.append("<div class=\"transcript\">");
        h.append("<header class=\"hdr\">");
        h.append("<div class=\"hdr-channel\"><span class=\"hash\">#</span>")
                .append(escape("ticket-" + t.id())).append("</div>");
        h.append("<div class=\"hdr-topic\">")
                .append(escape(t.subject() == null ? "Ticket transcript" : t.subject()))
                .append("</div>");
        h.append("<div class=\"hdr-meta\">");
        h.append("Opened ").append(escape(MSG_FULL.format(Instant.ofEpochMilli(t.createdAt()).atZone(ZoneId.systemDefault()))));
        if (t.closedAt() != null) {
            h.append(" &middot; Closed ").append(escape(MSG_FULL.format(
                    Instant.ofEpochMilli(t.closedAt()).atZone(ZoneId.systemDefault()))));
        }
        h.append(" &middot; ").append(msgs.size()).append(" message").append(msgs.size() == 1 ? "" : "s");
        h.append("</div></header>");

        h.append("<main class=\"chat\">");
        LocalDate currentDay = null;
        String lastAuthorKey = null;
        long lastTs = Long.MIN_VALUE;
        for (TicketMessage m : msgs) {
            ZonedDateTime when = Instant.ofEpochMilli(m.createdAt()).atZone(ZoneId.systemDefault());
            LocalDate day = when.toLocalDate();
            if (currentDay == null || !currentDay.equals(day)) {
                h.append("<div class=\"divider\"><span>")
                        .append(escape(DAY_DIVIDER.format(when))).append("</span></div>");
                currentDay = day;
                lastAuthorKey = null;
                lastTs = Long.MIN_VALUE;
            }
            String key = authorKey(m.authorKind(), m.authorId());
            boolean grouped = key.equals(lastAuthorKey)
                    && (m.createdAt() - lastTs) < GROUP_WINDOW_MS
                    && !TicketMessage.KIND_SYSTEM.equals(m.authorKind());
            AuthorInfo info = authors.getOrDefault(key, authors.get("system"));
            if (info == null) info = new AuthorInfo("Unknown", "", defaultAvatarUrl(m.authorId()), "", 0x9aa2ad);

            if (TicketMessage.KIND_SYSTEM.equals(m.authorKind())) {
                h.append("<div class=\"sys\"><span class=\"sys-icon\">&#9881;</span>")
                        .append("<span class=\"sys-body\">").append(escape(m.body() == null ? "" : m.body()))
                        .append("</span> <span class=\"sys-time\">")
                        .append(escape(MSG_TIME.format(when))).append("</span></div>");
                lastAuthorKey = key;
                lastTs = m.createdAt();
                continue;
            }

            h.append("<div class=\"msg ").append(grouped ? "grouped" : "fresh").append("\">");
            h.append("<div class=\"gutter\">");
            if (grouped) {
                h.append("<span class=\"hover-time\">").append(escape(MSG_TIME.format(when))).append("</span>");
            } else {
                h.append("<img class=\"avatar\" alt=\"\" src=\"").append(escape(info.avatarUrl())).append("\">");
            }
            h.append("</div>");
            h.append("<div class=\"body\">");
            if (!grouped) {
                h.append("<div class=\"meta\">");
                h.append("<span class=\"author\" style=\"color:#").append(hex6(info.colorRgb())).append("\">")
                        .append(escape(info.name())).append("</span>");
                if (info.roleLabel() != null && !info.roleLabel().isBlank()) {
                    h.append("<span class=\"tag tag-").append(roleSlug(info.roleLabel())).append("\">")
                            .append(escape(info.roleLabel())).append("</span>");
                }
                h.append("<span class=\"ts\" title=\"").append(escape(MSG_FULL.format(when))).append("\">")
                        .append(escape(MSG_TIME.format(when))).append("</span>");
                h.append("</div>");
            }
            String bodyText = m.body() == null ? "" : m.body();
            if (!bodyText.isBlank()) {
                h.append("<div class=\"content\">").append(renderContent(bodyText)).append("</div>");
            }
            List<TicketMessage.Attachment> atts = m.attachments();
            if (!atts.isEmpty()) {
                h.append("<div class=\"atts\">");
                for (TicketMessage.Attachment a : atts) {
                    h.append(renderAttachment(t.id(), m.id(), a));
                }
                h.append("</div>");
            }
            h.append("</div></div>");

            lastAuthorKey = key;
            lastTs = m.createdAt();
        }
        h.append("</main>");

        h.append("<footer class=\"foot\">Transcript generated ")
                .append(escape(MSG_FULL.format(ZonedDateTime.now())))
                .append("</footer>");
        h.append("</div></body></html>");
        return h.toString();
    }

    private static String renderAttachment(long ticketId, long messageId, TicketMessage.Attachment a) {
        String name = a.name() == null || a.name().isBlank() ? "file" : a.name();
        String size = a.size() > 0 ? humanSize(a.size()) : "";
        String href;
        boolean stored = a.storedPath() != null && !a.storedPath().isBlank();
        if (stored) {
            href = "/tickets/transcript-asset/" + ticketId + "/" + messageId + "/" + urlEncode(name);
        } else if (a.discordUrl() != null && !a.discordUrl().isBlank()) {
            href = a.discordUrl();
        } else {
            href = "#";
        }
        boolean isImage = looksLikeImage(name);
        StringBuilder b = new StringBuilder();
        if (isImage) {
            b.append("<a class=\"att-img\" target=\"_blank\" rel=\"noopener\" href=\"")
                    .append(escape(href)).append("\">")
                    .append("<img alt=\"").append(escape(name)).append("\" src=\"")
                    .append(escape(href)).append("\">")
                    .append("</a>");
        } else {
            b.append("<a class=\"att-file\" target=\"_blank\" rel=\"noopener\" href=\"")
                    .append(escape(href)).append("\">")
                    .append("<span class=\"att-icon\">&#128206;</span>")
                    .append("<span class=\"att-name\">").append(escape(name)).append("</span>");
            if (!size.isBlank()) {
                b.append("<span class=\"att-size\">").append(escape(size)).append("</span>");
            }
            b.append("</a>");
        }
        return b.toString();
    }

    private static boolean looksLikeImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024L * 1024 * 1024)) + " GB";
    }

    /* ----------------------------- Content formatting ----------------------------- */

    /**
     * Lightweight Discord-flavoured markdown: bold, italics, underline,
     * strikethrough, inline + fenced code, blockquotes, autolinks, channel
     * + user mentions. Designed to be safe: everything HTML-escapes first,
     * then a small regex pass adds markup.
     */
    static String renderContent(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        boolean inCodeBlock = false;
        StringBuilder code = new StringBuilder();
        boolean inQuote = false;
        for (String raw : lines) {
            if (raw.startsWith("```") && !inCodeBlock) {
                inCodeBlock = true;
                code.setLength(0);
                continue;
            }
            if (inCodeBlock) {
                if (raw.startsWith("```")) {
                    inCodeBlock = false;
                    out.append("<pre class=\"code\">").append(escape(code.toString())).append("</pre>");
                } else {
                    code.append(raw).append("\n");
                }
                continue;
            }
            if (raw.startsWith("> ") || "> ".equals(raw) || ">".equals(raw)) {
                if (!inQuote) { out.append("<blockquote>"); inQuote = true; }
                String quoteBody = raw.startsWith("> ") ? raw.substring(2) : raw.length() > 1 ? raw.substring(1) : "";
                out.append(applyInline(quoteBody)).append("<br>");
                continue;
            }
            if (inQuote) { out.append("</blockquote>"); inQuote = false; }
            out.append(applyInline(raw)).append("<br>");
        }
        if (inCodeBlock) {
            out.append("<pre class=\"code\">").append(escape(code.toString())).append("</pre>");
        }
        if (inQuote) out.append("</blockquote>");
        // Trim a single trailing <br> for cleaner spacing.
        if (out.length() >= 4 && out.substring(out.length() - 4).equals("<br>")) {
            out.setLength(out.length() - 4);
        }
        return out.toString();
    }

    private static String applyInline(String raw) {
        String s = escape(raw);
        // Fenced inline code goes first so other patterns don't run inside it.
        StringBuilder result = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int tick = s.indexOf('`', i);
            if (tick < 0) {
                result.append(applyNonCodeInline(s.substring(i)));
                break;
            }
            result.append(applyNonCodeInline(s.substring(i, tick)));
            int end = s.indexOf('`', tick + 1);
            if (end < 0) {
                result.append(s.substring(tick));
                break;
            }
            result.append("<code class=\"inline\">").append(s, tick + 1, end).append("</code>");
            i = end + 1;
        }
        return result.toString();
    }

    private static String applyNonCodeInline(String s) {
        // Bold
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        // Underline
        s = s.replaceAll("__([^_]+)__", "<u>$1</u>");
        // Italics (asterisk or single underscore)
        s = s.replaceAll("(?<![*\\w])\\*([^*\\n]+?)\\*(?!\\*)", "<em>$1</em>");
        s = s.replaceAll("(?<![_\\w])_([^_\\n]+?)_(?!_)", "<em>$1</em>");
        // Strikethrough
        s = s.replaceAll("~~([^~]+)~~", "<s>$1</s>");
        // Spoiler (we render as a soft highlight rather than click-to-reveal)
        s = s.replaceAll("\\|\\|([^|]+)\\|\\|", "<span class=\"spoiler\">$1</span>");
        // Autolink http(s) URLs. The URL was escaped earlier so &amp;, &quot;, etc.
        s = s.replaceAll("(https?://[^\\s\\)\\]<\"&]+)", "<a href=\"$1\" target=\"_blank\" rel=\"noopener\">$1</a>");
        // Discord user / channel / role mentions
        s = s.replaceAll("&lt;@!?([0-9]+)&gt;", "<span class=\"mention\">@user</span>");
        s = s.replaceAll("&lt;#([0-9]+)&gt;", "<span class=\"mention\">#channel</span>");
        s = s.replaceAll("&lt;@&amp;([0-9]+)&gt;", "<span class=\"mention\">@role</span>");
        return s;
    }

    /* ----------------------------- Small helpers ----------------------------- */

    private static String hex6(int rgb) {
        return String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
    }

    private static String roleSlug(String label) {
        return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Resolve a stored attachment's path on disk, scoped under the attachments dir. */
    public Optional<Path> resolveAttachment(long ticketId, long messageId, String name) {
        try {
            Ticket t = tickets.find(ticketId).orElse(null);
            if (t == null) return Optional.empty();
            for (TicketMessage m : tickets.messages(ticketId)) {
                if (m.id() != messageId) continue;
                for (TicketMessage.Attachment a : m.attachments()) {
                    if (!name.equals(a.name())) continue;
                    if (a.storedPath() == null || a.storedPath().isBlank()) return Optional.empty();
                    Path p = Path.of(a.storedPath()).toAbsolutePath().normalize();
                    Path base = attachmentsDir.toAbsolutePath().normalize();
                    if (!p.startsWith(base) || !Files.exists(p)) return Optional.empty();
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.log(Level.WARNING, "resolveAttachment failed", e);
            return Optional.empty();
        }
    }

    public Optional<Path> transcriptFile(Ticket t) {
        if (t == null || t.transcriptPath() == null || t.transcriptPath().isBlank()) return Optional.empty();
        try {
            Path p = Path.of(t.transcriptPath()).toAbsolutePath().normalize();
            Path base = attachmentsDir.toAbsolutePath().normalize();
            if (!p.startsWith(base) || !Files.exists(p)) return Optional.empty();
            return Optional.of(p);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Discord-mimicking CSS; sits inline at the top of every transcript. */
    private static final String CSS =
            ":root {\n" +
            "  --bg-primary: #313338;\n" +
            "  --bg-secondary: #2b2d31;\n" +
            "  --bg-tertiary: #1e1f22;\n" +
            "  --bg-message-hover: rgba(4,4,5,0.07);\n" +
            "  --text-normal: #dbdee1;\n" +
            "  --text-muted: #949ba4;\n" +
            "  --text-link: #00a8fc;\n" +
            "  --header-bg: #2b2d31;\n" +
            "  --divider: rgba(255,255,255,0.06);\n" +
            "  --mention-bg: rgba(88,101,242,0.3);\n" +
            "  --mention-color: #c9cdfb;\n" +
            "  --code-bg: #1e1f22;\n" +
            "  --quote-border: #4e5058;\n" +
            "  --spoiler-bg: #202225;\n" +
            "  --tag-staff: #e67e22;\n" +
            "  --tag-reporter: #5865f2;\n" +
            "  --tag-bot: #5865f2;\n" +
            "}\n" +
            "* { box-sizing: border-box; }\n" +
            "html, body { margin: 0; padding: 0; background: var(--bg-primary); color: var(--text-normal);\n" +
            "  font-family: 'gg sans', 'Whitney', 'Helvetica Neue', Helvetica, Arial, sans-serif;\n" +
            "  font-size: 16px; line-height: 1.375; -webkit-font-smoothing: antialiased; }\n" +
            ".transcript { max-width: 980px; margin: 0 auto; }\n" +
            ".hdr { position: sticky; top: 0; z-index: 5; background: var(--header-bg);\n" +
            "  padding: 14px 24px; border-bottom: 1px solid var(--divider);\n" +
            "  box-shadow: 0 1px 0 rgba(4,4,5,0.2); }\n" +
            ".hdr-channel { font-size: 16px; font-weight: 600; color: #f2f3f5;\n" +
            "  display: flex; align-items: center; gap: 6px; }\n" +
            ".hdr-channel .hash { color: var(--text-muted); font-weight: 400; }\n" +
            ".hdr-topic { color: var(--text-muted); font-size: 13px; margin-top: 2px; }\n" +
            ".hdr-meta { color: var(--text-muted); font-size: 12px; margin-top: 6px; }\n" +
            ".chat { padding: 16px 8px 32px 8px; }\n" +
            ".divider { position: relative; text-align: center; margin: 24px 16px 8px; }\n" +
            ".divider::before { content: ''; position: absolute; left: 0; right: 0; top: 50%;\n" +
            "  height: 1px; background: var(--divider); }\n" +
            ".divider span { position: relative; display: inline-block; padding: 2px 10px;\n" +
            "  background: var(--bg-primary); color: var(--text-muted);\n" +
            "  font-size: 12px; font-weight: 600; text-transform: capitalize;\n" +
            "  border: 1px solid var(--divider); border-radius: 8px; }\n" +
            ".msg { display: flex; padding: 2px 16px; position: relative; }\n" +
            ".msg:hover { background: var(--bg-message-hover); }\n" +
            ".msg.fresh { padding-top: 17px; padding-bottom: 2px; margin-top: 12px; }\n" +
            ".msg.grouped { padding-top: 2px; padding-bottom: 2px; }\n" +
            ".gutter { width: 56px; flex-shrink: 0; display: flex; justify-content: center;\n" +
            "  padding-top: 2px; }\n" +
            ".avatar { width: 40px; height: 40px; border-radius: 50%; object-fit: cover; }\n" +
            ".hover-time { color: var(--text-muted); font-size: 11px; opacity: 0;\n" +
            "  padding-top: 5px; transition: opacity .1s; }\n" +
            ".msg.grouped:hover .hover-time { opacity: 1; }\n" +
            ".body { flex: 1; min-width: 0; }\n" +
            ".meta { display: flex; align-items: baseline; gap: 8px; line-height: 1.375; }\n" +
            ".author { font-weight: 500; font-size: 16px; }\n" +
            ".tag { font-size: 10px; font-weight: 500; padding: 1px 6px; border-radius: 4px;\n" +
            "  color: white; text-transform: uppercase; letter-spacing: .02em;\n" +
            "  position: relative; top: -1px; }\n" +
            ".tag-staff { background: var(--tag-staff); }\n" +
            ".tag-reporter { background: var(--tag-reporter); }\n" +
            ".tag-bot { background: var(--tag-bot); }\n" +
            ".ts { color: var(--text-muted); font-size: 12px; }\n" +
            ".content { color: var(--text-normal); margin-top: 2px; white-space: normal;\n" +
            "  overflow-wrap: anywhere; }\n" +
            ".content a { color: var(--text-link); text-decoration: none; }\n" +
            ".content a:hover { text-decoration: underline; }\n" +
            ".content strong { font-weight: 700; }\n" +
            ".content em { font-style: italic; }\n" +
            ".content code.inline { background: var(--code-bg); padding: 2px 4px; border-radius: 4px;\n" +
            "  font-family: 'Consolas', 'Andale Mono WT', monospace; font-size: 0.85em; }\n" +
            ".content pre.code { background: var(--code-bg); padding: 8px 12px; border-radius: 4px;\n" +
            "  font-family: 'Consolas', monospace; font-size: 0.85em; overflow-x: auto;\n" +
            "  white-space: pre; border: 1px solid #1a1b1e; margin: 6px 0; }\n" +
            ".content blockquote { border-left: 4px solid var(--quote-border);\n" +
            "  padding: 0 8px; margin: 0; color: var(--text-normal); }\n" +
            ".content .mention { background: var(--mention-bg); color: var(--mention-color);\n" +
            "  padding: 0 2px; border-radius: 3px; font-weight: 500; }\n" +
            ".content .spoiler { background: var(--spoiler-bg); border-radius: 3px; padding: 0 2px;\n" +
            "  color: var(--bg-tertiary); }\n" +
            ".content .spoiler:hover { color: var(--text-normal); }\n" +
            ".atts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }\n" +
            ".att-img { display: inline-block; max-width: 400px; max-height: 350px;\n" +
            "  border-radius: 4px; overflow: hidden; line-height: 0; }\n" +
            ".att-img img { max-width: 100%; max-height: 350px; object-fit: cover; display: block; }\n" +
            ".att-file { display: inline-flex; align-items: center; gap: 8px;\n" +
            "  background: var(--bg-secondary); border: 1px solid var(--divider);\n" +
            "  border-radius: 4px; padding: 10px 12px; color: var(--text-link);\n" +
            "  text-decoration: none; max-width: 432px; }\n" +
            ".att-file:hover { text-decoration: underline; }\n" +
            ".att-icon { color: var(--text-muted); font-size: 18px; }\n" +
            ".att-name { flex: 1; }\n" +
            ".att-size { color: var(--text-muted); font-size: 12px; }\n" +
            ".sys { display: flex; gap: 8px; padding: 4px 16px; color: var(--text-muted);\n" +
            "  align-items: baseline; }\n" +
            ".sys-icon { color: var(--text-muted); }\n" +
            ".sys-body { flex: 1; }\n" +
            ".sys-time { font-size: 11px; }\n" +
            ".foot { padding: 14px 24px; border-top: 1px solid var(--divider);\n" +
            "  color: var(--text-muted); font-size: 12px; text-align: center; }\n" +
            "@media (prefers-color-scheme: light) {\n" +
            "  :root { --bg-primary: #ffffff; --bg-secondary: #f2f3f5; --bg-tertiary: #ebedef;\n" +
            "    --text-normal: #2e3338; --text-muted: #5c6772; --header-bg: #ffffff;\n" +
            "    --divider: rgba(6,6,7,0.08); --code-bg: #f2f3f5; --quote-border: #c7ccd1;\n" +
            "    --bg-message-hover: rgba(6,6,7,0.025); }\n" +
            "}\n";
}
