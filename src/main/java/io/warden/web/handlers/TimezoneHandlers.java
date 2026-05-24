package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.Services;
import io.warden.timezone.CountryTimezone;
import io.warden.timezone.EventRsvp;
import io.warden.timezone.ScheduledEvent;
import io.warden.timezone.TimezoneConfig;
import io.warden.timezone.UserTimezone;
import io.warden.web.auth.SessionCookie;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * /tz routes. Reachable by anyone with a valid Discord OAuth session
 * (no mod/admin role required) so non-staff members can configure their
 * timezone and RSVP to events. /tz is also the landing place when
 * onboarding_required is on and a member has no timezone set.
 */
public final class TimezoneHandlers {

    private final Services services;
    private final SessionCookie session;

    public TimezoneHandlers(Services services, SessionCookie session) {
        this.services = services;
        this.session = session;
    }

    // -------------------------------------------------------------------
    // /tz - picker
    // -------------------------------------------------------------------

    public void picker(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;

        TimezoneConfig cfg = services.timezones.config();
        Optional<UserTimezone> current = services.timezones.findUser(sess.discordId());
        String suggested = services.timezones.suggest(sess.discordId(), ctx.ip());
        String currentTz = current.map(UserTimezone::tzId).orElse(suggested);
        boolean firstTimeRequired = cfg.onboardingRequired() && current.isEmpty();
        String flash = ctx.queryParam("flash");
        String flashKind = ctx.queryParam("flash_kind");

        StringBuilder h = new StringBuilder(16384);
        h.append(Layout.head("Your timezone · Warden", "timezone", ctx));

        h.append("<div class=\"row g-3 align-items-start\">");
        h.append("<div class=\"col-12\">");
        h.append("<h1 class=\"h3 mb-1\">Your timezone</h1>");
        h.append("<p class=\"text-secondary mb-3\" style=\"max-width:60ch\">Pick the city or region closest to you. ")
                .append("We use this to show event times in your local clock and to help staff plan around the people who can actually attend.</p>");

        if (firstTimeRequired) {
            h.append("<div class=\"alert alert-warning\" role=alert>")
                    .append("<strong>Server admins are asking everyone to set this.</strong> ")
                    .append("Pick a spot on the map (or search) and save before continuing.")
                    .append("</div>");
        }

        if (flash != null && !flash.isBlank()) {
            String kind = (flashKind == null || flashKind.isBlank()) ? "success" : flashKind;
            h.append("<div class=\"alert alert-").append(Layout.escape(kind))
                    .append("\" role=alert>").append(Layout.escape(flash)).append("</div>");
        }

        h.append("<div class=\"alert alert-info d-flex gap-2\" role=alert>")
                .append("<i class=\"bi bi-shield-check\" aria-hidden=true></i>")
                .append("<div><strong>The map is just a UI shortcut.</strong> Warden does not store your location, ")
                .append("address, or GPS coordinates. Only the timezone you confirm is saved.</div>")
                .append("</div>");

        h.append("</div>");

        // ---- left column: map + search + selected ----
        h.append("<div class=\"col-lg-8\">");
        h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        h.append("<label for=\"tz-search\" class=\"form-label small text-secondary\">Search by city, country, or zone id</label>");
        h.append("<div class=\"position-relative\">");
        h.append("<input type=\"text\" class=\"form-control form-control-lg\" id=\"tz-search\" autocomplete=\"off\" "
                + "placeholder=\"e.g. Berlin, Tokyo, America/Chicago\">");
        h.append("<div id=\"tz-suggest\" class=\"list-group position-absolute w-100 shadow tz-suggest\" "
                + "style=\"top:100%;left:0;z-index:30;display:none;max-height:18rem;overflow:auto;\"></div>");
        h.append("</div>");
        h.append("<div class=\"tz-map-wrap mt-3\" id=\"tz-map-wrap\">");
        h.append(svgWorldMap());
        h.append("</div>");
        h.append("<div class=\"small text-secondary mt-2\">")
                .append("Click any dot to pick a nearby zone. Use the search field for anything that isn't on the map.")
                .append("</div>");
        h.append("</div></div>");

        h.append("</div>"); // col-lg-8

        // ---- right column: current selection + save ----
        h.append("<div class=\"col-lg-4\">");
        h.append("<form method=post action=\"/tz\" class=\"card\">");
        h.append("<div class=\"card-body\">");
        h.append("<div class=\"small text-secondary\">Selected timezone</div>");
        h.append("<div class=\"fs-5 fw-semibold\" id=\"tz-selected-id\">")
                .append(Layout.escape(currentTz)).append("</div>");
        h.append("<div class=\"text-secondary small\">"
                + "<span id=\"tz-selected-offset\"></span>"
                + " <span id=\"tz-selected-local\" class=\"ms-1\"></span></div>");
        h.append("<input type=\"hidden\" name=\"tz_id\" id=\"tz-id\" value=\"")
                .append(Layout.escape(currentTz)).append("\">");
        h.append("<input type=\"hidden\" name=\"source\" id=\"tz-source\" value=\"")
                .append(current.isPresent() ? "manual" : (suggested.equals(currentTz) ? "geoip" : "manual"))
                .append("\">");

        h.append("<hr>");
        h.append("<div class=\"d-flex gap-2 flex-wrap\">");
        h.append("<button class=\"btn btn-primary flex-grow-1\" type=submit><i class=\"bi bi-check2-circle me-1\"></i>Save timezone</button>");
        if (current.isPresent()) {
            h.append("<form method=post action=\"/tz/clear\" class=\"d-inline\" data-confirm=\"Forget your saved timezone?\">");
            h.append("<button class=\"btn btn-outline-secondary\" type=submit title=\"Forget my timezone\"><i class=\"bi bi-trash\"></i></button>");
            h.append("</form>");
        }
        h.append("</div>");

        if (cfg.geoipEnabled() && current.isEmpty() && !suggested.equals("UTC")) {
            h.append("<div class=\"small text-secondary mt-2\">"
                    + "We pre-selected <code>").append(Layout.escape(suggested))
                    .append("</code> based on rough region from your IP. Adjust if it isn't right.</div>");
        }

        h.append("</div>");
        h.append("</form>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body small text-secondary\">");
        h.append("<div class=\"mb-1 fw-semibold text-body\">What we store</div>");
        h.append("<ul class=\"mb-0 ps-3\">");
        h.append("<li>The timezone id you confirm (e.g. <code>Europe/London</code>).</li>");
        h.append("<li>When you last updated it.</li>");
        if (cfg.geoipEnabled()) {
            h.append("<li>Your IP is checked against a country database to seed the picker. The country code is not stored alongside your timezone.</li>");
        }
        h.append("</ul>");
        h.append("</div></div>");

        h.append("</div>"); // col-lg-4
        h.append("</div>"); // row

        h.append(tzPageStyle());
        h.append(tzPageScript(currentTz));
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void save(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;
        String tzId = ctx.formParam("tz_id");
        String source = ctx.formParam("source");
        if (!services.timezones.isValid(tzId)) {
            ctx.redirect("/tz?flash=" + url("Pick a valid timezone before saving.") + "&flash_kind=danger");
            return;
        }
        services.timezones.saveUser(sess.discordId(), tzId.trim(),
                (source == null || source.isBlank()) ? "manual" : source, "web:" + sess.discordId());
        // If the user came here because of the onboarding requirement, send
        // them on to wherever they were trying to go. Default to /dash so
        // staff land on their normal entry point, /tz/events for everyone else.
        boolean anyDash = sess.anyDashAccess();
        String next = anyDash ? "/dash" : "/tz/events";
        ctx.redirect(next + "?flash=" + url("Timezone saved.") + "&flash_kind=success");
    }

    public void clear(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;
        services.timezones.clearUser(sess.discordId(), "web:" + sess.discordId());
        ctx.redirect("/tz?flash=" + url("Cleared. Pick one again whenever you'd like.") + "&flash_kind=info");
    }

    // -------------------------------------------------------------------
    // /tz/events - public list + RSVP
    // -------------------------------------------------------------------

    public void eventsList(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;

        TimezoneConfig cfg = services.timezones.config();
        if (!cfg.schedulerEnabled()) {
            ctx.redirect("/tz?flash=" + url("The event scheduler isn't enabled on this server.")
                    + "&flash_kind=info");
            return;
        }

        Optional<UserTimezone> myTz = services.timezones.findUser(sess.discordId());
        long now = System.currentTimeMillis();
        List<ScheduledEvent> events = services.timezones.events().listUpcoming(now);

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Upcoming events · Warden", "timezone", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<div>");
        h.append("<h1 class=\"h3 mb-1\">Upcoming events</h1>");
        h.append("<p class=\"text-secondary mb-0\">All times shown in <strong id=\"tz-here\">")
                .append(Layout.escape(myTz.map(UserTimezone::tzId).orElse("your browser's timezone")))
                .append("</strong>.</p>");
        h.append("</div>");
        h.append("<div><a class=\"btn btn-outline-primary btn-sm\" href=\"/tz\"><i class=\"bi bi-globe2 me-1\"></i>Change my timezone</a></div>");
        h.append("</div>");

        String flash = ctx.queryParam("flash");
        if (flash != null && !flash.isBlank()) {
            String kind = ctx.queryParam("flash_kind");
            h.append("<div class=\"alert alert-").append(Layout.escape(kind == null ? "info" : kind))
                    .append("\" role=alert>").append(Layout.escape(flash)).append("</div>");
        }

        if (myTz.isEmpty()) {
            h.append("<div class=\"alert alert-warning\" role=alert>")
                    .append("You haven't picked a timezone yet. ")
                    .append("<a href=\"/tz\">Set it now</a> so other people can see when you're available.")
                    .append("</div>");
        }

        if (events.isEmpty()) {
            h.append("<div class=\"card\"><div class=\"card-body text-secondary text-center py-5\">")
                    .append("Nothing scheduled right now. Check back soon, or ask staff to plan one.")
                    .append("</div></div>");
        } else {
            for (ScheduledEvent ev : events) {
                Optional<EventRsvp> myRsvp;
                try {
                    myRsvp = services.timezones.rsvps().findFor(ev.id(), sess.discordId());
                } catch (Exception e) {
                    myRsvp = Optional.empty();
                }
                renderEventCard(h, ev, myRsvp, myTz.map(UserTimezone::tzId).orElse(null));
            }
        }

        h.append(eventCardStyle());
        h.append(eventTimeScript());
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void rsvp(Context ctx) throws Exception {
        SessionCookie.Session sess = requireSession(ctx);
        if (sess == null) return;
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); }
        catch (NumberFormatException nfe) { ctx.status(404); return; }
        String response = ctx.formParam("rsvp");
        if (!EventRsvp.isValid(response)) {
            ctx.redirect("/tz/events?flash=" + url("Pick going, maybe, or no.") + "&flash_kind=danger");
            return;
        }
        Optional<ScheduledEvent> ev = services.timezones.events().find(id);
        if (ev.isEmpty()) {
            ctx.redirect("/tz/events?flash=" + url("That event no longer exists.") + "&flash_kind=warning");
            return;
        }
        if (!ev.get().upcoming(System.currentTimeMillis())) {
            ctx.redirect("/tz/events?flash=" + url("That event has finished.") + "&flash_kind=warning");
            return;
        }
        services.timezones.rsvps().upsert(id, sess.discordId(), response);
        services.audit.write("web:" + sess.discordId(), "event_rsvp", sess.discordId(),
                Map.of("eventId", id, "rsvp", response));
        ctx.redirect("/tz/events?flash=" + url("Got it. Thanks for letting us know.") + "&flash_kind=success");
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private SessionCookie.Session requireSession(Context ctx) {
        if (session == null) {
            ctx.status(404).html("<h1>404</h1><p>Web sessions are not configured on this server.</p>");
            return null;
        }
        Optional<SessionCookie.Session> s = session.decode(ctx.cookie(SessionCookie.COOKIE_NAME));
        if (s.isEmpty()) {
            if (services.config.oauthConfigured()) {
                String here = ctx.path() + (ctx.queryString() == null ? "" : "?" + ctx.queryString());
                ctx.redirect("/auth/discord/start?next=" + url(here));
            } else {
                ctx.status(401).html("<h1>401</h1><p>Sign in with Discord to set your timezone.</p>");
            }
            return null;
        }
        ctx.attribute(io.warden.web.auth.DashAuth.CTX_SESSION, s.get());
        return s.get();
    }

