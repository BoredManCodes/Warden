package io.warden.llm;

/**
 * Pure threshold policy applied to a parsed LLM verdict.
 *
 * - approve with confidence >= autoApprove → final approve
 * - deny    with confidence >= (1 - autoDeny) → final deny
 *   (mirrors the inverted shape from the Node plan: autoDeny=0.15 ⇒ require ≥0.85 confidence for an auto-deny)
 * - everything else → escalate (preserves the LLM's reasoning for the mod review queue)
 */
public final class TriagePolicy {

    public enum FinalDecision { APPROVE, DENY, ESCALATE }

    public record Outcome(FinalDecision finalDecision, String reasonCode, LlmVerdict verdict) {}

    private TriagePolicy() {}

    public static Outcome apply(LlmVerdict v, double autoApproveThreshold, double autoDenyThreshold) {
        if (v.decision() == LlmVerdict.Decision.APPROVE && v.confidence() >= autoApproveThreshold) {
            return new Outcome(FinalDecision.APPROVE, "llm_auto", v);
        }
        if (v.decision() == LlmVerdict.Decision.DENY && v.confidence() >= (1.0 - autoDenyThreshold)) {
            return new Outcome(FinalDecision.DENY, "llm_auto", v);
        }
        return new Outcome(FinalDecision.ESCALATE,
                v.decision() == LlmVerdict.Decision.ESCALATE ? "llm_escalate" : "llm_uncertain", v);
    }
}
