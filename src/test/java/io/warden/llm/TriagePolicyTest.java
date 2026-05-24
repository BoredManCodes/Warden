package io.warden.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TriagePolicyTest {

    private static final double APPROVE = 0.85;
    private static final double DENY = 0.15; // policy uses 1 - denyThreshold for the >= check

    @Test
    void highConfidenceApproveAutoApproves() {
        var out = TriagePolicy.apply(verdict(LlmVerdict.Decision.APPROVE, 0.9), APPROVE, DENY);
        assertEquals(TriagePolicy.FinalDecision.APPROVE, out.finalDecision());
        assertEquals("llm_auto", out.reasonCode());
    }

    @Test
    void borderlineConfidenceApproveEscalates() {
        var out = TriagePolicy.apply(verdict(LlmVerdict.Decision.APPROVE, 0.84), APPROVE, DENY);
        assertEquals(TriagePolicy.FinalDecision.ESCALATE, out.finalDecision());
        assertEquals("llm_uncertain", out.reasonCode());
    }

    @Test
    void highConfidenceDenyAutoDenies() {
        var out = TriagePolicy.apply(verdict(LlmVerdict.Decision.DENY, 0.9), APPROVE, DENY);
        assertEquals(TriagePolicy.FinalDecision.DENY, out.finalDecision());
        assertEquals("llm_auto", out.reasonCode());
    }

    @Test
    void mediumConfidenceDenyEscalates() {
        var out = TriagePolicy.apply(verdict(LlmVerdict.Decision.DENY, 0.7), APPROVE, DENY);
        assertEquals(TriagePolicy.FinalDecision.ESCALATE, out.finalDecision());
    }

    @Test
    void explicitEscalateReturnsEscalateWithItsReason() {
        var out = TriagePolicy.apply(verdict(LlmVerdict.Decision.ESCALATE, 0.4), APPROVE, DENY);
        assertEquals(TriagePolicy.FinalDecision.ESCALATE, out.finalDecision());
        assertEquals("llm_escalate", out.reasonCode());
    }

    @Test
    void thresholdsAreInclusive() {
        var out = TriagePolicy.apply(verdict(LlmVerdict.Decision.APPROVE, 0.85), APPROVE, DENY);
        assertEquals(TriagePolicy.FinalDecision.APPROVE, out.finalDecision());
    }

    private static LlmVerdict verdict(LlmVerdict.Decision d, double c) {
        return new LlmVerdict(d, c, "test reasoning");
    }
}
