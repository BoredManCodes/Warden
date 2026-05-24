package io.warden.onboarding.model;

/**
 * One entry in the public landing's FAQ accordion. Edited from the dashboard
 * Landing tab and persisted as a JSON array in the settings row.
 */
public record LandingFaq(String question, String answer) {
    public LandingFaq {
        if (question == null) question = "";
        if (answer   == null) answer   = "";
    }
}
