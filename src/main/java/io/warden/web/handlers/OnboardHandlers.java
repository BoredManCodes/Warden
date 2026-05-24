package io.warden.web.handlers;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.warden.Services;
import io.warden.data.dao.LinkCodeDao;
import io.warden.discord.DiscordService;
import io.warden.onboarding.OnboardingService.StepResult;
import io.warden.onboarding.model.AnswerValue;
import io.warden.onboarding.model.FlowConfig;
import io.warden.onboarding.model.Question;
import io.warden.onboarding.model.QuestionKind;
import io.warden.web.auth.SessionCookie;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Public-facing onboarding web flow.
 *
 * Pairing model (changed from earlier "bot DMs a code" flow):
 *   The user opens /onboard. Depending on flow config the page offers
 *   "Sign in with Discord" (OAuth) and/or "Get a code to DM the bot". When the
 *   user requests a code, the page mints one bound to a guest cookie and
 *   polls /onboard/code/poll. The user DMs the code to the bot; the DM
 *   listener calls LinkCodeService.claim, the next poll picks it up, and the
 *   page upgrades the guest cookie to a real session and continues the flow.
 *
 * Routes:
 *   GET  /onboard               - landing
 *   POST /onboard/code/issue    - mint a code for this guest session (JSON)
 *   GET  /onboard/code/poll     - has the bot received my DM yet? (JSON)
 *   GET  /onboard/next          - routes to /rules or /q/{id} or /done
 *   GET  /onboard/rules         - rules page
 *   POST /onboard/agree         - service.onAgree
 *   POST /onboard/disagree      - service.onDisagree
 *   GET  /onboard/q/{id}        - render question
 *   POST /onboard/q/{id}        - submit answer
 *   GET  /onboard/done          - terminal page
 */
public final class OnboardHandlers {

    /** Window during which a claimed code is honoured by the poller. */
    private static final long CLAIM_LOOKBACK_MS = 20L * 60_000L;
    /** Name of the unsigned guest pairing cookie. */
    private static final String GUEST_COOKIE = "warden_onboard_guest";
    private static final int GUEST_COOKIE_MAX_AGE = 30 * 60; // 30 min, slightly above code TTL

    private static final SecureRandom RAND = new SecureRandom();
    private static final Base64.Encoder GUEST_ENC = Base64.getUrlEncoder().withoutPadding();

    private final Services services;
    private final DiscordService discord;
    private final SessionCookie session;

    public OnboardHandlers(Services services, DiscordService discord, SessionCookie session) {
        this.services = services;
        this.discord = discord;
        this.session = session;
    }

    public void landing(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isPresent()) {
            ctx.redirect("/onboard/next");
            return;
        }
        // If this guest already paired in a recent window, finish the upgrade
        // and skip the picker entirely.
        String guestId = ctx.cookie(GUEST_COOKIE);
        if (guestId != null && !guestId.isBlank()) {
            Optional<LinkCodeDao.ClaimedCode> claimed = services.linkCodes.findClaimedFor(guestId, CLAIM_LOOKBACK_MS);
            if (claimed.isPresent()) {
                upgradeToSession(ctx, claimed.get().discordId());
                ctx.redirect("/onboard/next");
                return;
            }
        }

        FlowConfig flow = services.settingsDao.get().flow();
        boolean oauthAvailable = services.config.oauthConfigured() && flow.entryViaWebOauth();
        boolean codeAvailable = flow.entryViaWebCode();
        boolean both = oauthAvailable && codeAvailable;

        GuildBrand brand = guildBrand();
        String botMention = botMentionOrName();

        StringBuilder h = new StringBuilder(4096);
        h.append(pageHead("Onboard · " + brand.name));
        h.append(brandHeader(brand, "Welcome", "Let's get you set up to join " + esc(brand.name) + "."));
        h.append(noticeCard());

        h.append("<main class=public>");

        if (!oauthAvailable && !codeAvailable) {
            h.append("<section class=card>");
            h.append("<p class=warn>No web entry methods are enabled. Ask a moderator to enable them.</p>");
            h.append("</section>");
        }

        if (both) {
            h.append("<section class=card>");
            h.append("<p class=muted style=\"margin:0\">Pick the way you'd rather connect your Discord account. Both end in the same place.</p>");
            h.append("</section>");
        }

        if (oauthAvailable) {
            h.append("<section class=card>");
            h.append("<h2>").append(both ? "Sign in with Discord" : "Sign in with Discord").append("</h2>");
            h.append("<p class=muted>One click. We'll read your Discord username and match you to your record on the server.</p>");
            h.append("<form method=get action=/auth/discord/start>");
            h.append("<input type=hidden name=next value=/onboard/next>");
            h.append("<button class=\"btn primary\" type=submit>")
                    .append(discordGlyph()).append(" Sign in with Discord</button>");
            h.append("</form>");
            h.append("</section>");
        }

