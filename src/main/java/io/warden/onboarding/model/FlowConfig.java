package io.warden.onboarding.model;

import java.util.List;

/**
 * Server-owner-tunable flow configuration. All 7 customisability axes live here
 * so the runtime can dispatch behavior off a single struct.
 */
public record FlowConfig(
        // Axis 1: Delivery
        boolean deliveryViaDm,
        boolean deliveryViaChannel,
        String deliveryChannelId,
        String deliveryMessageTemplate,

        // Axis 2: Entry methods
        boolean entryViaDiscordButton,
        boolean entryViaWebCode,
        boolean entryViaWebOauth,

        // Axis 3: Gating
        boolean gatingEnabled,

        // Axis 4: Triage
        TriageMode triageMode,

        // Axis 5: Approve actions
        boolean approveDmEnabled,
        String approveDmTemplate,
        boolean approveChannelAnnounce,
        String approveChannelTemplate,
        List<String> approveExtraRoles,

        // Axis 6: Deny actions
        boolean denyDmEnabled,
        String denyDmTemplate,
        DenyAction denyAction
) {
    /** True if any entry path is enabled - otherwise the user has no way to start onboarding. */
    public boolean anyEntryEnabled() {
        return entryViaDiscordButton || entryViaWebCode || entryViaWebOauth;
    }

    /** True if any delivery channel is enabled. */
    public boolean anyDeliveryEnabled() {
        return deliveryViaDm || deliveryViaChannel;
    }
}
