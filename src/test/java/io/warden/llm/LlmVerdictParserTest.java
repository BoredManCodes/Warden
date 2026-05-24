package io.warden.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmVerdictParserTest {

    @Test
    void parsesPlainJson() {
        var v = LlmVerdictParser.parse(
                "{\"decision\":\"approve\",\"confidence\":0.92,\"reasoning\":\"clear yes\"}");
        assertEquals(LlmVerdict.Decision.APPROVE, v.decision());
        assertEquals(0.92, v.confidence(), 0.0001);
        assertEquals("clear yes", v.reasoning());
    }

    @Test
    void parsesFencedJsonWithSurroundingProse() {
        String raw = "Here's the verdict:\n```json\n" +
                "{\"decision\":\"escalate\",\"confidence\":0.4,\"reasoning\":\"unclear intent\"}\n" +
                "```\nLet me know if you want more detail.";
        var v = LlmVerdictParser.parse(raw);
        assertEquals(LlmVerdict.Decision.ESCALATE, v.decision());
        assertEquals(0.4, v.confidence(), 0.0001);
    }

    @Test
    void parsesEmbeddedJsonWithoutFences() {
        var v = LlmVerdictParser.parse(
                "Verdict for the team: {\"decision\":\"deny\",\"confidence\":0.97,\"reasoning\":\"slur in answer 1\"} - be firm.");
        assertEquals(LlmVerdict.Decision.DENY, v.decision());
        assertEquals(0.97, v.confidence(), 0.0001);
    }

    @Test
    void falsyDecisionFallsBackToEscalate() {
        var v = LlmVerdictParser.parse(
                "{\"decision\":\"approve!\",\"confidence\":0.9,\"reasoning\":\"oops typo\"}");
        // unknown decision word → ESCALATE
        assertEquals(LlmVerdict.Decision.ESCALATE, v.decision());
    }

    @Test
    void garbageReturnsFallbackEscalate() {
        var v = LlmVerdictParser.parse("totally not json");
        assertEquals(LlmVerdict.Decision.ESCALATE, v.decision());
        assertEquals(0.0, v.confidence(), 0.0001);
        assertTrue(v.reasoning().toLowerCase().contains("parser fell back"),
                "fallback reasoning should explain itself: " + v.reasoning());
    }

    @Test
    void nullOrBlankReturnsFallbackEscalate() {
        var v1 = LlmVerdictParser.parse(null);
        var v2 = LlmVerdictParser.parse("   ");
        assertEquals(LlmVerdict.Decision.ESCALATE, v1.decision());
        assertEquals(LlmVerdict.Decision.ESCALATE, v2.decision());
    }

    @Test
    void confidenceClampedToZeroOne() {
        var hi = LlmVerdictParser.parse("{\"decision\":\"approve\",\"confidence\":42,\"reasoning\":\"oops\"}");
        assertEquals(1.0, hi.confidence(), 0.0001);
        var lo = LlmVerdictParser.parse("{\"decision\":\"deny\",\"confidence\":-0.5,\"reasoning\":\"oops\"}");
        assertEquals(0.0, lo.confidence(), 0.0001);
    }

    @Test
    void longReasoningTruncated() {
        String long2k = "x".repeat(2500);
        var v = LlmVerdictParser.parse(
                "{\"decision\":\"approve\",\"confidence\":0.9,\"reasoning\":\"" + long2k + "\"}");
        assertTrue(v.reasoning().length() <= 2001, "reasoning should be clipped: " + v.reasoning().length());
    }
}
