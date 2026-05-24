package io.warden.onboarding.model;

import java.util.List;

/**
 * Public-facing landing page settings. The landing replaces the setup
 * checklist at "/" once setup is complete and the visitor is not signed in.
 *
 * mode = "enabled"  - render the HTML template under plugins/Warden/www/
 *      = "disabled" - 404 at "/"; visitors must go to /dash or /onboard directly
 *      = "redirect" - 302 to {@link #redirectUrl()}; mods then need to remember
 *                     {public_url}/dash to reach the dashboard
 */
public record LandingConfig(
        String mode,
        String redirectUrl,
        String serverName,
        String serverAddress,
        String tagline,
        String joinUrl,
        boolean mapEnabled,
        String mapProvider,
        String mapUrl,
        String mapLabel,
        String brandImageUrl,
        String heroImageUrl,
        String accentColor,
        String papiPlayersOnline,
        String papiDiscordMembers,
        String statPlayersLabel,
        String statMembersLabel,
        String googleAnalyticsId,
        boolean cookieBannerEnabled,
        boolean leaderboardEnabled,
        String leaderboardTitle,
        String leaderboardDescription,
        int leaderboardTopN,
        String leaderboardLabel,
        String promoVideoUrl,
        List<LandingFeature> features,
        List<LandingFaq> faqs
) {
    public LandingConfig {
        features = features == null ? List.of() : List.copyOf(features);
        faqs     = faqs     == null ? List.of() : List.copyOf(faqs);
    }

    public boolean enabled()  { return "enabled".equalsIgnoreCase(mode); }
    public boolean redirect() { return "redirect".equalsIgnoreCase(mode)
            && redirectUrl != null && !redirectUrl.isBlank(); }
    public boolean disabled() { return "disabled".equalsIgnoreCase(mode); }

    /** True when the operator both flipped the toggle on and gave us a URL to embed. */
    public boolean mapReady() {
        return mapEnabled && mapUrl != null && !mapUrl.isBlank();
    }

    public String mapLabelOrDefault() {
        return (mapLabel == null || mapLabel.isBlank()) ? "Live Map" : mapLabel;
    }

    public String statPlayersLabelOrDefault() {
        return (statPlayersLabel == null || statPlayersLabel.isBlank()) ? "Players online" : statPlayersLabel;
    }

    public String statMembersLabelOrDefault() {
        return (statMembersLabel == null || statMembersLabel.isBlank()) ? "Discord members" : statMembersLabel;
    }

    public String leaderboardTitleOrDefault() {
        return (leaderboardTitle == null || leaderboardTitle.isBlank()) ? "Leaderboard" : leaderboardTitle;
    }

    public String leaderboardDescriptionOrDefault() {
        return (leaderboardDescription == null || leaderboardDescription.isBlank())
                ? "Top members ranked by activity." : leaderboardDescription;
    }

    public String leaderboardLabelOrDefault() {
        return (leaderboardLabel == null || leaderboardLabel.isBlank()) ? "Leaderboard" : leaderboardLabel;
    }

    public int leaderboardTopNOrDefault() {
        return leaderboardTopN <= 0 ? 25 : Math.min(leaderboardTopN, 200);
    }

    /**
     * Trimmed, validated GA measurement ID, or an empty string when not set or
     * not in a recognised format. Accepts the modern GA4 form (G-XXXXXXXXXX),
     * legacy Universal Analytics (UA-XXXX-Y) and Google Tag Manager (GTM-XXXXX)
     * ids since the same gtag bootstrap covers all three. Anything else is
     * treated as unset so we never inject an arbitrary attacker-controlled
     * string into the rendered <script src=...> URL.
     */
    public String googleAnalyticsIdOrEmpty() {
        String g = googleAnalyticsId == null ? "" : googleAnalyticsId.trim();
        if (g.isEmpty()) return "";
        return g.matches("^(G-|UA-|GTM-)[A-Za-z0-9\\-]{4,32}$") ? g : "";
    }

    /** True when {@link #googleAnalyticsIdOrEmpty()} returns a non-empty value. */
    public boolean googleAnalyticsEnabled() {
        return !googleAnalyticsIdOrEmpty().isEmpty();
    }

    /** Always returns a CSS-safe colour; falls back to the prior default if unset or malformed. */
    public String accentColorOrDefault() {
        String c = accentColor == null ? "" : accentColor.trim();
        if (c.matches("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")) return c;
        if (c.matches("^[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$")) return "#" + c;
        return "#39beff";
    }
}
