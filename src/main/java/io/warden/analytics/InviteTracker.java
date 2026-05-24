package io.warden.analytics;

import io.warden.data.dao.InviteDao;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Invite;
import net.dv8tion.jda.api.events.guild.invite.GuildInviteCreateEvent;
import net.dv8tion.jda.api.events.guild.invite.GuildInviteDeleteEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Snapshots the configured guild's invite uses-counts so we can attribute each
 * new member join to the invite they used. The flow:
 *   - On bot ready: pull every active invite, upsert into the invites table,
 *     and prime the in-memory snapshot.
 *   - On member join: retrieveInvites again; whichever code's uses bumped is
 *     the one used. Stamp that code onto the most recent member-events row
 *     for the joining user, then refresh the snapshot.
 *   - On invite create / delete: keep the table and snapshot in sync.
 *
 * Joins from server-discovery, vanity URLs, or direct bot-add show up with a
 * null invite_code; that's expected.
 */
public final class InviteTracker extends ListenerAdapter {

    /** Look-back when stamping invite_code onto the join row: any join in the last 60s. */
    private static final long ATTRIBUTION_LOOKBACK_MS = 60_000L;

    private final InviteDao invites;
    private final String configuredGuildId;
    private final ExecutorService bgExecutor;
    private final Logger log;

    private final Map<String, Integer> snapshot = new ConcurrentHashMap<>();

    public InviteTracker(InviteDao invites, String configuredGuildId,
                         ExecutorService bgExecutor, Logger log) {
        this.invites = invites;
        this.configuredGuildId = configuredGuildId == null ? "" : configuredGuildId;
        this.bgExecutor = bgExecutor;
        this.log = log;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        if (configuredGuildId.isBlank()) {
            log.warning("invites: no guild configured; snapshot will be empty until a guild id is set");
            return;
        }
        Guild guild = event.getJDA().getGuildById(configuredGuildId);
        if (guild == null) {
            log.warning("invites: configured guild " + configuredGuildId
                    + " not visible to the bot; is it actually a member of that guild?");
            return;
        }
        guild.retrieveInvites().queue(list -> {
            for (Invite inv : list) {
                primeFromJda(inv);
            }
            log.info("invites: primed snapshot with " + list.size() + " invite(s)");
        }, err -> log.log(Level.WARNING, "invites: failed to prime snapshot (does the bot have MANAGE_GUILD?)", err));
    }

    @Override
    public void onGuildInviteCreate(@NotNull GuildInviteCreateEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        Invite inv = event.getInvite();
        primeFromJda(inv);
    }

    @Override
    public void onGuildInviteDelete(@NotNull GuildInviteDeleteEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        String code = event.getCode();
        snapshot.remove(code);
        bgExecutor.submit(() -> {
            try {
                invites.markDeleted(code);
            } catch (Exception e) {
                log.log(Level.FINE, "invites: markDeleted failed for " + code, e);
            }
        });
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        if (!isThisGuild(event.getGuild().getId())) return;
        String discordId = event.getUser().getId();
        long joinedAt = event.getMember().getTimeJoined().toInstant().toEpochMilli();
        Map<String, Integer> previous = new HashMap<>(snapshot);

        event.getGuild().retrieveInvites().queue(list -> {
            String usedCode = null;
            for (Invite inv : list) {
                int now = inv.getUses();
                int prev = previous.getOrDefault(inv.getCode(), 0);
                if (now > prev && usedCode == null) {
                    usedCode = inv.getCode();
                }
                // Refresh snapshot + DB regardless of which one matched.
                primeFromJda(inv);
            }

            if (usedCode == null) {
                log.fine("invites: join by " + discordId + " not matched to any tracked invite "
                        + "(vanity / discovery / direct add)");
                return;
            }

            final String matched = usedCode;
            bgExecutor.submit(() -> {
                try {
                    long sinceMs = joinedAt - ATTRIBUTION_LOOKBACK_MS;
                    long rowId = invites.latestJoinEventId(discordId, sinceMs);
                    if (rowId < 0) {
                        log.fine("invites: no member_events join row found for " + discordId
                                + " in the last " + (ATTRIBUTION_LOOKBACK_MS / 1000) + "s");
                        return;
                    }
                    invites.attributeJoin(rowId, matched);
                } catch (Exception e) {
                    log.log(Level.FINE, "invites: attribution write failed", e);
                }
            });
        }, err -> log.log(Level.FINE, "invites: retrieveInvites on join failed", err));
    }

    /** Update both the in-memory snapshot and the DB row from a JDA Invite. */
    private void primeFromJda(Invite inv) {
        snapshot.put(inv.getCode(), inv.getUses());
        // JDA exposes max age in seconds (0 = never expires) instead of an absolute expiry timestamp.
        Long expires = null;
        int maxAge = inv.getMaxAge();
        if (maxAge > 0) {
            OffsetDateTime created = inv.getTimeCreated();
            if (created != null) {
                expires = created.toInstant().toEpochMilli() + maxAge * 1000L;
            }
        }
        final Long expiresAt = expires;
        final String code = inv.getCode();
        final int uses = inv.getUses();
        final int maxUses = inv.getMaxUses();
        final String channelId = inv.getChannel() == null ? null : inv.getChannel().getId();
        final String inviterId = inv.getInviter() == null ? null : inv.getInviter().getId();
        final String resolvedGuildId = Objects.requireNonNullElse(
                inv.getGuild() == null ? null : inv.getGuild().getId(), configuredGuildId);
        bgExecutor.submit(() -> {
            try {
                invites.upsertFromSnapshot(code, resolvedGuildId, channelId, inviterId,
                        uses, maxUses, expiresAt);
            } catch (Exception e) {
                log.log(Level.FINE, "invites: upsert failed for " + code, e);
            }
        });
    }

    private boolean isThisGuild(String guildId) {
        return configuredGuildId.isBlank() || configuredGuildId.equals(guildId);
    }
}
