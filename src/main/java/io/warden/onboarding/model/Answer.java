package io.warden.onboarding.model;

public record Answer(
        long id,
        String discordId,
        long questionId,
        AnswerValue value,
        long submittedAt
) {}