        if (codeAvailable) {
            h.append("<section class=card id=code-card>");
            h.append("<h2>").append(both ? "Or DM a code to the bot" : "DM a code to the bot").append("</h2>");
            h.append("<p class=muted>If you'd rather not authorise an app, request a one-time code below and DM it to ")
                    .append(esc(botMention)).append(" on Discord.</p>");

            h.append("<div id=code-empty>");
            h.append("<button id=get-code-btn class=\"btn ").append(both ? "ghost" : "primary").append("\" type=button>")
                    .append("Get a code</button>");
            h.append("</div>");

            h.append("<div id=code-ready hidden>");
            h.append("<p class=muted style=\"margin:0 0 .35rem\">DM this code to the bot:</p>");
            h.append("<div class=code-pill><code id=code-value>--------</code>");
            h.append("<button id=copy-btn class=\"btn ghost copy-btn\" type=button title=\"Copy code\">Copy</button>");
            h.append("</div>");
            h.append("<p class=muted small id=code-status>Waiting for your DM...</p>");
            h.append("<p class=muted small>Code expires in <span id=code-countdown>15:00</span>. ");
            h.append("If it lapses, click below for a new one.</p>");
            h.append("<button id=reissue-btn class=\"btn ghost\" type=button>Get a new code</button>");
            h.append("</div>");

            h.append("</section>");
            h.append("<script>").append(landingJs()).append("</script>");
        }

