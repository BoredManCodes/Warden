package io.warden.onboarding;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateTest {

    @Test
    void substitutesKnownPlaceholders() {
        String out = Template.render(
                "Hi {username}, welcome to {guild}!",
                Map.of("username", "Alex", "guild", "Example"));
        assertEquals("Hi Alex, welcome to Example!", out);
    }

    @Test
    void unknownPlaceholderRendersAsEmpty() {
        String out = Template.render(
                "Code: {code}{ignored}",
                Map.of("code", "ABCD1234"));
        assertEquals("Code: ABCD1234", out);
    }

    @Test
    void escapedBraceIsLiteral() {
        String out = Template.render(
                "Json: \\{ \"x\": {x} }",
                Map.of("x", "1"));
        assertEquals("Json: { \"x\": 1 }", out);
    }

    @Test
    void replacementWithSpecialCharsIsLiteral() {
        String out = Template.render(
                "Path: {p}",
                Map.of("p", "C:\\Users\\$me"));
        assertEquals("Path: C:\\Users\\$me", out);
    }

    @Test
    void nullOrEmptyTemplateReturnsEmpty() {
        assertEquals("", Template.render(null, Map.of()));
        assertEquals("", Template.render("", Map.of()));
    }

    @Test
    void multilineTemplateIsPreserved() {
        String tpl = "line 1: {a}\nline 2: {b}";
        assertEquals("line 1: x\nline 2: y", Template.render(tpl, Map.of("a", "x", "b", "y")));
    }
}