    private static String url(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static void renderEventCard(StringBuilder h, ScheduledEvent ev, Optional<EventRsvp> myRsvp, String viewerTz) {
        h.append("<div class=\"card tz-event-card mb-3\" data-utc=\"").append(ev.startsAtUtc()).append("\" ")
                .append("data-ends=\"").append(ev.endsAtUtc()).append("\" data-tz=\"")
                .append(viewerTz == null ? "" : Layout.escape(viewerTz)).append("\">");
        h.append("<div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-start gap-3\">");
        h.append("<div class=\"min-w-0\">");
        h.append("<div class=\"h5 mb-1\">").append(Layout.escape(ev.title())).append("</div>");
        h.append("<div class=\"text-secondary tz-event-time\"><i class=\"bi bi-clock me-1\"></i>")
                .append("<span class=\"tz-event-local-start\">").append(Layout.escape(formatIso(ev.startsAtUtc())))
                .append(" UTC</span> &middot; <span class=\"tz-event-rel text-muted\"></span>")
                .append("</div>");
        if (!ev.description().isBlank()) {
            h.append("<div class=\"mt-2\">").append(Layout.escape(ev.description())).append("</div>");
        }
        h.append("</div>");
        h.append("<div class=\"text-end small text-secondary\">")
                .append("<div>").append(ev.durationMinutes()).append(" min</div>")
                .append("</div>");
        h.append("</div>");

        String picked = myRsvp.map(EventRsvp::response).orElse("");
        h.append("<form method=post action=\"/tz/events/").append(ev.id()).append("/rsvp\" class=\"mt-3\">");
        h.append("<div class=\"btn-group btn-group-sm\" role=group>");
        for (String[] opt : new String[][] {
                {"going", "Going", "bi-check-circle", "btn-success"},
                {"maybe", "Maybe", "bi-question-circle", "btn-warning"},
                {"no",    "Can't make it", "bi-x-circle", "btn-outline-secondary"}}) {
            boolean sel = opt[0].equals(picked);
            h.append("<button type=submit name=rsvp value=\"").append(opt[0]).append("\" class=\"btn ")
                    .append(sel ? opt[3] + " active" : "btn-outline-light text-body")
                    .append("\"><i class=\"bi ").append(opt[2]).append(" me-1\"></i>")
                    .append(Layout.escape(opt[1])).append("</button>");
        }
        h.append("</div>");
        if (!picked.isBlank()) {
            h.append("<span class=\"ms-2 small text-secondary\">Your response: ")
                    .append(Layout.escape(picked)).append("</span>");
        }
        h.append("</form>");

        h.append("</div></div>");
    }

    private static String formatIso(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs).toString();
    }

