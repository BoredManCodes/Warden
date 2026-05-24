package io.warden.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

public final class WardenEmbeds {

    public static final String BRAND_FOOTER = "Powered By Warden";
    private static final String SEPARATOR = " • ";

    private WardenEmbeds() {}

    public static EmbedBuilder brand(EmbedBuilder eb) {
        if (eb == null || eb.isEmpty()) return eb;
        MessageEmbed.Footer existing = eb.build().getFooter();
        String existingText = existing != null ? existing.getText() : null;
        String existingIcon = existing != null ? existing.getIconUrl() : null;
        if (existingText == null || existingText.isBlank()) {
            eb.setFooter(BRAND_FOOTER, existingIcon);
        } else if (!existingText.contains(BRAND_FOOTER)) {
            String combined = existingText + SEPARATOR + BRAND_FOOTER;
            if (combined.length() > 2048) {
                combined = combined.substring(0, 2048);
            }
            eb.setFooter(combined, existingIcon);
        }
        return eb;
    }
}
