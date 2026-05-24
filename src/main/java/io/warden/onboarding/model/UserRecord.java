package io.warden.onboarding.model;

import io.warden.onboarding.OnboardingState;

public record UserRecord(
        String discordId,
        String username,
        long joinedAt,
        OnboardingState state,
        String webSessionId,
        long updatedAt
) {}
