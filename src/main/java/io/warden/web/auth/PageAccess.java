package io.warden.web.auth;

import io.javalin.http.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-page permission check for /dash/* surfaces.
 *
 * Rules:
 *   1. {@link SessionCookie.Session#owner()} always passes.
 *   2. {@link SessionCookie.Session#configAdmin()} always passes (they manage everything).
 *   3. If the page has an explicit allowlist of role IDs, the user passes if
 *      any of their role IDs is in it.
 *   4. Otherwise (empty allowlist, or the row doesn't exist), the page falls
 *      back to the legacy mod gate: {@link SessionCookie.Session#mod()}.
 *
 * The /dash/config surface stays gated by canEditLanding/canEditConfig in
 * WebService - PageAccess is for the other pages.
 *
 * Defined page keys mirror the navItem keys in {@link io.warden.web.handlers.Layout}.
 */
public final class PageAccess {

    public static final List<String> KEYS = List.of(
            "stats", "pending", "audit", "members", "invites",
            "moderation", "violations", "levels", "reaction-roles",
            "engagement", "tickets", "feedback", "alerts", "autoresponders", "scheduler"
    );

    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("stats", "Stats"),
            Map.entry("pending", "Pending"),
            Map.entry("audit", "Audit"),
            Map.entry("members", "Members"),
            Map.entry("invites", "Invites"),
            Map.entry("moderation", "Moderation"),
            Map.entry("violations", "Violations"),
            Map.entry("levels", "Levels and XP"),
            Map.entry("reaction-roles", "Reaction roles"),
            Map.entry("engagement", "Polls and giveaways"),
            Map.entry("tickets", "Tickets"),
            Map.entry("feedback", "Feedback"),
            Map.entry("alerts", "Alerts"),
            Map.entry("autoresponders", "Autoresponders"),
            Map.entry("scheduler", "Event scheduler")
    );

    private final PageAccessDao dao;
    private final Logger log;

    public PageAccess(PageAccessDao dao, Logger log) {
        this.dao = dao;
        this.log = log;
    }

    public boolean canAccess(SessionCookie.Session session, String pageKey) {
        if (session == null) return false;
        if (session.owner() || session.configAdmin()) return true;
        List<String> allowed;
        try {
            allowed = dao.rolesFor(pageKey);
        } catch (Exception e) {
            log.log(Level.WARNING, "page_access lookup failed for " + pageKey + ": " + e.getMessage());
            return session.mod();
        }
        if (allowed == null || allowed.isEmpty()) return session.mod();
        for (String rid : session.roleIdsOrEmpty()) {
            if (allowed.contains(rid)) return true;
        }
        // Even when an explicit allowlist is set, keep the legacy mod role
        // working unless an admin explicitly listed it (treat mod as the
        // default-on baseline). Granular admins should override by
        // configuring the mod role into the allowlist explicitly too.
        // -> No automatic mod fallback when allowlist exists.
        return false;
    }

    public boolean canAccess(Context ctx, String pageKey) {
        Optional<SessionCookie.Session> s = DashAuth.sessionOf(ctx);
        return s.isPresent() && canAccess(s.get(), pageKey);
    }
}