        h.append("</main>");
        h.append(pageFoot(brand));
        ctx.html(h.toString());
    }

    /** POST /onboard/code/issue -> {"code":"ABCD1234","expiresAt":1735200000000} */
    public void issueCode(Context ctx) throws Exception {
        FlowConfig flow = services.settingsDao.get().flow();
        if (!flow.entryViaWebCode()) {
            ctx.status(403).json(java.util.Map.of("error", "code entry disabled"));
            return;
        }
        String guestId = ctx.cookie(GUEST_COOKIE);
        if (guestId == null || guestId.isBlank() || !looksLikeGuestId(guestId)) {
            guestId = freshGuestId();
            ctx.cookie(buildGuestCookie(guestId));
        }
        LinkCodeDao.IssuedCode issued = services.linkCodes.issueForSession(guestId);
        services.audit.write("web", "link_code_issued", null,
                java.util.Map.of("guest", maskGuestId(guestId)));
        ctx.json(java.util.Map.of(
                "code", issued.code(),
                "expiresAt", issued.expiresAt()));
    }

    /** GET /onboard/code/poll -> {"linked":false} or {"linked":true,"next":"/onboard/next"} */
    public void pollCode(Context ctx) throws Exception {
        String guestId = ctx.cookie(GUEST_COOKIE);
        if (guestId == null || guestId.isBlank()) {
            ctx.json(java.util.Map.of("linked", false));
            return;
        }
        Optional<LinkCodeDao.ClaimedCode> claimed = services.linkCodes.findClaimedFor(guestId, CLAIM_LOOKBACK_MS);
        if (claimed.isEmpty()) {
            ctx.json(java.util.Map.of("linked", false));
            return;
        }
        upgradeToSession(ctx, claimed.get().discordId());
        ctx.json(java.util.Map.of("linked", true, "next", "/onboard/next"));
    }

    public void next(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isEmpty()) { ctx.redirect("/onboard"); return; }
        String did = sess.get().discordId();
        StepResult step = services.onboarding.nextStep(did);
        var user = services.userDao.findByDiscordId(did).orElse(null);
        if (user != null) {
            switch (user.state()) {
                case PENDING_LINK, LINKED -> { ctx.redirect("/onboard/rules"); return; }
                case AWAITING_ANSWERS -> {
                    if (step instanceof StepResult.AskQuestion aq) {
                        ctx.redirect("/onboard/q/" + aq.question().id()); return;
                    }
                    if (step instanceof StepResult.Submitted) { ctx.redirect("/onboard/done"); return; }
                }
                case AWAITING_REVIEW, APPROVED, DENIED -> { ctx.redirect("/onboard/done"); return; }
            }
        }
        ctx.redirect("/onboard/done");
    }

    public void rules(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isEmpty()) { ctx.redirect("/onboard"); return; }
        String did = sess.get().discordId();
        StepResult step = services.onboarding.onStart(did);
        if (!(step instanceof StepResult.ShowRules sr)) {
            ctx.redirect("/onboard/next"); return;
        }
        GuildBrand brand = guildBrand();
        StringBuilder h = new StringBuilder();
        h.append(pageHead("Server rules · " + brand.name));
        h.append(brandHeader(brand, "Server rules", "Please read these before continuing."));
        h.append(progressBar(0));
        h.append("<main class=public>");
        h.append("<section class=card>");
        String body = (sr.rulesMarkdown() == null || sr.rulesMarkdown().isBlank())
                ? "<em class=muted>The server admins haven't set rules text yet.</em>"
                : escMultiline(sr.rulesMarkdown());
        h.append("<article class=rules>").append(body).append("</article>");
        h.append("<div class=button-row>");
        h.append("<form method=post action=/onboard/agree style=\"display:inline\"><button class=\"btn primary\">I agree, continue</button></form>");
        h.append("<form method=post action=/onboard/disagree style=\"display:inline\"><button class=\"btn ghost\">I have questions</button></form>");
        h.append("</div>");
        h.append("</section>");
        h.append("</main>");
        h.append(pageFoot(brand));
        ctx.html(h.toString());
    }

    public void agree(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isEmpty()) { ctx.redirect("/onboard"); return; }
        services.onboarding.onAgree(sess.get().discordId());
        ctx.redirect("/onboard/next");
    }

    public void disagree(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isEmpty()) { ctx.redirect("/onboard"); return; }
        services.onboarding.onDisagree(sess.get().discordId());
        ctx.redirect("/onboard/done");
    }

    public void questionForm(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isEmpty()) { ctx.redirect("/onboard"); return; }
        long qid = Long.parseLong(ctx.pathParam("id"));
        Question q = services.questionDao.findById(qid).orElse(null);
        if (q == null || !q.active()) { ctx.redirect("/onboard/next"); return; }

        int total = services.questionDao.listActive().size();
        int answered = services.answerDao.listFor(sess.get().discordId()).size();
        int idx = Math.min(answered + 1, Math.max(total, 1));
        int pct = total <= 0 ? 0 : (int) Math.round(100.0 * (answered) / total);

        GuildBrand brand = guildBrand();
        StringBuilder h = new StringBuilder();
        h.append(pageHead("Question " + idx + " · " + brand.name));
        h.append(brandHeader(brand, "Question " + idx + " of " + total,
                "Answers are sent to the moderator team of " + esc(brand.name) + "."));
        h.append(progressBar(pct));
        h.append("<main class=public>");
        h.append(sensitiveInfoWarning());
        h.append("<section class=card>");
        h.append("<form method=post action=\"/onboard/q/").append(qid).append("\" class=stack>");
        h.append("<p class=prompt>").append(esc(q.prompt()))
                .append(q.required() ? " <span class=req>*</span>" : "")
                .append("</p>");
        switch (q.kind()) {
            case SHORT_TEXT -> h.append("<input name=value maxlength=280 ")
                    .append(q.required() ? "required " : "").append("autocomplete=off>");
            case LONG_TEXT  -> h.append("<textarea name=value rows=6 maxlength=4000 ")
                    .append(q.required() ? "required" : "").append("></textarea>");
            case SINGLE_CHOICE -> {
                h.append("<select name=value ").append(q.required() ? "required" : "").append(">");
                h.append("<option value=\"\" disabled selected>Choose...</option>");
                for (String c : q.choices()) {
                    h.append("<option value=\"").append(esc(c)).append("\">").append(esc(c)).append("</option>");
                }
                h.append("</select>");
            }
            case MULTI_CHOICE -> {
                h.append("<div class=choice-group>");
                for (String c : q.choices()) {
                    h.append("<label class=cb><input type=checkbox name=value value=\"")
                            .append(esc(c)).append("\"> <span>").append(esc(c)).append("</span></label>");
                }
                h.append("</div>");
            }
        }
        h.append("<div class=button-row><button class=\"btn primary\" type=submit>Submit answer</button></div>");
        h.append("</form>");
        h.append("</section>");
        h.append("</main>");
        h.append(pageFoot(brand));
        ctx.html(h.toString());
    }

    public void submitAnswer(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        if (sess.isEmpty()) { ctx.redirect("/onboard"); return; }
        long qid = Long.parseLong(ctx.pathParam("id"));
        Question q = services.questionDao.findById(qid).orElse(null);
        if (q == null) { ctx.redirect("/onboard/next"); return; }

        AnswerValue value;
        if (q.kind() == QuestionKind.MULTI_CHOICE) {
            List<String> vs = ctx.formParams("value");
            value = AnswerValue.of(vs == null ? List.<String>of() : new ArrayList<>(vs));
        } else {
            String v = ctx.formParam("value");
            value = AnswerValue.of(v == null ? "" : v);
        }
        StepResult result = services.onboarding.submitAnswer(sess.get().discordId(), qid, value);
        if (result instanceof StepResult.InvalidAnswer ia) {
            GuildBrand brand = guildBrand();
            StringBuilder h = new StringBuilder();
            h.append(pageHead("Try again · " + brand.name));
            h.append(brandHeader(brand, "Let's try that again", null));
            h.append("<main class=public>");
            h.append("<section class=card>");
            h.append("<p class=warn>").append(esc(humanReason(ia.reason()))).append("</p>");
            h.append("<p><a class=\"btn primary\" href=\"/onboard/q/").append(qid).append("\">&larr; Back to the question</a></p>");
            h.append("</section>");
            h.append("</main>");
            h.append(pageFoot(brand));
            ctx.html(h.toString());
            return;
        }
        ctx.redirect("/onboard/next");
    }

    public void done(Context ctx) throws Exception {
        Optional<SessionCookie.Session> sess = readSession(ctx);
        GuildBrand brand = guildBrand();
        StringBuilder h = new StringBuilder();
        h.append(pageHead("All set · " + brand.name));

        String title;
        String lede;
        String body;
        if (sess.isEmpty()) {
            title = "Nothing to do";
            lede = null;
            body = "<p>You're not currently in an onboarding flow.</p>";
        } else {
            var user = services.userDao.findByDiscordId(sess.get().discordId()).orElse(null);
            var state = user == null ? io.warden.onboarding.OnboardingState.PENDING_LINK : user.state();
            switch (state) {
                case APPROVED -> {
                    title = "You're in";
                    lede = "Welcome to " + esc(brand.name) + ".";
                    body = "<p>You've been approved. Hop back to the server to start chatting.</p>";
                }
                case DENIED -> {
                    title = "Not approved this time";
                    lede = null;
                    body = "<p>If you think this is a mistake, send a DM to one of the server moderators.</p>";
                }
                case AWAITING_REVIEW -> {
                    title = "Submitted";
                    lede = "Thanks for filling things out.";
                    body = "<p>A moderator from " + esc(brand.name)
                            + " will review your answers shortly. You'll get a DM with the decision.</p>";
                }
                default -> {
                    title = "All set";
                    lede = null;
                    body = "<p>You can safely close this tab.</p>";
                }
            }
        }
        h.append(brandHeader(brand, title, lede));
        h.append("<main class=public><section class=card>").append(body).append("</section></main>");
        h.append(pageFoot(brand));
        ctx.html(h.toString());
    }

    /* ---------- pairing helpers ---------- */

    private void upgradeToSession(Context ctx, String discordId) throws Exception {
        var user = services.userDao.findByDiscordId(discordId).orElse(null);
        String username = user == null ? "" : user.username();
        ctx.cookie(buildSessionCookie(session.encode(
                new SessionCookie.Session(discordId, username, false, System.currentTimeMillis() / 1000))));
        services.audit.write("web", "onboarding_linked", discordId, java.util.Map.of("via", "dm_code"));
    }

    private static String freshGuestId() {
        byte[] buf = new byte[18];
        RAND.nextBytes(buf);
        return GUEST_ENC.encodeToString(buf);
    }

    private static boolean looksLikeGuestId(String s) {
        if (s.length() < 22 || s.length() > 32) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static Cookie buildGuestCookie(String value) {
        Cookie c = new Cookie(GUEST_COOKIE, value);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(GUEST_COOKIE_MAX_AGE);
        c.setSameSite(io.javalin.http.SameSite.LAX);
        return c;
    }

    private static String maskGuestId(String guestId) {
        if (guestId == null || guestId.length() < 8) return "????";
        return guestId.substring(0, 4) + "..." + guestId.substring(guestId.length() - 4);
    }

    private String botMentionOrName() {
        if (discord != null && discord.jda() != null) {
            var self = discord.jda().getSelfUser();
            if (self != null) return "@" + self.getName();
        }
        return "the server bot";
    }

    /* ---------- branding helpers ---------- */

    private record GuildBrand(String name, String iconUrl, String accent) {}

    private GuildBrand guildBrand() {
        String name = "the server";
        String iconUrl = null;
        if (discord != null) {
            JDA jda = discord.jda();
            String gid = services.config.discordGuildId();
            if (jda != null && gid != null && !gid.isBlank()) {
                Guild g = jda.getGuildById(gid);
                if (g != null) {
                    name = g.getName();
                    String url = g.getIconUrl();
                    if (url != null) iconUrl = url + "?size=128";
                }
            }
        }
        return new GuildBrand(name, iconUrl, "#6b83ff");
    }

    private static String brandHeader(GuildBrand brand, String title, String lede) {
        StringBuilder s = new StringBuilder(512);
        s.append("<header class=hero>");
        s.append("<div class=hero-inner>");
        if (brand.iconUrl != null) {
            s.append("<img class=guild-icon src=\"").append(esc(brand.iconUrl))
                    .append("\" alt=\"").append(esc(brand.name)).append(" icon\" width=72 height=72>");
        } else {
            s.append("<div class=\"guild-icon guild-icon-fallback\" aria-hidden=true>")
                    .append(esc(initials(brand.name)))
                    .append("</div>");
        }
        s.append("<div class=hero-text>");
        s.append("<p class=server-name>").append(esc(brand.name)).append("</p>");
        s.append("<h1>").append(esc(title)).append("</h1>");
        if (lede != null && !lede.isBlank()) {
            s.append("<p class=lede>").append(lede).append("</p>");
        }
        s.append("</div></div></header>");
        return s.toString();
    }

    private static String noticeCard() {
        return "<section class=\"card notice\">"
                + "<div class=notice-row>"
                + "<span class=notice-icon aria-hidden=true>i</span>"
                + "<div><strong>Your answers go to the moderator team.</strong>"
                + " They'll review what you submit before approving you."
                + "</div></div>"
                + sensitiveInfoRow()
                + "</section>";
    }

    /** Slim warning shown on every question page where users type free-form answers. */
    private static String sensitiveInfoWarning() {
        return "<section class=\"card notice\">" + sensitiveInfoRow() + "</section>";
    }

    private static String sensitiveInfoRow() {
        return "<div class=notice-row>"
                + "<span class=\"notice-icon warn\" aria-hidden=true>!</span>"
                + "<div><strong>Don't share sensitive information.</strong>"
                + " No passwords, credit card numbers, social security numbers,"
                + " home address, or anything else you wouldn't want a moderator to see."
                + "</div></div>";
    }

    private static String progressBar(int pct) {
        int clamped = Math.max(0, Math.min(100, pct));
        return "<div class=progress role=progressbar aria-valuenow=" + clamped
                + " aria-valuemin=0 aria-valuemax=100>"
                + "<div class=progress-fill style=\"width:" + clamped + "%\"></div>"
                + "</div>";
    }

    private static String pageFoot(GuildBrand brand) {
        return "<footer class=site-foot>"
                + "<span>Onboarding for <strong>" + esc(brand.name) + "</strong></span>"
                + "<span class=dot>&middot;</span>"
                + "<span>Powered by Warden</span>"
                + "</footer>"
                + "</body></html>";
    }

    private static String discordGlyph() {
        return "<svg class=ico viewBox=\"0 0 24 24\" width=18 height=18 aria-hidden=true>"
                + "<path fill=\"currentColor\" d=\"M20.317 4.369A19.79 19.79 0 0 0 16.558 3a.07.07 0 0 0-.073.035c-.21.376-.444.866-.608 1.249a18.27 18.27 0 0 0-5.487 0 12.51 12.51 0 0 0-.617-1.25.07.07 0 0 0-.073-.034A19.736 19.736 0 0 0 6 4.369a.063.063 0 0 0-.03.025C2.79 9.045 2.082 13.58 2.43 18.057a.08.08 0 0 0 .03.054 19.9 19.9 0 0 0 5.992 3.029.07.07 0 0 0 .076-.026c.461-.63.873-1.295 1.226-1.994a.07.07 0 0 0-.038-.097 13.13 13.13 0 0 1-1.872-.892.07.07 0 0 1-.007-.117c.126-.094.252-.192.371-.291a.07.07 0 0 1 .073-.01c3.93 1.793 8.18 1.793 12.061 0a.07.07 0 0 1 .074.009c.12.1.245.198.372.292a.07.07 0 0 1-.006.117 12.36 12.36 0 0 1-1.873.891.07.07 0 0 0-.038.098c.36.699.772 1.363 1.225 1.993a.07.07 0 0 0 .076.027 19.83 19.83 0 0 0 6-3.029.07.07 0 0 0 .031-.053c.418-5.177-.7-9.673-2.967-13.663a.06.06 0 0 0-.03-.025zM8.02 15.331c-1.183 0-2.157-1.086-2.157-2.42 0-1.334.955-2.42 2.157-2.42 1.21 0 2.176 1.094 2.157 2.42 0 1.334-.956 2.42-2.157 2.42zm7.974 0c-1.183 0-2.157-1.086-2.157-2.42 0-1.334.955-2.42 2.157-2.42 1.21 0 2.176 1.094 2.157 2.42 0 1.334-.946 2.42-2.157 2.42z\"/>"
                + "</svg>";
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(2);
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            String p = parts[i];
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.codePointAt(0)));
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    /* ---------- session/page helpers ---------- */

    private Optional<SessionCookie.Session> readSession(Context ctx) {
        return session.decode(ctx.cookie(SessionCookie.COOKIE_NAME));
    }

    private static String pageHead(String title) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>" +
                "<meta name=viewport content=\"width=device-width,initial-scale=1\">" +
                "<title>" + esc(title) + "</title>" +
                Layout.THEME_BOOT_SCRIPT +
                "<link rel=icon type=\"image/svg+xml\" href=/static/img/warden-icon.svg>" +
                "<style>" + CSS + "</style></head><body>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escMultiline(String s) {
        return esc(s).replace("\n", "<br>");
    }

    private static String humanReason(String code) {
        return switch (code) {
            case "required"         -> "That question is required - please give an answer.";
            case "too_long_280"     -> "Your answer is over the 280-character limit.";
            case "too_long_4000"    -> "Your answer is over the 4000-character limit.";
            case "expected_text"    -> "Please type your answer.";
            case "expected_choice"  -> "Please pick one of the options.";
            case "not_in_choices"   -> "That isn't a valid option - pick from the list.";
            case "unknown_question" -> "That question is no longer active. Refresh and try again.";
            default                  -> "Your answer wasn't accepted. Please try again.";
        };
    }

    private static io.javalin.http.Cookie buildSessionCookie(String value) {
        Cookie c = new Cookie(SessionCookie.COOKIE_NAME, value);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(SessionCookie.MAX_AGE_SECONDS);
        c.setSameSite(io.javalin.http.SameSite.LAX);
        return c;
    }

    private static String landingJs() {
        // Vanilla JS, no fetch chains across files. Polls /onboard/code/poll
        // every 2.5s for up to 15 minutes (the code's TTL) and then prompts
        // the user to re-issue.
        return "(function(){\n"
                + "  var emptyBox=document.getElementById('code-empty');\n"
                + "  var readyBox=document.getElementById('code-ready');\n"
                + "  var codeEl=document.getElementById('code-value');\n"
                + "  var statusEl=document.getElementById('code-status');\n"
                + "  var countdownEl=document.getElementById('code-countdown');\n"
                + "  var getBtn=document.getElementById('get-code-btn');\n"
                + "  var reBtn=document.getElementById('reissue-btn');\n"
                + "  var copyBtn=document.getElementById('copy-btn');\n"
                + "  var pollTimer=null;var tickTimer=null;var expiresAt=0;\n"
                + "  function pad(n){return n<10?'0'+n:''+n;}\n"
                + "  function fmt(ms){if(ms<0)ms=0;var s=Math.floor(ms/1000);return Math.floor(s/60)+':'+pad(s%60);}\n"
                + "  function stopTimers(){if(pollTimer){clearInterval(pollTimer);pollTimer=null;}if(tickTimer){clearInterval(tickTimer);tickTimer=null;}}\n"
                + "  function tick(){var left=expiresAt-Date.now();countdownEl.textContent=fmt(left);if(left<=0){stopTimers();statusEl.textContent='Code expired. Get a new one to keep going.';}}\n"
                + "  function poll(){fetch('/onboard/code/poll',{credentials:'same-origin',headers:{'Accept':'application/json'}}).then(function(r){return r.json();}).then(function(j){if(j&&j.linked){stopTimers();statusEl.textContent='Linked! Redirecting...';window.location=j.next||'/onboard/next';}}).catch(function(){});}\n"
                + "  function showCode(code,exp){codeEl.textContent=code;expiresAt=exp;emptyBox.hidden=true;readyBox.hidden=false;statusEl.textContent='Waiting for your DM...';tick();stopTimers();pollTimer=setInterval(poll,2500);tickTimer=setInterval(tick,1000);}\n"
                + "  function issue(){fetch('/onboard/code/issue',{method:'POST',credentials:'same-origin',headers:{'Accept':'application/json'}}).then(function(r){return r.json();}).then(function(j){if(j&&j.code){showCode(j.code,j.expiresAt);}else{statusEl&&(statusEl.textContent='Could not get a code. Try again in a moment.');}}).catch(function(){if(statusEl)statusEl.textContent='Could not reach the server. Try again in a moment.';});}\n"
                + "  if(getBtn)getBtn.addEventListener('click',issue);\n"
                + "  if(reBtn)reBtn.addEventListener('click',issue);\n"
                + "  if(copyBtn)copyBtn.addEventListener('click',function(){var t=codeEl.textContent;if(!t||t.indexOf('-')===0)return;if(navigator.clipboard){navigator.clipboard.writeText(t).then(function(){copyBtn.textContent='Copied';setTimeout(function(){copyBtn.textContent='Copy';},1500);});}else{var ta=document.createElement('textarea');ta.value=t;document.body.appendChild(ta);ta.select();try{document.execCommand('copy');copyBtn.textContent='Copied';setTimeout(function(){copyBtn.textContent='Copy';},1500);}catch(e){}document.body.removeChild(ta);}});\n"
                + "})();";
    }

    private static final String CSS = """
            :root{
              color-scheme:light dark;
              --brand:#6b83ff; --brand-deep:#4a64e6;
              --bg:#f5f6fa; --bg-grad:radial-gradient(1200px 600px at 50% -200px,rgba(107,131,255,.16),transparent 70%);
              --card-bg:#ffffff; --card-border:#e2e4ea;
              --text:#181a23; --muted:#6c7080; --soft:#f1f2f7;
              --ok:#2faa5f; --warn-bg:#fff5d6; --warn-fg:#7a5800; --warn-border:#e7d394;
              --info-bg:rgba(107,131,255,.10); --info-fg:#3a4cb8;
              --notice-bg:#f7f8ff; --notice-border:#dfe3ff;
              --shadow:0 10px 30px -16px rgba(40,46,72,.16),0 2px 4px -2px rgba(40,46,72,.06);
              --radius:14px;
            }
            :root[data-theme=dark]{
              --bg:#0d0f15; --bg-grad:radial-gradient(1200px 600px at 50% -260px,rgba(107,131,255,.18),transparent 70%);
              --card-bg:#171922; --card-border:#262a36;
              --text:#ececf2; --muted:#9b9faf; --soft:#1c1e29;
              --warn-bg:#3a2f10; --warn-fg:#ffd97a; --warn-border:#6c5614;
              --info-bg:rgba(107,131,255,.18); --info-fg:#c3cdff;
              --notice-bg:#1a1d2c; --notice-border:#2a304a;
              --shadow:0 14px 40px -16px rgba(0,0,0,.6),0 2px 4px -2px rgba(0,0,0,.4);
            }
            @media (prefers-color-scheme:dark){
              :root:not([data-theme=light]){
                --bg:#0d0f15; --bg-grad:radial-gradient(1200px 600px at 50% -260px,rgba(107,131,255,.18),transparent 70%);
                --card-bg:#171922; --card-border:#262a36;
                --text:#ececf2; --muted:#9b9faf; --soft:#1c1e29;
                --warn-bg:#3a2f10; --warn-fg:#ffd97a; --warn-border:#6c5614;
                --info-bg:rgba(107,131,255,.18); --info-fg:#c3cdff;
                --notice-bg:#1a1d2c; --notice-border:#2a304a;
                --shadow:0 14px 40px -16px rgba(0,0,0,.6),0 2px 4px -2px rgba(0,0,0,.4);
              }
            }
            *,*::before,*::after{box-sizing:border-box}
            html,body{margin:0;padding:0}
            body{
              font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,
                          "Helvetica Neue",Arial,"Noto Sans",sans-serif;
              background:var(--bg);background-image:var(--bg-grad);background-repeat:no-repeat;
              color:var(--text);min-height:100vh;line-height:1.55;
              padding:2.5rem 1.25rem 4rem;
            }
            header.hero{max-width:640px;margin:0 auto 1.5rem}
            header.hero .hero-inner{display:flex;gap:1.2rem;align-items:center}
            .guild-icon{
              flex:0 0 auto;width:72px;height:72px;border-radius:18px;object-fit:cover;
              background:var(--card-bg);border:1px solid var(--card-border);
              box-shadow:var(--shadow);
            }
            .guild-icon-fallback{
              display:flex;align-items:center;justify-content:center;
              font-weight:700;font-size:1.6rem;letter-spacing:.04em;
              background:linear-gradient(135deg,#6b83ff,#4a64e6);color:#fff;
              border:none;
            }
            .hero-text{min-width:0;flex:1 1 auto}
            .server-name{
              margin:0 0 .15rem;color:var(--muted);font-size:.78rem;
              font-weight:600;text-transform:uppercase;letter-spacing:.08em;
            }
            header.hero h1{margin:0;font-size:1.7rem;line-height:1.2;letter-spacing:-.01em}
            header.hero p.lede{margin:.4rem 0 0;color:var(--muted);font-size:1.02rem}
            main.public{max-width:640px;margin:0 auto}
            section.card{
              background:var(--card-bg);border:1px solid var(--card-border);
              border-radius:var(--radius);padding:1.5rem 1.6rem;margin:1rem 0;
              box-shadow:var(--shadow);
            }
            section.card h2{margin:0 0 .4rem;font-size:1.05rem;letter-spacing:-.005em}
            .muted{color:var(--muted);margin-top:0}
            .small{font-size:.88em}
            p.warn{
              background:var(--warn-bg);color:var(--warn-fg);
              border:1px solid var(--warn-border);
              padding:.85rem 1rem;border-radius:10px;margin:0 0 1rem;
            }
            section.notice{padding:1rem 1.2rem;background:var(--notice-bg);border-color:var(--notice-border)}
            .notice-row{display:flex;gap:.85rem;align-items:flex-start;font-size:.93rem}
            .notice-row + .notice-row{margin-top:.7rem;padding-top:.7rem;border-top:1px dashed var(--notice-border)}
            .notice-row strong{color:var(--text)}
            .notice-row > div{color:var(--muted)}
            .notice-icon{
              flex:0 0 auto;width:1.5rem;height:1.5rem;border-radius:50%;
              display:flex;align-items:center;justify-content:center;
              font-weight:700;font-size:.85rem;font-style:italic;font-family:serif;
              background:var(--info-bg);color:var(--info-fg);
            }
            .notice-icon.warn{font-style:normal;background:var(--warn-bg);color:var(--warn-fg)}
            .progress{
              max-width:640px;margin:0 auto 1rem;height:6px;border-radius:99px;
              background:var(--soft);overflow:hidden;
            }
            .progress-fill{height:100%;background:linear-gradient(90deg,#6b83ff,#4a64e6);transition:width .25s ease}
            .stack > * + *{margin-top:1rem}
            .button-row{display:flex;gap:.6rem;flex-wrap:wrap;margin-top:1.25rem}
            input[type=text],input[name=value],input[type=number],select,textarea{
              width:100%;padding:.7em .85em;border:1px solid var(--card-border);border-radius:10px;
              font-size:1em;font-family:inherit;background:var(--soft);color:inherit;
              transition:border-color .12s,box-shadow .12s,background .12s;
            }
            input:focus,select:focus,textarea:focus{
              outline:none;border-color:var(--brand);background:var(--card-bg);
              box-shadow:0 0 0 4px rgba(107,131,255,.18);
            }
            textarea{resize:vertical;min-height:7em}
            .code-pill{
              display:flex;align-items:center;gap:.75rem;
              padding:.85rem 1rem;background:var(--soft);
              border:1px solid var(--card-border);border-radius:12px;
              margin:.4rem 0 .6rem;
            }
            .code-pill code{
              font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
              font-size:1.35em;letter-spacing:.4em;flex:1 1 auto;
              text-transform:uppercase;color:var(--text);
            }
            .copy-btn{padding:.45em .9em;font-size:.88em}
            .choice-group{display:flex;flex-direction:column;gap:.4rem;
              padding:.85rem 1rem;background:var(--soft);
              border:1px solid var(--card-border);border-radius:10px}
            label.cb{
              display:flex;gap:.65rem;align-items:center;cursor:pointer;
              padding:.35rem .15rem;border-radius:6px;
            }
            label.cb:hover{background:rgba(107,131,255,.06)}
            label.cb input{accent-color:var(--brand);width:1rem;height:1rem;margin:0}
            .prompt{font-size:1.1em;margin:0;font-weight:500}
            .req{color:#dc4040;font-weight:700}
            .btn{
              display:inline-flex;align-items:center;gap:.5rem;
              padding:.7em 1.4em;border-radius:10px;text-decoration:none;
              font-weight:600;font-size:.98em;font-family:inherit;
              border:1px solid transparent;background:var(--soft);color:var(--text);
              cursor:pointer;transition:transform .04s ease,background .12s,border-color .12s,box-shadow .12s;
            }
            .btn:hover{background:rgba(127,127,127,.08)}
            .btn:active{transform:translateY(1px)}
            .btn.primary{
              background:linear-gradient(180deg,#7d92ff,#5870f0);color:#fff;
              border-color:transparent;box-shadow:0 4px 12px -4px rgba(74,100,230,.5);
            }
            .btn.primary:hover{background:linear-gradient(180deg,#8b9eff,#6378f3)}
            .btn.ghost{background:transparent;border-color:var(--card-border);color:inherit}
            .btn.ghost:hover{background:rgba(127,127,127,.06)}
            .btn .ico{display:inline-block;vertical-align:-.18em}
            article.rules{
              white-space:normal;padding:1.1rem 1.25rem;background:var(--soft);
              border-left:3px solid var(--brand);border-radius:10px;
              max-height:55vh;overflow-y:auto;font-size:.96em;
            }
            article.rules em{color:var(--muted)}
            footer.site-foot{
              max-width:640px;margin:2.5rem auto 0;color:var(--muted);
              font-size:.82em;text-align:center;display:flex;gap:.5rem;
              justify-content:center;align-items:center;flex-wrap:wrap;
            }
            footer.site-foot .dot{opacity:.5}
            @media (max-width:520px){
              body{padding:1.75rem 1rem 3rem}
              header.hero .hero-inner{gap:1rem}
              header.hero h1{font-size:1.45rem}
              .guild-icon{width:60px;height:60px;border-radius:14px}
              section.card{padding:1.2rem 1.1rem;border-radius:12px}
              .code-pill code{letter-spacing:.3em;font-size:1.15em}
            }
            """;
}
