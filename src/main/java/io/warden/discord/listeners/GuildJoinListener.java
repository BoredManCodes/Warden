package io.warden.discord.listeners;

import io.warden.discord.OnboardingDelivery;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * On guildMemberJoin: hands off to {@link OnboardingDelivery} which runs the
 * gate + link-code + DM/channel delivery. Kept thin so the dashboard /
 * /warden reonboard replay path can reuse the same pipeline.
 */
public final class GuildJoinListener extends ListenerAdapter {

    private final OnboardingDelivery delivery;

    public GuildJoinListener(OnboardingDelivery delivery) {
        this.delivery = delivery;
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        delivery.runFor(event.getGuild(), event.getMember(), OnboardingDelivery.Trigger.JOIN);
    }
}
