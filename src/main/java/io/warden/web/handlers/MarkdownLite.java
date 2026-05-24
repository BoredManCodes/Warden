package io.warden.web.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny single-pass Markdown-ish renderer for the public /rules page.
 *
 * Handles the subset operators actually use in rules text: headings
 * ({@code #}/{@code ##}/{@code ###}), bullet lists, blank-line paragraphs,
 * {@code **bold**}, {@code *italic*}, inline {@code `code`}, links
 * {@code [text](https://...)}, blockquotes ({@code > }) and horizontal rules
 * ({@code ---}). Anything else is escaped as plain text.
 *
 * Not a full CommonMark parser - if operators want richer formatting they can
 * just edit rules.html directly. The point is to make whatever they typed in
 * the dashboard look reasonable without surprises.
 */
final class MarkdownLite {

    private MarkdownLite() {}

    private static final Pattern URL_OK   = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_RE  = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern BOLD_RE  = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITAL_RE  = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern CODE_RE  = Pattern.compile("`([^`]+)`");
    private static final Pattern OL_RE    = Pattern.compile("^(\\d+)\\.\\s+(.*)$");

    // Use the ASCII unit-separator and record-separator control chars (0x1F, 0x1E)
    // as opaque placeholders around inline-code spans. These never appear in plain
    // rules text the operator would type, so we can yank `code` out before escape /
    // bold / italic / link passes and put it back at the end without collisions.
    private static final char CODE_OPEN  = (char) 0x1F;
    private static final char CODE_CLOSE = (char) 0x1E;

    static String toHtml(String md) {
        if (md == null || md.isBlank()) {
            return "<p class=\"prose-empty\">No rules have been published yet.</p>";
        }
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        StringBuilder out = new StringBuilder(md.length() + 256);
        List<String> paraBuf = new ArrayList<>();
        boolean inUl = false;
        boolean inOl = false;
        boolean inQuote = false;

        for (String raw : lines) {
            String line = raw;
            String trimmed = line.strip();

            // Blank line: flush whatever block we were collecting.
            if (trimmed.isEmpty()) {
                flushPara(out, paraBuf);
                if (inUl)    { out.append("</ul>\n"); inUl = false; }
                if (inOl)    { out.append("</ol>\n"); inOl = false; }
                if (inQuote) { out.append("</blockquote>\n"); inQuote = false; }
                continue;
            }

            // Horizontal rule.
            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                flushPara(out, paraBuf);
                if (inUl)    { out.append("</ul>\n"); inUl = false; }
                if (inOl)    { out.append("</ol>\n"); inOl = false; }
                if (inQuote) { out.append("</blockquote>\n"); inQuote = false; }
                out.append("<hr>\n");
                continue;
            }

            // Headings.
            if (trimmed.startsWith("### ")) {
                flushBlocks(out, paraBuf, inUl, inOl, inQuote);
                inUl = false; inOl = false; inQuote = false;
                out.append("<h3>").append(inline(trimmed.substring(4).strip())).append("</h3>\n");
                continue;
            }
            if (trimmed.startsWith("## ")) {
                flushBlocks(out, paraBuf, inUl, inOl, inQuote);
                inUl = false; inOl = false; inQuote = false;
                out.append("<h2>").append(inline(trimmed.substring(3).strip())).append("</h2>\n");
                continue;
            }
            if (trimmed.startsWith("# ")) {
                flushBlocks(out, paraBuf, inUl, inOl, inQuote);
                inUl = false; inOl = false; inQuote = false;
                out.append("<h1>").append(inline(trimmed.substring(2).strip())).append("</h1>\n");
                continue;
            }

            // Bullet list item.
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushPara(out, paraBuf);
                if (inOl)    { out.append("</ol>\n"); inOl = false; }
                if (inQuote) { out.append("</blockquote>\n"); inQuote = false; }
                if (!inUl) { out.append("<ul>\n"); inUl = true; }
                out.append("  <li>").append(inline(trimmed.substring(2).strip())).append("</li>\n");
                continue;
            }

            // Numbered list item ("1. ", "12. ", ...).
            Matcher olm = OL_RE.matcher(trimmed);
            if (olm.matches()) {
                flushPara(out, paraBuf);
                if (inUl)    { out.append("</ul>\n"); inUl = false; }
                if (inQuote) { out.append("</blockquote>\n"); inQuote = false; }
                if (!inOl) { out.append("<ol>\n"); inOl = true; }
                out.append("  <li>").append(inline(olm.group(2).strip())).append("</li>\n");
                continue;
            }

            // Blockquote line.
            if (trimmed.startsWith("> ")) {
                flushPara(out, paraBuf);
                if (inUl) { out.append("</ul>\n"); inUl = false; }
                if (inOl) { out.append("</ol>\n"); inOl = false; }
                if (!inQuote) { out.append("<blockquote>\n"); inQuote = true; }
                out.append("  <p>").append(inline(trimmed.substring(2).strip())).append("</p>\n");
                continue;
            }

            if (inUl)    { out.append("</ul>\n"); inUl = false; }
            if (inOl)    { out.append("</ol>\n"); inOl = false; }
            if (inQuote) { out.append("</blockquote>\n"); inQuote = false; }
            paraBuf.add(line);
        }

        flushPara(out, paraBuf);
        if (inUl)    out.append("</ul>\n");
        if (inOl)    out.append("</ol>\n");
        if (inQuote) out.append("</blockquote>\n");

        return out.toString();
    }

    private static void flushBlocks(StringBuilder out, List<String> paraBuf, boolean inUl, boolean inOl, boolean inQuote) {
        flushPara(out, paraBuf);
        if (inUl)    out.append("</ul>\n");
        if (inOl)    out.append("</ol>\n");
        if (inQuote) out.append("</blockquote>\n");
    }

    private static void flushPara(StringBuilder out, List<String> buf) {
        if (buf.isEmpty()) return;
        String joined = String.join(" ", buf).strip();
        buf.clear();
        if (joined.isEmpty()) return;
        out.append("<p>").append(inline(joined)).append("</p>\n");
    }

    /** Apply inline transformations (escape first, then re-introduce markup as safe HTML). */
    private static String inline(String text) {
        // 1. Pull inline `code` out so its contents aren't re-processed for bold/italic/etc.
        List<String> codeSlots = new ArrayList<>();
        StringBuilder pulled = new StringBuilder(text.length());
        Matcher cm = CODE_RE.matcher(text);
        int last = 0;
        while (cm.find()) {
            pulled.append(text, last, cm.start());
            pulled.append(CODE_OPEN).append(codeSlots.size()).append(CODE_CLOSE);
            codeSlots.add(cm.group(1));
            last = cm.end();
        }
        pulled.append(text, last, text.length());

        // 2. Escape everything. The sentinels survive escape() unchanged.
        String escaped = escape(pulled.toString());

        // 3. Re-apply markup on the escaped string.
        escaped = applyLinks(escaped);
        escaped = BOLD_RE.matcher(escaped).replaceAll("<strong>$1</strong>");
        escaped = ITAL_RE.matcher(escaped).replaceAll("<em>$1</em>");

        // 4. Restore code slots as <code> with escaped content.
        StringBuilder out = new StringBuilder(escaped.length() + 16);
        int i = 0;
        while (i < escaped.length()) {
            char ch = escaped.charAt(i);
            if (ch == CODE_OPEN) {
                int end = escaped.indexOf(CODE_CLOSE, i + 1);
                if (end < 0) { i++; continue; }
                int num;
                try { num = Integer.parseInt(escaped.substring(i + 1, end)); }
                catch (NumberFormatException nfe) { i++; continue; }
                if (num >= 0 && num < codeSlots.size()) {
                    out.append("<code>").append(escape(codeSlots.get(num))).append("</code>");
                }
                i = end + 1;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Render Markdown links as anchors, but only when the URL passes the http(s) check.
     * Anything else is left as escaped literal text so we never emit javascript:/data: hrefs.
     */
    private static String applyLinks(String escaped) {
        Matcher m = LINK_RE.matcher(escaped);
        StringBuilder sb = new StringBuilder(escaped.length() + 32);
        while (m.find()) {
            String label = m.group(1);
            String href  = m.group(2);
            if (URL_OK.matcher(href).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        "<a href=\"" + href + "\" target=\"_blank\" rel=\"noopener noreferrer\">"
                        + label + "</a>"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + label + "](" + href + ")"));
            }
        }
        m.appendTail(sb);
        return sb.toString();
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
}