    private static String tzPageStyle() {
        return "<style>"
                + ".tz-map-wrap{position:relative;background:linear-gradient(180deg,#0e1538,#0a1124);"
                + "border-radius:.6rem;padding:.6rem;border:1px solid rgba(255,255,255,.04);overflow:hidden}"
                + ".tz-map{width:100%;height:auto;display:block}"
                + ".tz-map .tz-land{fill:#1f2a55;stroke:rgba(255,255,255,.05);stroke-width:.4}"
                + ".tz-map .tz-strip{fill:rgba(88,101,242,.04);stroke:rgba(88,101,242,.08);stroke-width:.25;cursor:pointer;transition:fill .15s}"
                + ".tz-map .tz-strip:hover{fill:rgba(88,101,242,.18)}"
                + ".tz-map .tz-strip.active{fill:rgba(88,101,242,.32)}"
                + ".tz-map .tz-city{fill:#f5d76e;stroke:#1b1f2d;stroke-width:.5;cursor:pointer;transition:r .12s,fill .12s}"
                + ".tz-map .tz-city:hover{fill:#fff;r:2.6}"
                + ".tz-map .tz-city.active{fill:#5865f2;stroke:#fff;stroke-width:.8}"
                + ".tz-map .tz-grid{fill:none;stroke:rgba(255,255,255,.04);stroke-width:.25}"
                + ".tz-map .tz-label{fill:rgba(255,255,255,.55);font:600 4.4px ui-monospace,monospace;pointer-events:none}"
                + ".tz-suggest{background:var(--bs-body-bg);border:1px solid var(--bs-border-color);border-radius:.4rem}"
                + ".tz-suggest .list-group-item{background:transparent;border-color:rgba(127,127,127,.08);cursor:pointer}"
                + ".tz-suggest .list-group-item:hover,.tz-suggest .list-group-item.tz-hl{background:rgba(88,101,242,.18)}"
                + "</style>";
    }

