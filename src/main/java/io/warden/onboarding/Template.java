package io.warden.onboarding;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight string-template renderer used for delivery/approve/deny messages.
 *
 * Placeholders take the form {key}. Unknown placeholders render as the empty
 * string (a deliberate choice - message templates should degrade gracefully
 * rather than blowing up at runtime with leaked {raw} tokens).
 *
 * Use \{ to render a literal '{' if a template happens to contain one for
 * non-placeholder reasons.
 */
public final class Template {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\\\\\{|\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private Template() {}

    public static String render(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (m.find()) {
            String escaped = m.group();
            if ("\\{".equals(escaped)) {
                m.appendReplacement(out, "{");
                continue;
            }
            String key = m.group(1);
            String value = vars == null ? null : vars.get(key);
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
