package io.warden.onboarding.model;

public record Application(
        long id,
        String discordId,
        long submittedAt,
        String llmDecision,           // approve | deny | escalate | null
        Integer llmConfidenceX1000,   // 0..1000 or null
        String llmReasoning,          // nullable
        String finalDecision,         // approve | deny | null (pending mod)
        String decidedBy,             // mod discord id, "llm", or null
        Long decidedAt,
        String modNote,
        String modMessageId
) {
    public boolean pendingModReview() {
        return finalDecision == null;
    }
}
