package io.warden.onboarding.model;

/**
 * One card in the public landing's "Features" grid. Edited from the
 * dashboard Landing tab and persisted as a JSON array in the settings row.
 *
 * {@code icon} is a token from a curated palette ({@link #ICONS}) - the
 * landing template maps each token to an inline SVG. Anything outside the
 * palette renders as a generic dot so a bad value never breaks the page.
 */
public record LandingFeature(String icon, String title, String body) {

    public static final java.util.List<String> ICONS = java.util.List.of(
            "shield", "sun", "shop", "globe", "users", "grid", "sparkles",
            "sword", "pickaxe", "chat", "lock", "map", "trophy", "heart"
    );

    public LandingFeature {
        if (icon  == null) icon  = "";
        if (title == null) title = "";
        if (body  == null) body  = "";
    }
}