    private static String tzPageScript(String currentTz) {
        return "<script>"
                + "(function(){"
                + "var ZONES=" + zonesJsArray() + ";"
                + "var CITIES=" + citiesJsArray() + ";"
                + "var SELECTED=" + js(currentTz) + ";"
                + "var idEl=document.getElementById('tz-id');"
                + "var srcEl=document.getElementById('tz-source');"
                + "var labelEl=document.getElementById('tz-selected-id');"
                + "var offEl=document.getElementById('tz-selected-offset');"
                + "var locEl=document.getElementById('tz-selected-local');"
                + "var searchEl=document.getElementById('tz-search');"
                + "var suggestEl=document.getElementById('tz-suggest');"
                + "var mapEl=document.querySelector('.tz-map');"
                + "function offset(zone){try{var f=new Intl.DateTimeFormat('en-GB',{timeZone:zone,timeZoneName:'shortOffset'});"
                + "var parts=f.formatToParts(new Date());"
                + "for(var i=0;i<parts.length;i++){if(parts[i].type==='timeZoneName')return parts[i].value;}}catch(e){}return'';}"
                + "function local(zone){try{return new Intl.DateTimeFormat([],{timeZone:zone,hour:'2-digit',minute:'2-digit',weekday:'short'}).format(new Date());}catch(e){return'';}}"
                + "function reflect(){"
                + "labelEl.textContent=SELECTED;"
                + "idEl.value=SELECTED;"
                + "offEl.textContent=offset(SELECTED);"
                + "locEl.textContent='now: '+local(SELECTED);"
                + "if(mapEl){mapEl.querySelectorAll('.tz-city.active').forEach(function(n){n.classList.remove('active');});"
                + "var dot=mapEl.querySelector('.tz-city[data-tz=\"'+SELECTED.replace(/\"/g,'\\\\\"')+'\"]');"
                + "if(dot)dot.classList.add('active');}"
                + "}"
                + "function setTz(z,src){SELECTED=z;if(srcEl)srcEl.value=src||'manual';reflect();}"
                + "reflect();"
                + "if(mapEl){mapEl.addEventListener('click',function(e){"
                + "var t=e.target;if(t&&t.classList&&t.classList.contains('tz-city')){"
                + "var z=t.getAttribute('data-tz');if(z)setTz(z,'manual');}});}"
                + "var hl=-1;var lastResults=[];"
                + "function score(zone,q){var l=zone.toLowerCase();q=q.toLowerCase();"
                + "if(l===q)return 100;if(l.indexOf('/'+q)>=0)return 80;if(l.indexOf(q)>=0)return 50;"
                + "var pretty=zone.replace(/_/g,' ').toLowerCase();if(pretty.indexOf(q)>=0)return 30;return 0;}"
                + "function render(items){if(!items.length){suggestEl.style.display='none';return;}"
                + "var html='';for(var i=0;i<items.length;i++){var z=items[i];"
                + "html+='<a class=\"list-group-item list-group-item-action\" data-tz=\"'+z.replace(/\"/g,'&quot;')+'\">'"
                + "+'<span class=\"fw-semibold\">'+z.replace(/_/g,' ')+'</span>'"
                + "+'<span class=\"float-end small text-secondary\">'+offset(z)+'</span></a>';}"
                + "suggestEl.innerHTML=html;suggestEl.style.display='block';"
                + "Array.prototype.forEach.call(suggestEl.querySelectorAll('a'),function(a){"
                + "a.addEventListener('mousedown',function(e){e.preventDefault();setTz(a.getAttribute('data-tz'),'manual');suggestEl.style.display='none';searchEl.value='';});});}"
                + "searchEl.addEventListener('input',function(){var q=this.value.trim();hl=-1;"
                + "if(q.length<1){suggestEl.style.display='none';lastResults=[];return;}"
                + "var scored=[];for(var i=0;i<ZONES.length;i++){var s=score(ZONES[i],q);if(s>0)scored.push([s,ZONES[i]]);}"
                + "scored.sort(function(a,b){return b[0]-a[0]||a[1].localeCompare(b[1]);});"
                + "lastResults=scored.slice(0,24).map(function(p){return p[1];});render(lastResults);});"
                + "searchEl.addEventListener('blur',function(){setTimeout(function(){suggestEl.style.display='none';},120);});"
                + "searchEl.addEventListener('keydown',function(e){var items=suggestEl.querySelectorAll('a');if(!items.length)return;"
                + "if(e.key==='ArrowDown'){e.preventDefault();hl=(hl+1)%items.length;}"
                + "else if(e.key==='ArrowUp'){e.preventDefault();hl=(hl-1+items.length)%items.length;}"
                + "else if(e.key==='Enter'){e.preventDefault();if(hl>=0&&items[hl]){setTz(items[hl].getAttribute('data-tz'),'manual');suggestEl.style.display='none';searchEl.value='';}return;}"
                + "else if(e.key==='Escape'){suggestEl.style.display='none';return;}"
                + "items.forEach(function(a){a.classList.remove('tz-hl');});if(hl>=0)items[hl].classList.add('tz-hl');});"
                + "})();</script>";
    }

    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < (s == null ? 0 : s.length()); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '<' -> b.append("\\u003C");
                case '>' -> b.append("\\u003E");
                default -> b.append(c);
            }
        }
        b.append("\"");
        return b.toString();
    }

    private static String zonesJsArray() {
        Set<String> sorted = new TreeSet<>(ZoneId.getAvailableZoneIds());
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (String z : sorted) {
            if (!first) b.append(',');
            b.append(js(z));
            first = false;
        }
        b.append(']');
        return b.toString();
    }

    private static final String LAND_PATHS = loadLandPaths();

    private static String loadLandPaths() {
        try (var in = TimezoneHandlers.class.getResourceAsStream("/world-land.txt")) {
            if (in == null) return "";
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder b = new StringBuilder(raw.length() + 4096);
            for (String line : raw.split("\\R")) {
                if (line.isBlank()) continue;
                b.append("<path d=\"").append(line).append("\"/>");
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // SVG world map + dotted reference cities.
    private static String svgWorldMap() {
        // Equirectangular: x = lon + 180, y = 90 - lat. world-land.txt has already
        // been clipped to lat [-58, +75] (y in [15, 148]) so the Arctic ring and
        // Antarctica do not show; viewBox adds a small ocean margin above and below.
        int viewTop = 10;
        int viewH = 142;
        StringBuilder s = new StringBuilder(8192);
        s.append("<svg class=\"tz-map\" viewBox=\"0 ").append(viewTop).append(" 360 ").append(viewH)
                .append("\" xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"World map - pick a timezone\">");
        // Background ocean
        s.append("<rect x=0 y=").append(viewTop).append(" width=360 height=").append(viewH).append(" fill=\"#0a1124\"/>");
        // Hour strips
        for (int i = -12; i <= 12; i++) {
            double x = (i + 12) * 15.0;
            s.append("<rect class=\"tz-strip\" x=\"").append(x).append("\" y=\"").append(viewTop)
                    .append("\" width=15 height=\"").append(viewH).append("\" ")
                    .append("data-utc=\"").append(i).append("\"/>");
        }
        // Continents: equirectangular paths (x = lon + 180, y = 90 - lat) derived
        // from Natural Earth 110m land via topojson, simplified to ~22KB. See
        // resources/world-land.txt (one polygon per line).
        s.append("<g class=\"tz-land\">").append(LAND_PATHS).append("</g>");

        // Hour labels on top (clear of the new viewBox top edge)
        s.append("<g>");
        for (int i = -12; i <= 12; i += 3) {
            double x = (i + 12) * 15.0 + 0.5;
            String label = (i == 0 ? "UTC" : (i > 0 ? "+" + i : String.valueOf(i)));
            s.append("<text class=\"tz-label\" x=\"").append(x).append("\" y=\"").append(viewTop + 4).append("\">").append(label).append("</text>");
        }
        s.append("</g>");

        // Reference city dots. Each dot is positioned with x = lon + 180, y = 90 - lat
        s.append("<g class=\"tz-cities\">");
        for (CityDot c : CITIES) {
            double x = c.lon + 180.0;
            double y = 90.0 - c.lat;
            s.append("<circle class=\"tz-city\" cx=\"").append(round(x)).append("\" cy=\"").append(round(y))
                    .append("\" r=\"1.8\" data-tz=\"").append(Layout.escape(c.tz)).append("\">")
                    .append("<title>").append(Layout.escape(c.name + " - " + c.tz)).append("</title></circle>");
        }
        s.append("</g>");
        s.append("</svg>");
        return s.toString();
    }

    private static String round(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String citiesJsArray() {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (CityDot c : CITIES) {
            if (!first) b.append(',');
            first = false;
            b.append("{n:").append(js(c.name)).append(",tz:").append(js(c.tz)).append("}");
        }
        b.append(']');
        return b.toString();
    }

    private record CityDot(String name, String tz, double lat, double lon) {}

    private static final List<CityDot> CITIES = buildCities();

    private static List<CityDot> buildCities() {
        List<CityDot> c = new ArrayList<>();
        c.add(new CityDot("Reykjavik", "Atlantic/Reykjavik", 64.13, -21.94));
        c.add(new CityDot("London", "Europe/London", 51.51, -0.13));
        c.add(new CityDot("Lisbon", "Europe/Lisbon", 38.72, -9.14));
        c.add(new CityDot("Dublin", "Europe/Dublin", 53.35, -6.26));
        c.add(new CityDot("Madrid", "Europe/Madrid", 40.42, -3.70));
        c.add(new CityDot("Paris", "Europe/Paris", 48.86, 2.35));
        c.add(new CityDot("Brussels", "Europe/Brussels", 50.85, 4.35));
        c.add(new CityDot("Amsterdam", "Europe/Amsterdam", 52.37, 4.90));
        c.add(new CityDot("Berlin", "Europe/Berlin", 52.52, 13.40));
        c.add(new CityDot("Copenhagen", "Europe/Copenhagen", 55.68, 12.57));
        c.add(new CityDot("Oslo", "Europe/Oslo", 59.91, 10.75));
        c.add(new CityDot("Stockholm", "Europe/Stockholm", 59.33, 18.06));
        c.add(new CityDot("Helsinki", "Europe/Helsinki", 60.17, 24.94));
        c.add(new CityDot("Warsaw", "Europe/Warsaw", 52.23, 21.01));
        c.add(new CityDot("Prague", "Europe/Prague", 50.08, 14.44));
        c.add(new CityDot("Vienna", "Europe/Vienna", 48.21, 16.37));
        c.add(new CityDot("Rome", "Europe/Rome", 41.90, 12.50));
        c.add(new CityDot("Athens", "Europe/Athens", 37.98, 23.73));
        c.add(new CityDot("Bucharest", "Europe/Bucharest", 44.43, 26.10));
        c.add(new CityDot("Sofia", "Europe/Sofia", 42.70, 23.32));
        c.add(new CityDot("Istanbul", "Europe/Istanbul", 41.01, 28.98));
        c.add(new CityDot("Moscow", "Europe/Moscow", 55.76, 37.62));
        c.add(new CityDot("Kyiv", "Europe/Kyiv", 50.45, 30.52));
        c.add(new CityDot("Minsk", "Europe/Minsk", 53.90, 27.57));

        c.add(new CityDot("Cairo", "Africa/Cairo", 30.04, 31.24));
        c.add(new CityDot("Lagos", "Africa/Lagos", 6.52, 3.38));
        c.add(new CityDot("Nairobi", "Africa/Nairobi", -1.29, 36.82));
        c.add(new CityDot("Johannesburg", "Africa/Johannesburg", -26.20, 28.04));
        c.add(new CityDot("Casablanca", "Africa/Casablanca", 33.57, -7.59));
        c.add(new CityDot("Algiers", "Africa/Algiers", 36.75, 3.06));
        c.add(new CityDot("Addis Ababa", "Africa/Addis_Ababa", 9.03, 38.74));

        c.add(new CityDot("Tehran", "Asia/Tehran", 35.69, 51.39));
        c.add(new CityDot("Dubai", "Asia/Dubai", 25.20, 55.27));
        c.add(new CityDot("Riyadh", "Asia/Riyadh", 24.71, 46.68));
        c.add(new CityDot("Jerusalem", "Asia/Jerusalem", 31.78, 35.22));
        c.add(new CityDot("Baghdad", "Asia/Baghdad", 33.31, 44.36));
        c.add(new CityDot("Karachi", "Asia/Karachi", 24.86, 67.01));
        c.add(new CityDot("Kabul", "Asia/Kabul", 34.53, 69.17));
        c.add(new CityDot("Delhi", "Asia/Kolkata", 28.61, 77.21));
        c.add(new CityDot("Mumbai", "Asia/Kolkata", 19.08, 72.88));
        c.add(new CityDot("Dhaka", "Asia/Dhaka", 23.81, 90.41));
        c.add(new CityDot("Bangkok", "Asia/Bangkok", 13.76, 100.50));
        c.add(new CityDot("Jakarta", "Asia/Jakarta", -6.21, 106.85));
        c.add(new CityDot("Kuala Lumpur", "Asia/Kuala_Lumpur", 3.14, 101.69));
        c.add(new CityDot("Singapore", "Asia/Singapore", 1.35, 103.82));
        c.add(new CityDot("Manila", "Asia/Manila", 14.60, 120.98));
        c.add(new CityDot("Hong Kong", "Asia/Hong_Kong", 22.32, 114.17));
        c.add(new CityDot("Shanghai", "Asia/Shanghai", 31.23, 121.47));
        c.add(new CityDot("Beijing", "Asia/Shanghai", 39.90, 116.41));
        c.add(new CityDot("Taipei", "Asia/Taipei", 25.04, 121.56));
        c.add(new CityDot("Seoul", "Asia/Seoul", 37.57, 126.98));
        c.add(new CityDot("Tokyo", "Asia/Tokyo", 35.68, 139.69));
        c.add(new CityDot("Ulaanbaatar", "Asia/Ulaanbaatar", 47.92, 106.92));
        c.add(new CityDot("Almaty", "Asia/Almaty", 43.24, 76.95));
        c.add(new CityDot("Tashkent", "Asia/Tashkent", 41.30, 69.24));
        c.add(new CityDot("Vladivostok", "Asia/Vladivostok", 43.12, 131.89));
        c.add(new CityDot("Yekaterinburg", "Asia/Yekaterinburg", 56.84, 60.61));
        c.add(new CityDot("Novosibirsk", "Asia/Novosibirsk", 55.04, 82.93));

        c.add(new CityDot("Auckland", "Pacific/Auckland", -36.85, 174.76));
        c.add(new CityDot("Sydney", "Australia/Sydney", -33.87, 151.21));
        c.add(new CityDot("Melbourne", "Australia/Melbourne", -37.81, 144.96));
        c.add(new CityDot("Brisbane", "Australia/Brisbane", -27.47, 153.03));
        c.add(new CityDot("Perth", "Australia/Perth", -31.95, 115.86));
        c.add(new CityDot("Adelaide", "Australia/Adelaide", -34.93, 138.60));
        c.add(new CityDot("Honolulu", "Pacific/Honolulu", 21.31, -157.86));
        c.add(new CityDot("Fiji", "Pacific/Fiji", -18.13, 178.42));

        c.add(new CityDot("Anchorage", "America/Anchorage", 61.22, -149.90));
        c.add(new CityDot("Vancouver", "America/Vancouver", 49.28, -123.12));
        c.add(new CityDot("Los Angeles", "America/Los_Angeles", 34.05, -118.24));
        c.add(new CityDot("Seattle", "America/Los_Angeles", 47.61, -122.33));
        c.add(new CityDot("Denver", "America/Denver", 39.74, -104.99));
        c.add(new CityDot("Phoenix", "America/Phoenix", 33.45, -112.07));
        c.add(new CityDot("Chicago", "America/Chicago", 41.88, -87.63));
        c.add(new CityDot("Mexico City", "America/Mexico_City", 19.43, -99.13));
        c.add(new CityDot("Toronto", "America/Toronto", 43.65, -79.38));
        c.add(new CityDot("New York", "America/New_York", 40.71, -74.01));
        c.add(new CityDot("Halifax", "America/Halifax", 44.65, -63.58));
        c.add(new CityDot("St. John's", "America/St_Johns", 47.56, -52.71));

        c.add(new CityDot("Havana", "America/Havana", 23.13, -82.39));
        c.add(new CityDot("Panama", "America/Panama", 8.98, -79.52));
        c.add(new CityDot("Bogota", "America/Bogota", 4.71, -74.07));
        c.add(new CityDot("Lima", "America/Lima", -12.05, -77.04));
        c.add(new CityDot("Santiago", "America/Santiago", -33.45, -70.67));
        c.add(new CityDot("Buenos Aires", "America/Argentina/Buenos_Aires", -34.60, -58.38));
        c.add(new CityDot("Sao Paulo", "America/Sao_Paulo", -23.55, -46.63));
        c.add(new CityDot("Rio", "America/Sao_Paulo", -22.91, -43.17));
        c.add(new CityDot("Caracas", "America/Caracas", 10.49, -66.88));

        return c;
    }

    private static String eventCardStyle() {
        return "<style>"
                + ".tz-event-card .btn-group .btn{min-width:6.5rem}"
                + ".tz-event-card .min-w-0{min-width:0}"
                + "</style>";
    }

    /**
     * Each event card has data-utc / data-ends / data-tz. This script
     * rewrites the visible text into the viewer's saved zone (or the
     * browser's if none) and adds a relative "in 3h 20m" hint.
     */
    private static String eventTimeScript() {
        return "<script>(function(){"
                + "var cards=document.querySelectorAll('.tz-event-card');"
                + "if(!cards.length)return;"
                + "function rel(ms){var d=Math.round(ms/1000);if(d<0){d=-d;return formatDur(d)+' ago';}return 'in '+formatDur(d);}"
                + "function formatDur(s){if(s<60)return s+'s';var m=Math.round(s/60);if(m<60)return m+'m';"
                + "var h=Math.floor(m/60);m=m-h*60;if(h<24)return h+'h '+(m?m+'m':'').trim();"
                + "var d=Math.floor(h/24);h=h-d*24;return d+'d '+(h?h+'h':'').trim();}"
                + "function fmt(zone,date){try{return new Intl.DateTimeFormat([],{timeZone:zone||undefined,weekday:'short',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',timeZoneName:'short'}).format(date);}catch(e){return date.toString();}}"
                + "function tick(){var now=Date.now();cards.forEach(function(card){"
                + "var utc=parseInt(card.getAttribute('data-utc'),10);var zone=card.getAttribute('data-tz');"
                + "var startEl=card.querySelector('.tz-event-local-start');var relEl=card.querySelector('.tz-event-rel');"
                + "if(!isNaN(utc)){if(startEl)startEl.textContent=fmt(zone,new Date(utc));if(relEl)relEl.textContent=rel(utc-now);}});}"
                + "tick();setInterval(tick,30000);"
                + "})();</script>";
    }
}
