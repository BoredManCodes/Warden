package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.discord.DiscordService;
import io.warden.timezone.EventRsvp;
import io.warden.timezone.ScheduledEvent;
import io.warden.timezone.TimezoneConfig;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.SessionCookie;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * /dash/scheduler. Staff create events here. Each event has a UTC instant
 * plus optional target Discord roles; the detail page shows RSVPs and
 * each respondent's local time so staff can pick a slot that catches the
 * most of the people they care about.
 */
public final class DashSchedulerHandlers {

    private final Services services;
    private final DiscordService discord;
    private final GuildLookup lookup;

    public DashSchedulerHandlers(Services services, DiscordService discord, GuildLookup lookup) {
        this.services = services;
        this.discord = discord;
        this.lookup = lookup;
    }

    // -------------------------------------------------------------------
    // List page + admin config (settings live on the top of this page)
    // -------------------------------------------------------------------

    public void page(Context ctx) throws Exception {
        TimezoneConfig cfg = services.timezones.config();
        long now = System.currentTimeMillis();
        List<ScheduledEvent> upcoming = services.timezones.events().listUpcoming(now);
        List<ScheduledEvent> all = services.timezones.events().listAll(50);

        // Each list item shows total RSVPs; fetch counts in one go.
        Map<Long, Map<String, Integer>> countsByEvent = new HashMap<>();
        for (ScheduledEvent ev : all) {
            try { countsByEvent.put(ev.id(), services.timezones.rsvps().countsFor(ev.id())); }
            catch (Exception ignored) { countsByEvent.put(ev.id(), Map.of()); }
        }

        String tab = ctx.queryParam("tab");
        if (tab == null || tab.isBlank()) tab = "events";

        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        boolean canConfigure = sess != null && sess.canEditConfig();

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Event scheduler · Warden", "scheduler", ctx));
        h.append("<div class=\"d-flex justify-content-between align-items-start mb-3\">");
        h.append("<div>");
        h.append("<h1 class=\"h3 mb-1\">Event scheduler</h1>");
        h.append("<p class=\"text-secondary mb-0\">Schedule events in UTC. Players see them in their own timezone "
                + "and can RSVP from <a href=\"/tz/events\">/tz/events</a>.</p>");
        h.append("</div>");
        h.append("<a class=\"btn btn-primary\" href=\"/dash/scheduler/new\"><i class=\"bi bi-plus-lg me-1\"></i>New event</a>");
        h.append("</div>");

        if (!cfg.schedulerEnabled()) {
            h.append("<div class=\"alert alert-warning\">The scheduler is disabled. Players won't see the events list. ")
                    .append(canConfigure ? "Use the Settings tab below to turn it back on." : "Ask a config admin to enable it.")
                    .append("</div>");
        }

        String flash = ctx.queryParam("flash");
        if (flash != null && !flash.isBlank()) {
            String kind = ctx.queryParam("flash_kind");
            h.append("<div class=\"alert alert-").append(Layout.escape(kind == null ? "info" : kind))
                    .append("\" role=alert>").append(Layout.escape(flash)).append("</div>");
        }

        h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-3\">Upcoming (").append(upcoming.size()).append(")</h2>");
        h.append("<div class=\"table-responsive\"><table class=\"table align-middle\">");
        h.append("<thead><tr><th>Title</th><th>Start (UTC)</th><th>Duration</th>"
                + "<th>Going / Maybe / No</th><th>Target roles</th><th></th></tr></thead><tbody>");
        if (upcoming.isEmpty()) {
            h.append("<tr><td colspan=6 class=\"text-secondary text-center py-3\">Nothing scheduled. ")
                    .append("Create one with the button above.</td></tr>");
        }
        for (ScheduledEvent ev : upcoming) {
            renderRow(h, ev, countsByEvent.getOrDefault(ev.id(), Map.of()));
        }
        h.append("</tbody></table></div>");
        h.append("</div></div>");

