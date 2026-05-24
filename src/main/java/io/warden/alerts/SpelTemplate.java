package io.warden.alerts;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSRV-compatible {expression} template renderer. Each pair of braces is
 * extracted, parsed as a Spring Expression, and evaluated against the supplied
 * context. Failures fall back to the literal {expr} text so a typo in one
 * placeholder doesn't blank-out the rest of the message.
 *
 * Parsed expressions are cached so the second fire of the same alert template
 * is just an evaluation pass, no parse step.
 *
 * Nested braces are not supported; the regex matches the shortest run inside a
 * pair, which is enough for the property-access expressions DSRV alerts use in
 * practice ({event.player.name}, {server.onlinePlayers.size()}, ...).
 */
public final class SpelTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();

    private SpelTemplate() {}

    public static String render(String template, StandardEvaluationContext ctx) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        if (!m.find()) return template;
        StringBuilder out = new StringBuilder(template.length() + 32);
        do {
            String exprSrc = m.group(1).trim();
            String value;
            try {
                Expression expr = CACHE.computeIfAbsent(exprSrc, PARSER::parseExpression);
                Object result = expr.getValue(ctx);
                value = result == null ? "" : result.toString();
            } catch (Exception e) {
                // Keep the raw token so the operator can see what failed when they
                // look at the post in Discord, rather than getting silent gaps.
                value = "{" + exprSrc + "}";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Evaluate a SpEL boolean condition. Anything other than Boolean.TRUE
     * (including evaluation failures) counts as false, so conditions never
     * accidentally fire on a parse error.
     */
    public static boolean evaluateCondition(String expression, StandardEvaluationContext ctx) {
        if (expression == null) return true;
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) return true;
        try {
            Expression expr = CACHE.computeIfAbsent(trimmed, PARSER::parseExpression);
            Object result = expr.getValue(ctx);
            if (result instanceof Boolean b) return b;
            // Treat a non-boolean result as falsy unless it's a truthy primitive
            // shaped like "true"/"yes" - DSRV is strict about boolean conditions,
            // so mirror that.
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
