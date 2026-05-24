package io.warden.onboarding.model;

import java.util.List;

public record Question(
        long id,
        int order,
        String prompt,
        QuestionKind kind,
        List<String> choices,
        boolean required,
        boolean active
) {}