        // History (cancelled / past)
        h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-3\">All events</h2>");
        h.append("<div class=\"small text-secondary mb-2\">Showing the 50 most recent. Past and cancelled events are kept here for reference.</div>");
        h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle\">");
        h.append("<thead><tr><th>#</th><th>Title</th><th>When (UTC)</th><th>Status</th><th>RSVPs</th></tr></thead><tbody>");
        for (ScheduledEvent ev : all) {
            String status;
            if (ev.cancelledAt() != null) status = "<span class=\"badge bg-secondary\">cancelled</span>";
            else if (ev.endsAtUtc() < now) status = "<span class=\"badge bg-secondary\">past</span>";
            else if (ev.startsAtUtc() < now) status = "<span class=\"badge bg-success\">live</span>";
            else status = "<span class=\"badge bg-primary\">upcoming</span>";
            Map<String, Integer> co = countsByEvent.getOrDefault(ev.id(), Map.of());
            int going = co.getOrDefault(EventRsvp.GOING, 0);
            int maybe = co.getOrDefault(EventRsvp.MAYBE, 0);
            int no = co.getOrDefault(EventRsvp.NO, 0);
            h.append("<tr><td>").append(ev.id()).append("</td>")
                    .append("<td><a href=\"/dash/scheduler/").append(ev.id()).append("\">")
                    .append(Layout.escape(ev.title())).append("</a></td>")
                    .append("<td>").append(formatUtc(ev.startsAtUtc())).append("</td>")
                    .append("<td>").append(status).append("</td>")
                    .append("<td>").append(going).append(" / ").append(maybe).append(" / ").append(no).append("</td></tr>");
        }
        if (all.isEmpty()) {
            h.append("<tr><td colspan=5 class=\"text-secondary text-center py-3\">No events yet.</td></tr>");
        }
        h.append("</tbody></table></div>");
        h.append("</div></div>");

        // Settings card. canEditConfig keeps the geoip + onboarding toggles
        // gated to admins so mods can still create events without seeing
        // the privacy-relevant switches.
        h.append("<div class=\"card\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-3\">Timezone settings</h2>");
        if (!canConfigure) {
            h.append("<p class=\"text-secondary mb-0\">Only the server owner or a config admin can change these.</p>");
        } else {
            h.append("<form method=post action=\"/dash/scheduler/settings\">");
            h.append("<div class=\"form-check form-switch mb-2\">")
                    .append("<input class=form-check-input type=checkbox id=opt-required name=onboarding_required ")
                    .append(cfg.onboardingRequired() ? "checked" : "").append(">")
                    .append("<label class=form-check-label for=opt-required>")
                    .append("<strong>Require timezone during onboarding</strong>")
                    .append(Layout.infoIcon("When on, any signed-in user without a saved timezone is redirected to /tz before reaching the rest of the dashboard."))
                    .append("</label></div>");
            h.append("<div class=\"form-check form-switch mb-2\">")
                    .append("<input class=form-check-input type=checkbox id=opt-geoip name=geoip_enabled ")
                    .append(cfg.geoipEnabled() ? "checked" : "").append(">")
                    .append("<label class=form-check-label for=opt-geoip>")
                    .append("<strong>Pre-select via GeoIP</strong>")
                    .append(Layout.infoIcon("Uses the analytics GeoIP lookup to guess the user's country and seed the picker. The guess is only a hint; the user still has to confirm."))
                    .append("</label></div>");
            h.append("<div class=\"form-check form-switch mb-3\">")
                    .append("<input class=form-check-input type=checkbox id=opt-sched name=scheduler_enabled ")
                    .append(cfg.schedulerEnabled() ? "checked" : "").append(">")
                    .append("<label class=form-check-label for=opt-sched>")
                    .append("<strong>Event scheduler enabled</strong>")
                    .append(Layout.infoIcon("Turn off to hide /tz/events and the staff page entirely. Existing events are kept but invisible."))
                    .append("</label></div>");
            h.append("<button class=\"btn btn-primary\" type=submit>Save settings</button>");
            h.append("</form>");
        }
        h.append("</div></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void saveSettings(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(403).result("config admin only");
            return;
        }
        boolean required = "on".equals(ctx.formParam("onboarding_required")) || "true".equals(ctx.formParam("onboarding_required"));
        boolean geoip = "on".equals(ctx.formParam("geoip_enabled")) || "true".equals(ctx.formParam("geoip_enabled"));
        boolean schedEnabled = "on".equals(ctx.formParam("scheduler_enabled")) || "true".equals(ctx.formParam("scheduler_enabled"));
        services.timezones.configs().save(new TimezoneConfig(required, geoip, schedEnabled));
        services.audit.write("web:" + sess.discordId(), "timezone_settings_saved", null,
                Map.of("onboardingRequired", required, "geoipEnabled", geoip, "schedulerEnabled", schedEnabled));
        ctx.redirect("/dash/scheduler?flash=Settings+saved.&flash_kind=success");
    }

    // -------------------------------------------------------------------
    // Create / edit / detail
    // -------------------------------------------------------------------

    public void newForm(Context ctx) throws Exception {
        renderForm(ctx, null);
    }

    public void editForm(Context ctx) throws Exception {
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); }
        catch (NumberFormatException nfe) { ctx.status(404); return; }
        Optional<ScheduledEvent> ev = services.timezones.events().find(id);
        if (ev.isEmpty()) { ctx.status(404).html("<h1>404</h1>"); return; }
        renderForm(ctx, ev.get());
    }

    private void renderForm(Context ctx, ScheduledEvent ev) {
        var roleOpts = lookup.roles();
        boolean editing = ev != null;

        String defaultStartInput;
        String defaultZone;
        int defaultDuration;
        String defaultTitle, defaultDesc;
        List<String> defaultRoles;
        if (editing) {
            ZoneId utc = ZoneId.of("UTC");
            defaultStartInput = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ev.startsAtUtc()), utc)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            defaultZone = "UTC";
            defaultDuration = ev.durationMinutes();
            defaultTitle = ev.title();
            defaultDesc = ev.description();
            defaultRoles = ev.targetRoles();
        } else {
            ZoneId utc = ZoneId.of("UTC");
            defaultStartInput = ZonedDateTime.now(utc).plusDays(1).withMinute(0).withSecond(0)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            defaultZone = "UTC";
            defaultDuration = 60;
            defaultTitle = "";
            defaultDesc = "";
            defaultRoles = List.of();
        }

        String action = editing ? ("/dash/scheduler/" + ev.id()) : "/dash/scheduler/new";

        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head((editing ? "Edit event" : "New event") + " · Warden", "scheduler", ctx));
        h.append("<div class=\"d-flex justify-content-between align-items-center mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">").append(editing ? "Edit event" : "New event").append("</h1>");
        h.append("<a class=\"btn btn-outline-secondary btn-sm\" href=\"/dash/scheduler\">Back to list</a>");
        h.append("</div>");
        h.append("<form method=post action=\"").append(action).append("\" class=\"card\"><div class=\"card-body\">");

        // AI brief -> auto-fills title and description. Optional - operators
        // can still type manually.
        h.append("<div class=\"mb-3 p-3 rounded border bg-body-tertiary\">");
        h.append("<div class=\"d-flex align-items-center gap-2 mb-2\">")
                .append("<i class=\"bi bi-stars text-warning\"></i>")
                .append("<strong>AI helper</strong>")
                .append("<span class=\"text-secondary small\">describe the event and let the AI fill in title + description.</span>")
                .append("</div>");
        h.append("<div class=\"input-group input-group-sm\">")
                .append("<input id=\"ai-event-brief\" class=\"form-control\" type=\"text\" maxlength=\"400\" ")
                .append("placeholder=\"e.g. friday night build challenge with a redstone theme\">")
                .append("<button type=\"button\" id=\"ai-event-go\" class=\"btn btn-outline-primary\">")
                .append("<i class=\"bi bi-magic me-1\"></i>Draft</button>")
                .append("</div>");
        h.append("<div id=\"ai-event-status\" class=\"small text-secondary mt-1\"></div>");
        h.append("</div>");

        h.append("<div class=\"mb-3\"><label class=\"form-label\">Title</label>")
                .append("<input id=\"event-title\" class=\"form-control\" name=\"title\" required maxlength=200 value=\"")
                .append(Layout.escape(defaultTitle)).append("\"></div>");
        h.append("<div class=\"mb-3\"><label class=\"form-label\">Description</label>")
                .append("<textarea id=\"event-description\" class=\"form-control\" name=\"description\" rows=3 maxlength=2000>")
                .append(Layout.escape(defaultDesc)).append("</textarea></div>");
        h.append("<div class=\"row g-3\">");
        h.append("<div class=\"col-md-5\"><label class=\"form-label\">Start (in your input timezone)</label>")
                .append("<input class=\"form-control\" type=\"datetime-local\" name=\"start\" required value=\"")
                .append(Layout.escape(defaultStartInput)).append("\"></div>");
        h.append("<div class=\"col-md-4\"><label class=\"form-label\">Input timezone</label>")
                .append("<select class=\"form-select\" name=\"input_zone\">");
        for (String z : commonZones()) {
            h.append("<option value=\"").append(Layout.escape(z)).append("\"");
            if (z.equals(defaultZone)) h.append(" selected");
            h.append(">").append(Layout.escape(z)).append("</option>");
        }
        h.append("</select></div>");
        h.append("<div class=\"col-md-3\"><label class=\"form-label\">Duration (minutes)</label>")
                .append("<input class=\"form-control\" type=number name=\"duration\" min=5 max=1440 value=\"")
                .append(defaultDuration).append("\"></div>");
        h.append("</div>");

        h.append("<div class=\"mt-3\">");
        h.append(GuildLookup.multiCheckField("target_roles",
                "Target roles (optional - everyone with a timezone set sees the event if left empty)",
                roleOpts, defaultRoles,
                "If you pick roles, the staff RSVP breakdown filters to members with those roles. Players still see the event on /tz/events regardless."));
        h.append("</div>");

        h.append("<div class=\"d-flex gap-2 mt-3\">");
        h.append("<button class=\"btn btn-primary\" type=submit><i class=\"bi bi-check2 me-1\"></i>")
                .append(editing ? "Save changes" : "Schedule event").append("</button>");
        if (editing) {
            h.append("<form method=post action=\"/dash/scheduler/").append(ev.id())
                    .append("/cancel\" class=\"m-0\" data-confirm=\"Cancel this event? Participants will see it disappear from their list.\">")
                    .append("<button class=\"btn btn-outline-warning\" type=submit>Cancel event</button></form>");
            h.append("<form method=post action=\"/dash/scheduler/").append(ev.id())
                    .append("/delete\" class=\"m-0\" data-confirm=\"Delete this event permanently? RSVPs are removed too.\" data-confirm-kind=\"danger\">")
                    .append("<button class=\"btn btn-outline-danger\" type=submit>Delete</button></form>");
        }
        h.append("</div>");
        h.append("</div></form>");

        h.append("<script>(function(){")
                .append("var go=document.getElementById('ai-event-go');")
                .append("var brIn=document.getElementById('ai-event-brief');")
                .append("var stat=document.getElementById('ai-event-status');")
                .append("var title=document.getElementById('event-title');")
                .append("var desc=document.getElementById('event-description');")
                .append("if(!go)return;")
                .append("go.addEventListener('click',function(){")
                .append("var brief=(brIn.value||'').trim();")
                .append("if(!brief&&!(title&&title.value)){stat.textContent='Type a short brief first.';return;}")
                .append("go.disabled=true;stat.textContent='Asking the AI...';")
                .append("var b=new URLSearchParams();b.set('brief',brief);")
                .append("if(title)b.set('title',(title.value||'').trim());")
                .append("fetch('/dash/ai/event-draft',{method:'POST',credentials:'same-origin',")
                .append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString()})")
                .append(".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})")
                .append(".then(function(j){")
                .append("if(!j||!j.ok){stat.textContent='Failed: '+((j&&j.message)||'unknown');return;}")
                .append("if(title&&j.title)title.value=j.title;")
                .append("if(desc&&j.description)desc.value=j.description;")
                .append("stat.textContent='Drafted. Review before saving.';")
                .append("})")
                .append(".catch(function(e){stat.textContent='Network error: '+(e.message||e);})")
                .append(".finally(function(){go.disabled=false;});")
                .append("});")
                .append("})();</script>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void save(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null) { ctx.status(401); return; }

        Long editId = null;
        String idPath = ctx.pathParamMap().get("id");
        if (idPath != null && !idPath.isBlank()) {
            try { editId = Long.parseLong(idPath); }
            catch (NumberFormatException nfe) { ctx.status(404); return; }
        }

        String title = trim(ctx.formParam("title"));
        String description = trim(ctx.formParam("description"));
        String start = trim(ctx.formParam("start"));
        String zone = trim(ctx.formParam("input_zone"));
        String durationStr = trim(ctx.formParam("duration"));
        List<String> targetRoles = ctx.formParams("target_roles");

        if (title.isEmpty() || start.isEmpty()) {
            ctx.redirect("/dash/scheduler" + (editId == null ? "/new" : "/" + editId + "/edit")
                    + "?flash=Title+and+start+are+required.&flash_kind=danger");
            return;
        }
        if (zone.isEmpty()) zone = "UTC";
        if (!services.timezones.isValid(zone)) {
            ctx.redirect("/dash/scheduler" + (editId == null ? "/new" : "/" + editId + "/edit")
                    + "?flash=Pick+a+valid+input+timezone.&flash_kind=danger");
            return;
        }

        long startsAt;
        try {
            ZonedDateTime zdt = java.time.LocalDateTime.parse(start)
                    .atZone(ZoneId.of(zone));
            startsAt = zdt.toInstant().toEpochMilli();
        } catch (Exception ex) {
            ctx.redirect("/dash/scheduler" + (editId == null ? "/new" : "/" + editId + "/edit")
                    + "?flash=Bad+start+time.&flash_kind=danger");
            return;
        }
        int duration;
        try { duration = Math.max(5, Math.min(1440, Integer.parseInt(durationStr))); }
        catch (Exception ex) { duration = 60; }

        long now = System.currentTimeMillis();
        if (editId == null) {
            ScheduledEvent ev = new ScheduledEvent(0L, title, description, startsAt, duration,
                    sess.discordId(), sess.displayName() == null ? sess.username() : sess.displayName(),
                    targetRoles == null ? List.of() : targetRoles,
                    "", "", now, null);
            long newId = services.timezones.events().create(ev);
            services.audit.write("web:" + sess.discordId(), "event_created", null,
                    Map.of("id", newId, "title", title, "startsAtUtc", startsAt));
            ctx.redirect("/dash/scheduler/" + newId + "?flash=Event+scheduled.&flash_kind=success");
        } else {
            Optional<ScheduledEvent> existing = services.timezones.events().find(editId);
            if (existing.isEmpty()) { ctx.status(404); return; }
            ScheduledEvent ev = existing.get();
            ScheduledEvent updated = new ScheduledEvent(ev.id(), title, description, startsAt, duration,
                    ev.creatorId(), ev.creatorName(),
                    targetRoles == null ? List.of() : targetRoles,
                    ev.discordAnnounceChannelId(), ev.discordAnnounceMessageId(),
                    ev.createdAt(), ev.cancelledAt());
            services.timezones.events().delete(ev.id());
            long newId = services.timezones.events().create(updated);
            services.audit.write("web:" + sess.discordId(), "event_updated", null,
                    Map.of("id", editId, "title", title, "startsAtUtc", startsAt));
            ctx.redirect("/dash/scheduler/" + newId + "?flash=Event+updated.&flash_kind=success");
        }
    }

    public void cancel(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null) { ctx.status(401); return; }
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); }
        catch (NumberFormatException nfe) { ctx.status(404); return; }
        services.timezones.events().cancel(id, System.currentTimeMillis());
        services.audit.write("web:" + sess.discordId(), "event_cancelled", null, Map.of("id", id));
        ctx.redirect("/dash/scheduler?flash=Event+cancelled.&flash_kind=warning");
    }

    public void delete(Context ctx) throws Exception {
        SessionCookie.Session sess = DashAuth.sessionOf(ctx).orElse(null);
        if (sess == null || !sess.canEditConfig()) {
            ctx.status(403).result("config admin only");
            return;
        }
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); }
        catch (NumberFormatException nfe) { ctx.status(404); return; }
        services.timezones.events().delete(id);
        services.audit.write("web:" + sess.discordId(), "event_deleted", null, Map.of("id", id));
        ctx.redirect("/dash/scheduler?flash=Event+deleted.&flash_kind=warning");
    }

    public void detail(Context ctx) throws Exception {
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); }
        catch (NumberFormatException nfe) { ctx.status(404); return; }
        Optional<ScheduledEvent> evOpt = services.timezones.events().find(id);
        if (evOpt.isEmpty()) { ctx.status(404).html("<h1>404</h1>"); return; }
        ScheduledEvent ev = evOpt.get();

        List<EventRsvp> rsvps = services.timezones.rsvps().listFor(id);

        // Pull per-user timezone for the rsvps. Hit DAO once for all in one query.
        List<String> ids = new ArrayList<>(rsvps.size());
        for (EventRsvp r : rsvps) ids.add(r.discordId());
        Map<String, String> tzByUser = services.timezones.users().loadMany(ids);

        Map<String, Integer> counts = services.timezones.rsvps().countsFor(id);
        int going = counts.getOrDefault(EventRsvp.GOING, 0);
        int maybe = counts.getOrDefault(EventRsvp.MAYBE, 0);
        int no    = counts.getOrDefault(EventRsvp.NO, 0);

        // Aggregate "going" people by zone offset to show staff a rough
        // distribution: e.g. "5 in UTC-5, 3 in UTC+1". Buckets are formatted
        // offset strings sorted ascending.
        Map<String, Integer> byOffset = new TreeMap<>();
        for (EventRsvp r : rsvps) {
            if (!EventRsvp.GOING.equals(r.response()) && !EventRsvp.MAYBE.equals(r.response())) continue;
            String tz = tzByUser.get(r.discordId());
            String offset = offsetAt(ev.startsAtUtc(), tz);
            byOffset.merge(offset, 1, Integer::sum);
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head(Layout.escape(ev.title()) + " · Warden", "scheduler", ctx));

        h.append("<div class=\"d-flex justify-content-between align-items-start mb-3 gap-3\">");
        h.append("<div>");
        h.append("<h1 class=\"h3 mb-1\">").append(Layout.escape(ev.title())).append("</h1>");
        h.append("<div class=\"text-secondary\">Created by ").append(Layout.escape(ev.creatorName()))
                .append(" &middot; ").append(formatUtc(ev.startsAtUtc())).append(" UTC for ")
                .append(ev.durationMinutes()).append(" min");
        if (ev.cancelledAt() != null) h.append(" &middot; <span class=\"badge bg-secondary\">cancelled</span>");
        h.append("</div>");
        h.append("</div>");
        h.append("<div><a class=\"btn btn-outline-primary\" href=\"/dash/scheduler/").append(id)
                .append("/edit\">Edit</a> <a class=\"btn btn-outline-secondary\" href=\"/dash/scheduler\">Back</a></div>");
        h.append("</div>");

        if (!ev.description().isBlank()) {
            h.append("<div class=\"card mb-3\"><div class=\"card-body\">")
                    .append(Layout.escape(ev.description()).replace("\n", "<br>"))
                    .append("</div></div>");
        }

        h.append("<div class=\"row g-3\">");
        h.append("<div class=\"col-md-4\"><div class=\"card\"><div class=\"card-body\">")
                .append("<div class=\"text-secondary small\">Going</div>")
                .append("<div class=\"display-6 fw-semibold text-success\">").append(going).append("</div></div></div></div>");
        h.append("<div class=\"col-md-4\"><div class=\"card\"><div class=\"card-body\">")
                .append("<div class=\"text-secondary small\">Maybe</div>")
                .append("<div class=\"display-6 fw-semibold text-warning\">").append(maybe).append("</div></div></div></div>");
        h.append("<div class=\"col-md-4\"><div class=\"card\"><div class=\"card-body\">")
                .append("<div class=\"text-secondary small\">Can't make it</div>")
                .append("<div class=\"display-6 fw-semibold text-body-secondary\">").append(no).append("</div></div></div></div>");
        h.append("</div>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-3\">Going / Maybe by local timezone</h2>");
        if (byOffset.isEmpty()) {
            h.append("<p class=\"text-secondary mb-0\">Nobody has RSVP'd yet, or the people who did haven't set a timezone.</p>");
        } else {
            h.append("<div class=\"d-flex flex-wrap gap-2\">");
            for (Map.Entry<String, Integer> e : byOffset.entrySet()) {
                h.append("<span class=\"badge bg-primary-subtle text-primary-emphasis fs-6\">")
                        .append(Layout.escape(e.getKey())).append(": ").append(e.getValue()).append("</span>");
            }
            h.append("</div>");
        }
        h.append("</div></div>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h5 mb-3\">Respondents (").append(rsvps.size()).append(")</h2>");
        h.append("<div class=\"table-responsive\"><table class=\"table table-sm align-middle\">");
        h.append("<thead><tr><th>User</th><th>Response</th><th>Timezone</th><th>Their local time</th><th>Responded</th></tr></thead><tbody>");
        if (rsvps.isEmpty()) {
            h.append("<tr><td colspan=5 class=\"text-secondary text-center py-3\">No responses yet.</td></tr>");
        }
        Set<String> filterRoles = ev.targetRoles() == null ? Set.of() : Set.copyOf(ev.targetRoles());
        boolean hasFilter = !filterRoles.isEmpty();
        for (EventRsvp r : rsvps) {
            String tz = tzByUser.get(r.discordId());
            String localTime = tz == null ? "" : formatInZone(ev.startsAtUtc(), tz);
            String displayName = resolveDisplayName(r.discordId());
            boolean matchesFilter = hasFilter ? memberHasAny(r.discordId(), filterRoles) : true;
            if (hasFilter && !matchesFilter) continue;
            h.append("<tr><td>").append(Layout.escape(displayName))
                    .append(" <span class=\"text-secondary small\">").append(Layout.escape(r.discordId())).append("</span></td>");
            String badge = switch (r.response()) {
                case EventRsvp.GOING -> "<span class=\"badge bg-success\">going</span>";
                case EventRsvp.MAYBE -> "<span class=\"badge bg-warning text-dark\">maybe</span>";
                case EventRsvp.NO -> "<span class=\"badge bg-secondary\">can't</span>";
                default -> Layout.escape(r.response());
            };
            h.append("<td>").append(badge).append("</td>");
            h.append("<td>").append(tz == null ? "<span class=\"text-secondary\">not set</span>" : Layout.escape(tz)).append("</td>");
            h.append("<td>").append(Layout.escape(localTime)).append("</td>");
            h.append("<td>").append(formatUtc(r.respondedAt())).append("</td></tr>");
        }
        h.append("</tbody></table></div>");
        h.append("</div></div>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private boolean memberHasAny(String discordId, Set<String> wantedRoles) {
        if (wantedRoles == null || wantedRoles.isEmpty()) return true;
        if (discord == null || discord.jda() == null) return false;
        JDA jda = discord.jda();
        Guild g = jda.getGuildById(services.config.discordGuildId());
        if (g == null) return false;
        try {
            Member m = g.getMemberById(discordId);
            if (m == null) m = g.retrieveMemberById(discordId).complete();
            if (m == null) return false;
            for (var r : m.getRoles()) {
                if (wantedRoles.contains(r.getId())) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveDisplayName(String discordId) {
        if (discord == null || discord.jda() == null) return discordId;
        try {
            Guild g = discord.jda().getGuildById(services.config.discordGuildId());
            if (g == null) return discordId;
            Member m = g.getMemberById(discordId);
            if (m != null) return m.getEffectiveName();
        } catch (Exception ignored) {}
        try {
            return services.userDao.findByDiscordId(discordId).map(u -> u.username()).orElse(discordId);
        } catch (Exception ignored) {
            return discordId;
        }
    }

    private static void renderRow(StringBuilder h, ScheduledEvent ev, Map<String, Integer> counts) {
        int going = counts.getOrDefault(EventRsvp.GOING, 0);
        int maybe = counts.getOrDefault(EventRsvp.MAYBE, 0);
        int no = counts.getOrDefault(EventRsvp.NO, 0);
        h.append("<tr><td><a href=\"/dash/scheduler/").append(ev.id()).append("\">")
                .append(Layout.escape(ev.title())).append("</a></td>");
        h.append("<td><span data-utc=\"").append(ev.startsAtUtc()).append("\">").append(formatUtc(ev.startsAtUtc())).append("</span></td>");
        h.append("<td>").append(ev.durationMinutes()).append(" min</td>");
        h.append("<td><span class=\"badge bg-success me-1\">").append(going).append("</span>")
                .append("<span class=\"badge bg-warning text-dark me-1\">").append(maybe).append("</span>")
                .append("<span class=\"badge bg-secondary\">").append(no).append("</span></td>");
        h.append("<td>");
        if (ev.targetRoles().isEmpty()) {
            h.append("<span class=\"text-secondary\">everyone</span>");
        } else {
            h.append(ev.targetRoles().size()).append(" role").append(ev.targetRoles().size() == 1 ? "" : "s");
        }
        h.append("</td>");
        h.append("<td><a class=\"btn btn-sm btn-outline-primary\" href=\"/dash/scheduler/").append(ev.id())
                .append("/edit\"><i class=\"bi bi-pencil\"></i></a></td>");
        h.append("</tr>");
    }

    private static String formatUtc(long epochMs) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
    }

    private static String formatInZone(long epochMs, String zone) {
        try {
            return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.of(zone))
                    .format(DateTimeFormatter.ofPattern("EEE MMM d, HH:mm"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String offsetAt(long epochMs, String zone) {
        if (zone == null || zone.isBlank()) return "unknown";
        try {
            var off = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.of(zone)).getOffset();
            int seconds = off.getTotalSeconds();
            if (seconds == 0) return "UTC";
            int sign = seconds < 0 ? -1 : 1;
            int abs = Math.abs(seconds);
            int hours = abs / 3600;
            int mins = (abs % 3600) / 60;
            return "UTC" + (sign < 0 ? "-" : "+") + hours + (mins > 0 ? String.format(":%02d", mins) : "");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static List<String> commonZones() {
        List<String> common = new ArrayList<>(List.of(
                "UTC",
                "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Moscow",
                "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
                "America/Sao_Paulo", "America/Argentina/Buenos_Aires",
                "Africa/Johannesburg", "Africa/Cairo", "Africa/Lagos",
                "Asia/Dubai", "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "Asia/Seoul", "Asia/Singapore",
                "Australia/Sydney", "Pacific/Auckland"
        ));
        // append the rest of IANA so any zone is selectable
        Set<String> all = new java.util.TreeSet<>(ZoneId.getAvailableZoneIds());
        for (String z : all) if (!common.contains(z)) common.add(z);
        return common;
    }
}
