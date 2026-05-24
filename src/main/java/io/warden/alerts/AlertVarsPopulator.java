package io.warden.alerts;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fills the standard {player}/{player_uuid}/{world}/... variables into an
 * {@link AlertContext} based on the runtime type of a Bukkit event. Anything
 * we don't recognise still gets the raw event attached for SpEL access
 * ({event.someField}), so dynamic-event alerts work even without a
 * hand-written populator entry.
 */
public final class AlertVarsPopulator {

    private AlertVarsPopulator() {}

    public static void populate(Event event, AlertContext ctx) {
        if (event == null || ctx == null) return;
        ctx.event(event);

        Player player = null;
        if (event instanceof PlayerEvent pe) {
            player = pe.getPlayer();
        } else if (event instanceof PlayerDeathEvent pde) {
            // PlayerDeathEvent extends EntityDeathEvent, not PlayerEvent.
            player = pde.getEntity();
            String killerName = pde.getEntity().getKiller() == null
                    ? "" : pde.getEntity().getKiller().getName();
            String deathMessage = "";
            try {
                Component msg = pde.deathMessage();
                if (msg != null) deathMessage = PlainTextComponentSerializer.plainText().serialize(msg);
            } catch (Throwable ignored) {}
            ctx.set("death_message", deathMessage)
                    .set("killer", killerName);
        }

        if (player != null) {
            ctx.player(player)
                    .set("player", player.getName())
                    .set("player_uuid", player.getUniqueId().toString())
                    .set("player_display", player.getName())
                    .set("world", player.getWorld() == null ? "" : player.getWorld().getName());
            try {
                ctx.set("player_display",
                        PlainTextComponentSerializer.plainText().serialize(player.displayName()));
            } catch (Throwable ignored) {}
        }

        if (event instanceof PlayerAdvancementDoneEvent pade) {
            Advancement adv = pade.getAdvancement();
            AdvancementDisplay display = null;
            try { display = adv.getDisplay(); } catch (Throwable ignored) {}
            String name;
            if (display != null) {
                try { name = PlainTextComponentSerializer.plainText().serialize(display.title()); }
                catch (Throwable t) { name = adv.getKey().toString(); }
            } else {
                name = adv.getKey().toString();
            }
            ctx.set("advancement", name);
            ctx.set("has_display", display != null ? "true" : "false");
        }

        if (event instanceof PlayerChangedWorldEvent pcw) {
            ctx.set("from_world", pcw.getFrom() == null ? "" : pcw.getFrom().getName());
        }

        if (event instanceof AsyncChatEvent ace) {
            try {
                ctx.set("message", PlainTextComponentSerializer.plainText().serialize(ace.message()));
            } catch (Throwable ignored) {
                ctx.set("message", "");
            }
        }
    }
}
