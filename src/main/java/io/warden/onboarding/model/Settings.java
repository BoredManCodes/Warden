package io.warden.onboarding.model;

import java.util.List;

/**
 * Top-level settings persisted in the singleton row of the `settings` table.
 * Flow config is nested so callers that only care about flow behaviour don't
 * have to thread the rest through. Landing config is nested for the same reason.
 */
public record Settings(
        String rulesMarkdown,
        String gatedRoleId,
        String fullRoleId,
        String modRoleId,
        String configAdminRoleId,
        String webManagerRoleId,
        String welcomeChannelId,
        String modReviewChannelId,
        String llmSystemPrompt,
        double llmAutoApproveThreshold,
        double llmAutoDenyThreshold,
        boolean llmAutoDenyEnabled,
        String llmApiKey,
        String llmBaseUrl,
        String llmModel,
        boolean geoipEnabled,
        String geoipLicenseKey,
        FlowConfig flow,
        LandingConfig landing
) {
    public static Settings blank() {
        return new Settings(
                "", "", "", "", "", "", "", "", "",
                0.85, 0.15,
                true,
                "", "https://app.manifest.build/v1", "auto",
                false, "",
                blankFlow(),
                blankLanding()
        );
    }

    public static FlowConfig blankFlow() {
        return new FlowConfig(
                /* deliveryViaDm */ false,
                /* deliveryViaChannel */ false,
                /* deliveryChannelId */ "",
                /* deliveryMessageTemplate */ "",
                /* entryViaDiscordButton */ true,
                /* entryViaWebCode */ true,
                /* entryViaWebOauth */ false,
                /* gatingEnabled */ false,
                /* triageMode */ TriageMode.MOD_ONLY,
                /* approveDmEnabled */ false,
                /* approveDmTemplate */ "",
                /* approveChannelAnnounce */ false,
                /* approveChannelTemplate */ "",
                /* approveExtraRoles */ List.of(),
                /* denyDmEnabled */ false,
                /* denyDmTemplate */ "",
                /* denyAction */ DenyAction.LEAVE_GATED
        );
    }

    public static LandingConfig blankLanding() {
        return new LandingConfig("enabled", "", "", "", "", "", false, "", "", "Live Map",
                "", "",
                "#39beff", "", "",
                "Players online", "Discord members",
                "", false,
                false, "Leaderboard", "Top members ranked by activity.", 25, "Leaderboard",
                "",
                defaultFeatures(), defaultFaqs());
    }

    public static List<LandingFeature> defaultFeatures() {
        return List.of(
                new LandingFeature("shield", "Land protection",
                        "Claim your builds with a simple in-game tool. No griefing, no rollbacks, no drama."),
                new LandingFeature("sun", "Quality of life",
                        "Sleep through nights together, set homes, teleport to friends, and use a tidy economy."),
                new LandingFeature("shop", "Player shops",
                        "Open a stall at spawn, sell what you craft, or just window-shop the latest builds."),
                new LandingFeature("globe", "Multiple modes",
                        "Hop between survival, SkyBlock, and seasonal events without leaving the server."),
                new LandingFeature("users", "Active community",
                        "Voice channels, weekly events, and staff who actually play on the server."),
                new LandingFeature("grid", "Fair and bot-checked",
                        "New joiners pass a quick onboarding step so the door stays open without letting raiders in.")
        );
    }

    public static List<LandingFaq> defaultFaqs() {
        return List.of(
                new LandingFaq("How do I connect?",
                        "Copy the address above, open Minecraft, add a new server, and paste it in. That is the whole process."),
                new LandingFaq("Which Minecraft version do you run?",
                        "The latest stable Paper release. Older clients usually connect fine thanks to ViaVersion."),
                new LandingFaq("Do I need to apply?",
                        "No application. First-time joiners run through a short onboarding step in Discord so we can keep raiders out."),
                new LandingFaq("Can I bring friends?",
                        "Always. Bring a whole guild if you like. Co-op projects make the server better.")
        );
    }

    public boolean llmConfigured() {
        return llmApiKey != null && !llmApiKey.isBlank();
    }
}
