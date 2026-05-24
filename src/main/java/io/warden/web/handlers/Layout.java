package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.config.WardenConfig;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.PageAccess;
import io.warden.web.auth.SessionCookie;

import java.util.Optional;

/**
 * Shared AdminLTE 4 shell for /dash/* pages. Pages call {@link #head(String, String, Context)}
 * before their content and {@link #foot()} after. Asset URLs are pinned CDN
 * references; swap them for /static/* paths if you bundle the files locally.
 */
public final class Layout {

    private Layout() {}

    /**
     * Per-request {@link PageAccess} reference. WebService stashes it on each
     * request via ctx.attribute so {@link #head(String, String, Context)} can
     * use it without a thread-local. Falls back to {@code null}, which means
     * "show everything the session.mod() flag allows" (back-compat).
     */
    public static final String CTX_PAGE_ACCESS = "warden.pageAccess";

    /**
     * Per-request {@link WardenConfig.Modules} reference. WebService attaches it
     * inside the /dash before-handlers so the sidebar can drop disabled-module
     * entries entirely. Falls back to "all modules on" when null - e.g. on
     * pages rendered outside /dash, or in tests.
     */
    public static final String CTX_MODULES = "warden.modules";

    private static final String BOOTSTRAP_CSS =
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css";
    private static final String BOOTSTRAP_ICONS =
            "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.13.1/font/bootstrap-icons.min.css";
    private static final String ADMINLTE_CSS =
            "https://cdn.jsdelivr.net/npm/admin-lte@4.0.0-rc7/dist/css/adminlte.min.css";
    private static final String BOOTSTRAP_JS =
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js";
    private static final String ADMINLTE_JS =
            "https://cdn.jsdelivr.net/npm/admin-lte@4.0.0-rc7/dist/js/adminlte.min.js";

    /**
     * Inline script that picks up the saved theme preference and applies it
     * to <html> before any styles render, so other pages stay consistent and
     * there's no flash of the wrong colour scheme.
     *
     * Three modes are stored in localStorage under {@code warden.theme}:
     *   - "light"  → force light
     *   - "dark"   → force dark
     *   - anything else (or absent) → follow OS prefers-color-scheme
     *
     * Sets both {@code data-bs-theme} (used by the Bootstrap pages) and
     * {@code data-theme} (used by the public-facing onboarding/welcome pages
     * whose CSS isn't Bootstrap-based) so a single switch covers everything.
     */
    public static final String THEME_BOOT_SCRIPT =
            "<script>(function(){"
            + "var KEY='warden.theme';"
            + "var saved=null;try{saved=localStorage.getItem(KEY);}catch(_){}"
            + "var mode=(saved==='light'||saved==='dark')?saved:'system';"
            + "var sys=window.matchMedia&&window.matchMedia('(prefers-color-scheme:dark)').matches;"
            + "var eff=(mode==='dark')||(mode==='system'&&sys)?'dark':'light';"
            + "var html=document.documentElement;"
            + "html.setAttribute('data-bs-theme',eff);"
            + "html.setAttribute('data-theme',eff);"
            + "html.dataset.themeMode=mode;"
            + "})();</script>";

    /** Backwards-compat overload (no session/username chip rendered). */
    public static String head(String title, String activeNav) {
        return head(title, activeNav, null);
    }

    /**
     * Open the document, render the AdminLTE shell (top navbar + sidebar with
     * user chip at the bottom), and open the content container. The caller
     * writes their body and closes with {@link #foot()}.
     *
     * @param activeNav one of "pending", "audit", "members", "stats", "invites", "config",
     *                  "moderation", "violations", "levels", "reaction-roles", "engagement",
     *                  "tickets", "alerts", or null for none.
     * @param ctx       request context; used to read the current mod session for
     *                  the user chip. May be null on background-rendered fragments.
     */
    public static String head(String title, String activeNav, Context ctx) {
        Optional<SessionCookie.Session> sess = ctx == null ? Optional.empty() : DashAuth.sessionOf(ctx);
        // Prefer Discord's global_name (display name, original casing) over the
        // unique username; fall back to username, then to a generic "Mod" label.
        String displayName = sess.map(SessionCookie.Session::displayName).orElse("");
        if (displayName == null || displayName.isBlank()) {
            displayName = sess.map(SessionCookie.Session::username).orElse("");
        }
        if (displayName == null || displayName.isBlank()) displayName = "Mod";
        boolean isMod = sess.map(SessionCookie.Session::mod).orElse(false);
        // Pick the highest-rank label that applies. Owner implies admin/web,
        // so we check from the top down. Falls back to "Signed in" when none
        // of the role flags are set (which means they got in via the legacy
        // session-secret token path).
        String roleLabel = "Signed in";
        if (sess.isPresent()) {
            var ss = sess.get();
            if (ss.owner()) roleLabel = "Owner";
            else if (ss.configAdmin()) roleLabel = "Admin";
            else if (ss.mod()) roleLabel = "Mod";
            else if (ss.webManager()) roleLabel = "Web Admin";
        }
        String discordId = sess.map(SessionCookie.Session::discordId).orElse("");
        String avatarHash = sess.map(SessionCookie.Session::avatar).orElse("");
        String avatarUrl = "";
        if (avatarHash != null && !avatarHash.isBlank()
                && discordId != null && !discordId.isBlank()) {
            String ext = avatarHash.startsWith("a_") ? "gif" : "png";
            avatarUrl = "https://cdn.discordapp.com/avatars/" + discordId
                    + "/" + avatarHash + "." + ext + "?size=64";
        }

        StringBuilder s = new StringBuilder(2048);
        s.append("<!doctype html><html lang=en><head>")
                .append("<meta charset=utf-8>")
                .append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(title)).append("</title>")
                .append(THEME_BOOT_SCRIPT)
                .append("<link rel=icon type=\"image/svg+xml\" href=\"/static/img/warden-icon.svg\">")
                .append("<link rel=stylesheet href=\"").append(BOOTSTRAP_CSS).append("\">")
                .append("<link rel=stylesheet href=\"").append(BOOTSTRAP_ICONS).append("\">")
                .append("<link rel=stylesheet href=\"").append(ADMINLTE_CSS).append("\">")
                .append("<style>").append(CUSTOM_CSS).append("</style>")
                .append("</head>");

        s.append("<body class=\"layout-fixed sidebar-expand-lg bg-body-tertiary\">");
        s.append("<div class=app-wrapper>");

        // Top navbar (sidebar toggle on the left).
        s.append("<nav class=\"app-header navbar navbar-expand bg-body\">")
                .append("<div class=container-fluid>")
                .append("<ul class=navbar-nav>")
                .append("<li class=nav-item>")
                .append("<a class=nav-link data-lte-toggle=sidebar href=# role=button aria-label=\"toggle sidebar\">")
                .append("<i class=\"bi bi-list\"></i></a></li>")
                .append("</ul>")
                .append("</div></nav>");

        // Sidebar with brand + nav items + user chip docked at the bottom.
        s.append("<aside class=\"app-sidebar bg-body-secondary shadow\" data-bs-theme=dark>")
                .append("<div class=sidebar-brand>")
                .append("<a href=/dash class=brand-link>")
                .append("<img src=/static/img/warden-icon.svg alt=Warden class=\"brand-image opacity-75 shadow\">")
                .append("<span class=\"brand-text fw-light\">Warden</span>")
                .append("</a></div>")
                .append("<div class=sidebar-wrapper>")
                .append("<nav class=mt-2>")
                .append("<ul class=\"nav sidebar-menu flex-column\" data-lte-toggle=treeview role=menu>");
        boolean canEditLanding = sess.map(SessionCookie.Session::canEditLanding).orElse(false);
        PageAccess access = ctx == null ? null : (PageAccess) ctx.attribute(CTX_PAGE_ACCESS);
        WardenConfig.Modules mods = ctx == null ? null : (WardenConfig.Modules) ctx.attribute(CTX_MODULES);
        if (mods == null) mods = WardenConfig.Modules.allOn();
        s.append(pageNav("stats",          activeNav, "/dash/stats",          "bi-bar-chart",     "Stats",                sess, access, isMod));
        s.append(pageNav("pending",        activeNav, "/dash/pending",        "bi-inbox",         "Pending",              sess, access, isMod));
        s.append(pageNav("audit",          activeNav, "/dash/audit",          "bi-journal-text",  "Audit",                sess, access, isMod));
        s.append(pageNav("members",        activeNav, "/dash/members",        "bi-people",        "Members",              sess, access, isMod));
        s.append(pageNav("invites",        activeNav, "/dash/invites",        "bi-link-45deg",    "Invites",              sess, access, isMod));
        if (mods.moderation())     s.append(pageNav("moderation",     activeNav, "/dash/moderation",     "bi-shield-check",  "Moderation",           sess, access, isMod));
        if (mods.violations())     s.append(pageNav("violations",     activeNav, "/dash/violations",     "bi-bug",           "Violations",           sess, access, isMod));
        if (mods.levels())         s.append(pageNav("levels",         activeNav, "/dash/levels",         "bi-bar-chart-line","Levels and XP",        sess, access, isMod));
        if (mods.reactionRoles())  s.append(pageNav("reaction-roles", activeNav, "/dash/reaction-roles", "bi-emoji-smile",   "Reaction roles",       sess, access, isMod));
        if (mods.engagement())     s.append(pageNav("engagement",     activeNav, "/dash/engagement",     "bi-megaphone",     "Polls and giveaways",  sess, access, isMod));
        if (mods.tickets())        s.append(pageNav("tickets",        activeNav, "/dash/tickets",        "bi-life-preserver","Tickets",              sess, access, isMod));
        if (mods.feedback())       s.append(pageNav("feedback",       activeNav, "/dash/feedback",       "bi-chat-quote",    "Feedback",             sess, access, isMod));
        if (mods.alerts())         s.append(pageNav("alerts",         activeNav, "/dash/alerts",         "bi-bell",          "Alerts",               sess, access, isMod));
        if (mods.autoresponders()) s.append(pageNav("autoresponders", activeNav, "/dash/autoresponders", "bi-chat-dots",     "Autoresponders",       sess, access, isMod));
        if (mods.eventsTimezones()) {
            s.append(pageNav("scheduler",  activeNav, "/dash/scheduler",      "bi-calendar-event","Event scheduler",      sess, access, isMod));
            // Timezone picker: open to everyone with a session; we render it as a
            // plain entry rather than going through PageAccess.
            s.append(navItem("timezone",   activeNav, "/tz",                  "bi-globe2",        "My timezone",     true));
        }
        s.append(navItem("config",         activeNav, "/dash/config",         "bi-gear",          "Config",  canEditLanding));
        boolean canEditPerms = sess.map(SessionCookie.Session::canEditConfig).orElse(false);
        s.append(navItem("permissions",    activeNav, "/dash/permissions",    "bi-shield-lock",   "Permissions", canEditPerms));
        s.append(navItem("api-keys",       activeNav, "/dash/api-keys",       "bi-key",           "API keys",    canEditPerms));
        s.append(navItem("https",          activeNav, "/dash/https",          "bi-shield-shaded", "HTTPS",       canEditPerms));
        s.append(navItem("debug",          activeNav, "/dash/debug",          "bi-bug",           "Debug",       canEditPerms));
        s.append(navItem("about",          activeNav, "/dash/about",          "bi-info-circle",   "About",        true));
        s.append("</ul></nav></div>");

        // User chip - sits flush against the bottom of the sidebar. Renders the
        // Discord avatar when the session cookie carries an avatar hash, and
        // falls back to a generic icon otherwise. Sign-out is a form-POST so it
        // works without JavaScript.
        s.append("<div class=\"warden-user-chip\">");
        if (!avatarUrl.isBlank()) {
            s.append("<img class=\"wuc-avatar wuc-avatar-img\" src=\"").append(escape(avatarUrl))
                    .append("\" alt=\"\" aria-hidden=true>");
        } else {
            s.append("<div class=\"wuc-avatar\" aria-hidden=true><i class=\"bi bi-person-fill\"></i></div>");
        }
        s.append("<div class=\"wuc-meta\">")
                .append("<div class=\"wuc-name\" title=\"").append(escape(displayName)).append("\">")
                .append(escape(displayName)).append("</div>")
                .append("<div class=\"wuc-sub\">").append(escape(roleLabel)).append("</div>")
                .append("</div>")
                .append("<div class=\"wuc-actions\">")
                .append("<div class=\"dropdown\">")
                .append("<button class=\"wuc-btn\" type=button id=\"wuc-theme\" data-bs-toggle=dropdown")
                .append(" aria-expanded=false title=\"Theme\" aria-label=\"Theme\">")
                .append("<i class=\"bi bi-circle-half\" id=\"wuc-theme-icon\"></i>")
                .append("</button>")
                .append("<ul class=\"dropdown-menu dropdown-menu-end\" aria-labelledby=\"wuc-theme\">")
                .append("<li><button class=\"dropdown-item\" type=button data-theme-set=\"system\">")
                .append("<i class=\"bi bi-circle-half me-2\"></i>System</button></li>")
                .append("<li><button class=\"dropdown-item\" type=button data-theme-set=\"light\">")
                .append("<i class=\"bi bi-sun me-2\"></i>Light</button></li>")
                .append("<li><button class=\"dropdown-item\" type=button data-theme-set=\"dark\">")
                .append("<i class=\"bi bi-moon-stars me-2\"></i>Dark</button></li>")
                .append("</ul>")
                .append("</div>")
                .append("<form method=post action=\"/auth/logout\" class=\"wuc-logout\">")
                .append("<button type=submit class=\"wuc-btn\" title=\"Sign out\" aria-label=\"Sign out\">")
                .append("<i class=\"bi bi-box-arrow-right\"></i>")
                .append("</button>")
                .append("</form>")
                .append("</div>")
                .append("</div>");

        s.append("</aside>");

        // Open main content.
        s.append("<main class=app-main>")
                .append("<div class=app-content>")
                .append("<div class=container-fluid>");
        return s.toString();
    }

    /** Close the content container, sidebar wrapper, body. Loads JS at the end. */
    public static String foot() {
        return "</div></div></main></div>"
                + AUTH_MODAL_MARKUP
                + WARDEN_POPUP_MARKUP
                + "<script src=\"" + BOOTSTRAP_JS + "\"></script>"
                + "<script src=\"" + ADMINLTE_JS + "\"></script>"
                + "<script>"
                + "document.querySelectorAll('[data-bs-toggle=\"tooltip\"]').forEach(function(el){new bootstrap.Tooltip(el);});"
                + "</script>"
                + AUTH_MODAL_SCRIPT
                + WARDEN_POPUP_SCRIPT
                + FORM_VALIDATION_FIX
                + THEME_PICKER_SCRIPT
                + "</body></html>";
    }

    /**
     * AdminLTE 4 auto-injects an {@code .invalid-feedback} div below any form
     * control that fails HTML5 validation (and slaps {@code is-invalid} on the
     * input). Two problems with that:
     *   - The injected div is part of the layout, so it visibly pushes content
     *     below the form when validation fires.
     *   - AdminLTE never removes the div or the {@code is-invalid} class once
     *     the user fixes the input, so a stale error sticks around.
     *
     * Fix: hide the inline feedback entirely (the browser's own validation
     * bubble already shows the same message in an overlay), and listen for
     * {@code input} events to strip {@code is-invalid} and the dangling
     * feedback node as soon as the field becomes valid.
     */
    private static final String FORM_VALIDATION_FIX =
            "<style>.invalid-feedback{display:none!important}</style>"
            + "<script>(function(){"
            + "function clear(el){"
            + "el.classList.remove('is-invalid');"
            + "var id=el.getAttribute('aria-describedby');"
            + "if(id){var fb=document.getElementById(id);"
            + "if(fb&&fb.classList.contains('invalid-feedback')){fb.parentNode.removeChild(fb);}"
            + "el.removeAttribute('aria-describedby');}"
            + "}"
            + "document.addEventListener('input',function(e){"
            + "var el=e.target;"
            + "if(!el||!el.classList||!el.classList.contains('is-invalid'))return;"
            + "if(typeof el.checkValidity==='function'&&el.checkValidity()){clear(el);}"
            + "},true);"
            + "document.addEventListener('change',function(e){"
            + "var el=e.target;"
            + "if(!el||!el.classList||!el.classList.contains('is-invalid'))return;"
            + "if(typeof el.checkValidity==='function'&&el.checkValidity()){clear(el);}"
            + "},true);"
            + "})();</script>";

    /**
     * Build a redirect path that carries a flash message back to a dash page.
     * The receiver doesn't need to do anything special: {@link #WARDEN_POPUP_SCRIPT}
     * picks the params up on page load, pops a styled toast, and strips them
     * from the URL so refreshes don't re-flash.
     *
     * @param kind one of "success", "info", "warn", "error"
     */
    public static String flashRedirect(String target, String kind, String message) {
        StringBuilder s = new StringBuilder(target);
        s.append(target.contains("?") ? "&" : "?");
        s.append("flash=").append(urlEncodeParam(message == null ? "" : message));
        s.append("&flash_kind=").append(urlEncodeParam(kind == null ? "info" : kind));
        return s.toString();
    }

    private static String urlEncodeParam(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Bootstrap modal shown when a user clicks a locked sidebar entry. */
    private static final String AUTH_MODAL_MARKUP =
            "<div class=\"modal fade\" id=\"warden-auth-modal\" tabindex=\"-1\" aria-hidden=\"true\">"
            + "<div class=\"modal-dialog modal-dialog-centered\">"
            + "  <div class=\"modal-content\">"
            + "    <div class=\"modal-header border-0 pb-0\">"
            + "      <h5 class=\"modal-title d-flex align-items-center gap-2\">"
            + "        <i class=\"bi bi-shield-lock text-warning\"></i>"
            + "        <span id=\"warden-auth-modal-title\">Restricted</span>"
            + "      </h5>"
            + "      <button type=\"button\" class=\"btn-close\" data-bs-dismiss=\"modal\" aria-label=\"Close\"></button>"
            + "    </div>"
            + "    <div class=\"modal-body\"><p id=\"warden-auth-modal-body\" class=\"mb-0 text-secondary\"></p></div>"
            + "    <div class=\"modal-footer border-0 pt-0\">"
            + "      <button type=\"button\" class=\"btn btn-secondary\" data-bs-dismiss=\"modal\">Got it</button>"
            + "    </div>"
            + "  </div>"
            + "</div></div>";

    /**
     * Shared confirm modal and toast host. The confirm modal is reused across
     * the dash to replace native {@code confirm()} dialogs: forms with a
     * {@code data-confirm="..."} attribute have their submit intercepted, the
     * modal pops with that message, and the form only submits on OK. The
     * toast host floats in the top-right corner and is driven by
     * {@code WardenToast.show(...)} or the {@code ?flash=...&flash_kind=...}
     * query param convention.
     */
    private static final String WARDEN_POPUP_MARKUP =
            // Confirm modal: title + body are filled in by JS, callback wired
            // up per-show. Uses the same Bootstrap modal pattern as the auth
            // modal so it inherits the theme.
            "<div class=\"modal fade\" id=\"warden-confirm-modal\" tabindex=\"-1\" aria-hidden=\"true\">"
            + "<div class=\"modal-dialog modal-dialog-centered\">"
            + "  <div class=\"modal-content\">"
            + "    <div class=\"modal-header border-0 pb-0\">"
            + "      <h5 class=\"modal-title d-flex align-items-center gap-2\">"
            + "        <i class=\"bi bi-question-circle text-primary\" id=\"warden-confirm-modal-icon\"></i>"
            + "        <span id=\"warden-confirm-modal-title\">Confirm</span>"
            + "      </h5>"
            + "      <button type=\"button\" class=\"btn-close\" data-bs-dismiss=\"modal\" aria-label=\"Close\"></button>"
            + "    </div>"
            + "    <div class=\"modal-body\"><p id=\"warden-confirm-modal-body\" class=\"mb-0 text-secondary\"></p></div>"
            + "    <div class=\"modal-footer border-0 pt-0\">"
            + "      <button type=\"button\" class=\"btn btn-light\" data-bs-dismiss=\"modal\" id=\"warden-confirm-cancel\">Cancel</button>"
            + "      <button type=\"button\" class=\"btn btn-primary\" id=\"warden-confirm-ok\">OK</button>"
            + "    </div>"
            + "  </div>"
            + "</div></div>"
            // Toast host (top-right). Toasts get appended here.
            + "<div id=\"warden-toast-host\" class=\"warden-toast-host\" aria-live=\"polite\" aria-atomic=\"true\"></div>";

    private static final String WARDEN_POPUP_SCRIPT =
            "<script>(function(){"
            // ------ Toast ------
            + "var host=document.getElementById('warden-toast-host');"
            + "function escapeHtml(s){return (s==null?'':String(s)).replace(/[&<>\"']/g,function(c){"
            + "return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;','\\'':'&#39;'}[c];});}"
            + "function classForKind(k){return ({success:'wt-success',info:'wt-info',warn:'wt-warn',warning:'wt-warn',error:'wt-error',danger:'wt-error'})[k]||'wt-info';}"
            + "function iconForKind(k){return ({success:'bi-check-circle-fill',info:'bi-info-circle-fill',warn:'bi-exclamation-triangle-fill',warning:'bi-exclamation-triangle-fill',error:'bi-x-octagon-fill',danger:'bi-x-octagon-fill'})[k]||'bi-info-circle-fill';}"
            + "function toast(opts){"
            + "if(!host)return;"
            + "var o=(typeof opts==='string')?{message:opts}:(opts||{});"
            + "var kind=o.kind||'info';"
            + "var timeout=(o.timeout==null)?5000:o.timeout;"
            + "var el=document.createElement('div');"
            + "el.className='warden-toast '+classForKind(kind);"
            + "el.setAttribute('role',kind==='error'?'alert':'status');"
            + "var title=o.title?'<div class=\"wt-title\">'+escapeHtml(o.title)+'</div>':'';"
            + "el.innerHTML='<i class=\"bi '+iconForKind(kind)+' wt-icon\"></i>'"
            + "+'<div class=\"wt-body\">'+title+'<div class=\"wt-msg\">'+escapeHtml(o.message||'')+'</div></div>'"
            + "+'<button type=\"button\" class=\"wt-close\" aria-label=\"Dismiss\"><i class=\"bi bi-x\"></i></button>';"
            + "host.appendChild(el);"
            + "requestAnimationFrame(function(){el.classList.add('wt-show');});"
            + "var dismiss=function(){"
            + "if(el.dataset.dismissed==='1')return;"
            + "el.dataset.dismissed='1';"
            + "el.classList.remove('wt-show');"
            + "el.classList.add('wt-leave');"
            + "setTimeout(function(){if(el.parentNode)el.parentNode.removeChild(el);},220);"
            + "};"
            + "el.querySelector('.wt-close').addEventListener('click',dismiss);"
            + "if(timeout>0)setTimeout(dismiss,timeout);"
            + "return {dismiss:dismiss};"
            + "}"
            + "window.WardenToast={show:toast};"
            // ------ Confirm / Alert ------
            + "var modalEl=document.getElementById('warden-confirm-modal');"
            + "var titleEl=document.getElementById('warden-confirm-modal-title');"
            + "var bodyEl=document.getElementById('warden-confirm-modal-body');"
            + "var iconEl=document.getElementById('warden-confirm-modal-icon');"
            + "var okBtn=document.getElementById('warden-confirm-ok');"
            + "var cancelBtn=document.getElementById('warden-confirm-cancel');"
            + "var bs=window.bootstrap;"
            + "function buildConfirm(opts){"
            + "var o=(typeof opts==='string')?{message:opts}:(opts||{});"
            + "return new Promise(function(resolve){"
            + "if(!modalEl||!bs){resolve(window.confirm(o.message||'Are you sure?'));return;}"
            + "titleEl.textContent=o.title||'Confirm';"
            + "bodyEl.textContent=o.message||'Are you sure?';"
            + "var danger=o.danger===true||o.kind==='danger'||o.kind==='error';"
            + "iconEl.className='bi '+(danger?'bi-exclamation-triangle-fill text-danger':'bi-question-circle text-primary');"
            + "okBtn.textContent=o.okLabel||(danger?'Delete':'Confirm');"
            + "okBtn.className='btn '+(danger?'btn-danger':'btn-primary');"
            + "cancelBtn.textContent=o.cancelLabel||'Cancel';"
            + "var modal=bs.Modal.getOrCreateInstance(modalEl);"
            + "var decided=false;"
            + "function cleanup(){okBtn.removeEventListener('click',onOk);modalEl.removeEventListener('hidden.bs.modal',onHide);}"
            + "function onOk(){decided=true;cleanup();modal.hide();resolve(true);}"
            + "function onHide(){if(decided)return;cleanup();resolve(false);}"
            + "okBtn.addEventListener('click',onOk);"
            + "modalEl.addEventListener('hidden.bs.modal',onHide);"
            + "modal.show();"
            + "setTimeout(function(){try{okBtn.focus();}catch(_){}},120);"
            + "});"
            + "}"
            + "window.WardenConfirm=buildConfirm;"
            + "window.WardenAlert=function(opts){"
            + "var o=(typeof opts==='string')?{message:opts}:(opts||{});"
            + "toast({kind:o.kind||'info',title:o.title,message:o.message||'',timeout:o.timeout==null?6000:o.timeout});"
            + "return Promise.resolve();"
            + "};"
            // Form interceptor: any form with data-confirm gets a styled confirm
            // before submitting. data-confirm-title / data-confirm-kind let
            // callers override the title and danger styling.
            + "document.addEventListener('submit',function(e){"
            + "var form=e.target;"
            + "if(!form||form.tagName!=='FORM')return;"
            + "var msg=form.getAttribute('data-confirm');"
            + "if(!msg)return;"
            + "if(form.dataset.wConfirmed==='1'){form.dataset.wConfirmed='';return;}"
            + "e.preventDefault();"
            + "var submitter=e.submitter;"
            + "buildConfirm({"
            + "message:msg,"
            + "title:form.getAttribute('data-confirm-title')||'Confirm',"
            + "kind:form.getAttribute('data-confirm-kind')||'',"
            + "okLabel:form.getAttribute('data-confirm-ok')||''"
            + "}).then(function(ok){"
            + "if(!ok)return;"
            + "form.dataset.wConfirmed='1';"
            // Preserve the submitter (so name=value pairs on submit buttons still get sent).
            + "if(submitter&&submitter.name){"
            + "var hidden=document.createElement('input');"
            + "hidden.type='hidden';hidden.name=submitter.name;hidden.value=submitter.value;"
            + "hidden.setAttribute('data-w-confirm-submitter','1');"
            + "form.appendChild(hidden);"
            + "}"
            // If the form was submitted by a non-form button (rare), call requestSubmit so the submit event fires again.
            + "if(typeof form.requestSubmit==='function'){form.requestSubmit(submitter||undefined);}else{form.submit();}"
            + "});"
            + "},true);"
            // Flash from URL: ?flash=...&flash_kind=... pops a toast and is stripped.
            + "try{"
            + "var url=new URL(window.location.href);"
            + "var fm=url.searchParams.get('flash');"
            + "if(fm){"
            + "var fk=url.searchParams.get('flash_kind')||'info';"
            + "var ft=url.searchParams.get('flash_title')||'';"
            + "toast({kind:fk,title:ft||undefined,message:fm});"
            + "url.searchParams.delete('flash');"
            + "url.searchParams.delete('flash_kind');"
            + "url.searchParams.delete('flash_title');"
            + "window.history.replaceState({},document.title,url.pathname+(url.search?url.search:'')+url.hash);"
            + "}"
            + "}catch(_){}"
            + "})();</script>";

    private static final String AUTH_MODAL_SCRIPT =
            "<script>(function(){"
            + "var modalEl=document.getElementById('warden-auth-modal');if(!modalEl)return;"
            + "var titleEl=document.getElementById('warden-auth-modal-title');"
            + "var bodyEl=document.getElementById('warden-auth-modal-body');"
            + "var bs=window.bootstrap;if(!bs)return;"
            + "var modal=bs.Modal.getOrCreateInstance(modalEl);"
            + "document.querySelectorAll('[data-locked=\"1\"]').forEach(function(a){"
            + "a.addEventListener('click',function(e){"
            + "e.preventDefault();"
            + "titleEl.textContent=a.getAttribute('data-locked-title')||'Restricted';"
            + "bodyEl.textContent=a.getAttribute('data-locked-body')||'';"
            + "modal.show();"
            + "});});"
            + "})();</script>";

    private static String navItem(String key, String active, String href, String icon, String label) {
        return navItem(key, active, href, icon, label, true);
    }

    /**
     * Sidebar entry whose enabled-ness comes from {@link PageAccess}. When
     * {@code access} is null (e.g. tests, or a fragment rendered without a
     * request context), falls back to the legacy {@code mod} boolean so we
     * don't surprise existing surfaces. The locked-modal copy still names the
     * exact role-set the operator can configure.
     */
    private static String pageNav(String key, String active, String href, String icon, String label,
                                  Optional<SessionCookie.Session> sess, PageAccess access,
                                  boolean modFallback) {
        boolean enabled;
        if (access != null && sess.isPresent()) {
            enabled = access.canAccess(sess.get(), key);
        } else {
            enabled = modFallback;
        }
        if (enabled) {
            return navItem(key, active, href, icon, label, true);
        }
        boolean isActive = key.equals(active);
        String cls = "nav-link" + (isActive ? " active" : "") + " warden-nav-locked";
        String body = "This page is restricted. Ask an admin to grant you one of the roles "
                + "listed for " + label + " on the dashboard's Permissions tab.";
        return "<li class=nav-item><a href=\"" + href + "\" class=\"" + cls + "\""
                + " data-locked=1 data-locked-title=\"" + escape(label) + " is restricted\""
                + " data-locked-body=\"" + escape(body) + "\""
                + " aria-disabled=true>"
                + "<i class=\"nav-icon bi " + icon + "\"></i> <p>"
                + escape(label) + " <i class=\"bi bi-lock-fill ms-1 warden-nav-lockicon\" aria-hidden=true></i></p></a></li>";
    }

    /**
     * Renders a sidebar entry. When {@code enabled} is false the entry is shown
     * with a lock badge; clicking it pops a Bootstrap modal explaining the
     * required role rather than navigating to a 401. Direct URL entry still
     * lands on the 401 page from the server-side route guard.
     */
    private static String navItem(String key, String active, String href, String icon, String label, boolean enabled) {
        boolean isActive = key.equals(active);
        String cls = "nav-link" + (isActive ? " active" : "") + (enabled ? "" : " warden-nav-locked");
        if (enabled) {
            return "<li class=nav-item><a href=\"" + href + "\" class=\"" + cls + "\">"
                    + "<i class=\"nav-icon bi " + icon + "\"></i> <p>"
                    + escape(label) + "</p></a></li>";
        }
        // Locked entries: stay tabbable (a11y), but the JS intercept routes the
        // click to the shared #warden-auth-modal. data-locked-* carry the modal copy.
        String body = "Sign in with the Config admin or Web manager role to use the "
                + label + " page. Ask a server owner to grant you one of those roles.";
        return "<li class=nav-item><a href=\"" + href + "\" class=\"" + cls + "\""
                + " data-locked=1 data-locked-title=\"" + escape(label) + " is restricted\""
                + " data-locked-body=\"" + escape(body) + "\""
                + " aria-disabled=true>"
                + "<i class=\"nav-icon bi " + icon + "\"></i> <p>"
                + escape(label) + " <i class=\"bi bi-lock-fill ms-1 warden-nav-lockicon\" aria-hidden=true></i></p></a></li>";
    }

    public static String escape(String s) {
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

    /**
     * Bootstrap-tooltip info icon. Returns "" when tip is blank so callers can
     * unconditionally append it.
     */
    public static String infoIcon(String tip) {
        if (tip == null || tip.isBlank()) return "";
        return " <span class=\"info-icon\" data-bs-toggle=tooltip data-bs-placement=top"
                + " data-bs-title=\"" + escape(tip) + "\" tabindex=0 role=img aria-label=info>"
                + "<i class=\"bi bi-info-circle\"></i></span>";
    }

    /** Wires up the dropdown items so each one writes localStorage and re-applies the theme. */
    private static final String THEME_PICKER_SCRIPT =
            "<script>(function(){"
            + "var KEY='warden.theme';"
            + "var icons={system:'bi-circle-half',light:'bi-sun',dark:'bi-moon-stars'};"
            + "var iconEl=document.getElementById('wuc-theme-icon');"
            + "function reflect(mode){"
            + "if(iconEl){iconEl.className='bi '+(icons[mode]||icons.system);}"
            + "document.querySelectorAll('[data-theme-set]').forEach(function(b){"
            + "b.classList.toggle('active',b.getAttribute('data-theme-set')===mode);"
            + "});"
            + "}"
            + "function apply(mode){"
            + "var sys=window.matchMedia&&window.matchMedia('(prefers-color-scheme:dark)').matches;"
            + "var eff=(mode==='dark')||(mode==='system'&&sys)?'dark':'light';"
            + "var html=document.documentElement;"
            + "html.setAttribute('data-bs-theme',eff);"
            + "html.setAttribute('data-theme',eff);"
            + "html.dataset.themeMode=mode;"
            + "reflect(mode);"
            + "}"
            + "var saved=null;try{saved=localStorage.getItem(KEY);}catch(_){}"
            + "reflect((saved==='light'||saved==='dark')?saved:'system');"
            + "document.querySelectorAll('[data-theme-set]').forEach(function(b){"
            + "b.addEventListener('click',function(){"
            + "var m=b.getAttribute('data-theme-set');"
            + "try{localStorage.setItem(KEY,m);}catch(_){}"
            + "apply(m);"
            + "});"
            + "});"
            + "})();</script>";

    private static final String CUSTOM_CSS = """
            /* Sidebar brand chip: align the icon and "Warden" text on the same
               baseline. AdminLTE's default has uneven vertical centering once
               you swap in a rounded icon, so pin everything to flex-center. */
            .app-sidebar .sidebar-brand{display:flex;align-items:center;justify-content:center;
              padding:.6rem .9rem;min-height:3.25rem}
            .app-sidebar .sidebar-brand .brand-link{display:flex;align-items:center;gap:.55rem;
              padding:0;margin:0;line-height:1;width:100%}
            .app-sidebar .sidebar-brand .brand-image{margin:0;flex:0 0 auto;
              max-height:2rem;width:auto;height:auto;display:block}
            .app-sidebar .sidebar-brand .brand-text{margin:0;line-height:1;font-size:1.15rem;
              white-space:nowrap}

            /* Locked sidebar entries - shown but greyed; click pops a modal */
            .nav-link.warden-nav-locked{opacity:.55;cursor:not-allowed}
            .nav-link.warden-nav-locked:hover{opacity:.7;background:rgba(255,255,255,.04)}
            .nav-link.warden-nav-locked .warden-nav-lockicon{font-size:.78em;opacity:.85}

            /* Minecraft account chip - reused on pending/members/audit pages */
            .mc-chip{display:inline-block;font-size:.75em;font-weight:600;letter-spacing:.02em;
              padding:.15em .55em;border-radius:99px;
              background:rgba(40,167,69,.16);color:#1e7a3d;border:1px solid rgba(40,167,69,.35);
              vertical-align:middle;margin-left:.4rem}
            [data-bs-theme=dark] .mc-chip{color:#7fd49a;background:rgba(40,167,69,.22)}

            .info-icon{color:var(--bs-secondary-color);cursor:help;font-size:.85em;margin-left:.25rem}
            .info-icon:hover,.info-icon:focus{color:var(--bs-primary)}

            /* Word-style markdown toolbar above template textareas */
            .tpl-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:.2rem;
              padding:.4rem .5rem;background:var(--bs-tertiary-bg);
              border:1px solid var(--bs-border-color);border-bottom:none;
              border-radius:.375rem .375rem 0 0;margin-top:.35rem}
            .tpl-toolbar + textarea{border-top-left-radius:0;border-top-right-radius:0;margin-top:-1px}
            .tpl-toolbar .tpl-group{display:flex;gap:.15rem;align-items:center;flex-wrap:wrap}
            .tpl-toolbar .tpl-sep{width:1px;height:1.25rem;background:var(--bs-border-color);margin:0 .35rem}
            .tpl-toolbar .tpl-btn{padding:.25em .55em;font-size:.82rem;line-height:1.1;
              background:var(--bs-body-bg);border:1px solid var(--bs-border-color);
              color:inherit;border-radius:.25rem;cursor:pointer;
              font-family:inherit;min-width:1.9rem;text-align:center;
              transition:background .12s,color .12s,border-color .12s}
            .tpl-toolbar .tpl-btn:hover{background:rgba(13,110,253,.12);color:var(--bs-primary);
              border-color:rgba(13,110,253,.25)}
            .tpl-toolbar .tpl-btn.bold{font-weight:700}
            .tpl-toolbar .tpl-btn.italic{font-style:italic}
            .tpl-toolbar .tpl-btn.underline{text-decoration:underline}
            .tpl-toolbar .tpl-btn.strike{text-decoration:line-through}
            .tpl-toolbar .tpl-btn.code,.tpl-toolbar .tpl-btn.chip{
              font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.78rem}
            .tpl-toolbar .tpl-btn.chip{background:rgba(13,110,253,.12);color:var(--bs-primary);border-color:transparent}
            .tpl-toolbar .tpl-btn.chip:hover{background:var(--bs-primary);color:#fff}

            /* Scrollable multi-check list for role pickers */
            .multicheck{max-height:14rem;overflow-y:auto;
              background:var(--bs-tertiary-bg);padding:.5rem .85rem;
              border:1px solid var(--bs-border-color);border-radius:.5rem}
            .multicheck .form-check{margin-bottom:.25rem}

            /* Audit raw payload toggle */
            details.raw-payload summary{cursor:pointer;color:var(--bs-secondary-color);font-size:.85em}
            details.raw-payload code{word-break:break-all;font-size:.82em}

            /* Templates textarea uses monospace */
            textarea.tpl{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88rem}

            /* LLM verdict block on pending cards */
            .llm-verdict{background:rgba(13,110,253,.08);border:1px solid rgba(13,110,253,.18);
              padding:.7rem 1rem;border-radius:.4rem;margin:.5rem 0 1rem;font-size:.92em}
            .llm-verdict code{background:rgba(0,0,0,.06);padding:.1em .4em;border-radius:3px}
            [data-bs-theme=dark] .llm-verdict code{background:rgba(255,255,255,.08)}

            /* Members state badge colors */
            .state-badge{font-size:.78em;padding:.25em .55em;border-radius:.4rem;
              text-transform:none;letter-spacing:0;font-weight:500}

            /* CSS-only tabs (radio + sibling selectors) styled to match Bootstrap nav-tabs.
               Used by /dash/config so that all 8 settings panels can live inside one form. */
            .cfg-tabs{position:relative}
            .cfg-tabs > input.cfg-tabradio{position:absolute;left:-9999px;width:1px;height:1px;opacity:0;pointer-events:none}
            .cfg-tab-bar{display:flex;flex-wrap:wrap;gap:.15rem;
              border-bottom:1px solid var(--bs-border-color);margin:1.25rem 0 1.5rem}
            .cfg-tab-bar label.cfg-tablabel{padding:.65rem 1rem;cursor:pointer;font-weight:500;
              font-size:.92rem;color:var(--bs-secondary-color);
              border:1px solid transparent;border-bottom:2px solid transparent;
              margin-bottom:-1px;border-radius:.375rem .375rem 0 0;
              transition:color .12s,border-color .12s,background .12s;user-select:none}
            .cfg-tab-bar label.cfg-tablabel:hover{color:var(--bs-emphasis-color);
              background:rgba(127,127,127,.06)}
            .cfg-tab-panel{display:none}
            #cfg-roles:checked    ~ .cfg-tab-bar label[for=cfg-roles],
            #cfg-delivery:checked ~ .cfg-tab-bar label[for=cfg-delivery],
            #cfg-entry:checked    ~ .cfg-tab-bar label[for=cfg-entry],
            #cfg-geoip:checked    ~ .cfg-tab-bar label[for=cfg-geoip],
            #cfg-gating:checked   ~ .cfg-tab-bar label[for=cfg-gating],
            #cfg-triage:checked   ~ .cfg-tab-bar label[for=cfg-triage],
            #cfg-ai:checked       ~ .cfg-tab-bar label[for=cfg-ai],
            #cfg-approve:checked  ~ .cfg-tab-bar label[for=cfg-approve],
            #cfg-deny:checked     ~ .cfg-tab-bar label[for=cfg-deny],
            #cfg-rules:checked    ~ .cfg-tab-bar label[for=cfg-rules],
            #cfg-landing:checked  ~ .cfg-tab-bar label[for=cfg-landing],
            #cfg-modules:checked  ~ .cfg-tab-bar label[for=cfg-modules],
            #cfg-questions:checked ~ .cfg-tab-bar label[for=cfg-questions],
            #mod-automod:checked  ~ .cfg-tab-bar label[for=mod-automod],
            #mod-raid:checked     ~ .cfg-tab-bar label[for=mod-raid],
            #mod-warn:checked     ~ .cfg-tab-bar label[for=mod-warn],
            #eng-polls:checked    ~ .cfg-tab-bar label[for=eng-polls],
            #eng-giv:checked      ~ .cfg-tab-bar label[for=eng-giv],
            #eng-rem:checked      ~ .cfg-tab-bar label[for=eng-rem]
            {color:var(--bs-primary);border-bottom-color:var(--bs-primary);background:transparent}
            #cfg-roles:checked    ~ .cfg-tab-panels [data-tab=roles],
            #cfg-delivery:checked ~ .cfg-tab-panels [data-tab=delivery],
            #cfg-entry:checked    ~ .cfg-tab-panels [data-tab=entry],
            #cfg-geoip:checked    ~ .cfg-tab-panels [data-tab=geoip],
            #cfg-gating:checked   ~ .cfg-tab-panels [data-tab=gating],
            #cfg-triage:checked   ~ .cfg-tab-panels [data-tab=triage],
            #cfg-ai:checked       ~ .cfg-tab-panels [data-tab=ai],
            #cfg-approve:checked  ~ .cfg-tab-panels [data-tab=approve],
            #cfg-deny:checked     ~ .cfg-tab-panels [data-tab=deny],
            #cfg-rules:checked    ~ .cfg-tab-panels [data-tab=rules],
            #cfg-landing:checked  ~ .cfg-tab-panels [data-tab=landing],
            #cfg-modules:checked  ~ .cfg-tab-panels [data-tab=modules],
            #cfg-questions:checked ~ .cfg-tab-panels [data-tab=questions],
            #mod-automod:checked  ~ .cfg-tab-panels [data-tab=automod],
            #mod-raid:checked     ~ .cfg-tab-panels [data-tab=raid],
            #mod-warn:checked     ~ .cfg-tab-panels [data-tab=warn],
            #eng-polls:checked    ~ .cfg-tab-panels [data-tab=polls],
            #eng-giv:checked      ~ .cfg-tab-panels [data-tab=giveaways],
            #eng-rem:checked      ~ .cfg-tab-panels [data-tab=reminders]
            {display:block}
            /* Hide the main settings form's Save button on tabs that own their save flow
               (Questions has its own CRUD, Modules has its own form). The `>` selector
               targets only the main form (direct child of .cfg-tab-panels); the Modules
               form lives one level deeper inside its .cfg-tab-panel and is unaffected. */
            #cfg-questions:checked ~ .cfg-tab-panels > form .save-row,
            #cfg-modules:checked   ~ .cfg-tab-panels > form .save-row{display:none}
            .cfg-tabs > input.cfg-tabradio:focus-visible ~ .cfg-tab-bar label{
              outline:2px solid var(--bs-primary);outline-offset:2px;
            }

            /* User chip at the bottom of the sidebar: avatar + name + theme + sign-out.
               Pinned to the foot of the sidebar so it survives sidebar scrolling. */
            .app-sidebar{position:relative;display:flex;flex-direction:column}
            .app-sidebar .sidebar-wrapper{flex:1 1 auto;min-height:0;overflow-y:auto}
            .warden-user-chip{
              display:flex;align-items:center;gap:.6rem;
              padding:.55rem .65rem;margin:.5rem .55rem .6rem;
              background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.08);
              border-radius:.55rem;
            }
            .warden-user-chip .wuc-avatar{
              flex:0 0 auto;width:2.1rem;height:2.1rem;border-radius:50%;
              background:linear-gradient(135deg,#6b83ff,#4a64e6);color:#fff;
              display:flex;align-items:center;justify-content:center;font-size:1.1rem;
              object-fit:cover;overflow:hidden;
            }
            .warden-user-chip .wuc-avatar-img{
              background:#1f2532;
            }
            .warden-user-chip .wuc-meta{flex:1 1 auto;min-width:0;line-height:1.15}
            .warden-user-chip .wuc-name{
              color:#fff;font-weight:600;font-size:.92rem;
              white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
            }
            .warden-user-chip .wuc-sub{
              color:rgba(255,255,255,.55);font-size:.72rem;letter-spacing:.02em;
            }
            .warden-user-chip .wuc-actions{display:flex;align-items:center;gap:.2rem;flex:0 0 auto}
            .warden-user-chip .wuc-logout{margin:0}
            .warden-user-chip .wuc-btn{
              background:transparent;border:none;color:rgba(255,255,255,.7);
              padding:.3rem .45rem;border-radius:.35rem;font-size:1rem;line-height:1;cursor:pointer;
              transition:background .12s,color .12s;
            }
            .warden-user-chip .wuc-btn:hover{background:rgba(255,255,255,.10);color:#fff}
            .warden-user-chip .dropdown-item.active{
              background-color:rgba(13,110,253,.18);color:var(--bs-primary)}

            /* Toast host: pinned to top-right of the viewport, doesn't intercept clicks
               outside its own toasts. Stacked toasts gap with margin. */
            .warden-toast-host{position:fixed;top:1rem;right:1rem;z-index:1080;
              display:flex;flex-direction:column;gap:.5rem;
              max-width:min(26rem,calc(100vw - 2rem));pointer-events:none}
            .warden-toast{pointer-events:auto;display:flex;align-items:flex-start;gap:.6rem;
              padding:.7rem .85rem;border-radius:.55rem;border:1px solid var(--bs-border-color);
              background:var(--bs-body-bg);
              box-shadow:0 6px 24px rgba(0,0,0,.18),0 2px 6px rgba(0,0,0,.10);
              opacity:0;transform:translateY(-.4rem) scale(.98);
              transition:opacity .18s ease,transform .18s ease;
              min-width:16rem;font-size:.92rem}
            .warden-toast.wt-show{opacity:1;transform:translateY(0) scale(1)}
            .warden-toast.wt-leave{opacity:0;transform:translateY(-.3rem) scale(.985)}
            .warden-toast .wt-icon{font-size:1.15rem;line-height:1.2;flex:0 0 auto;margin-top:.05rem}
            .warden-toast .wt-body{flex:1 1 auto;min-width:0;line-height:1.35}
            .warden-toast .wt-title{font-weight:600;margin-bottom:.1rem}
            .warden-toast .wt-msg{color:var(--bs-emphasis-color);white-space:pre-wrap;word-break:break-word}
            .warden-toast .wt-close{background:transparent;border:0;color:var(--bs-secondary-color);
              padding:.1rem .25rem;line-height:1;cursor:pointer;font-size:1rem;flex:0 0 auto;border-radius:.25rem}
            .warden-toast .wt-close:hover{background:rgba(127,127,127,.12);color:var(--bs-emphasis-color)}
            .warden-toast.wt-success{border-left:3px solid var(--bs-success)}
            .warden-toast.wt-success .wt-icon{color:var(--bs-success)}
            .warden-toast.wt-info{border-left:3px solid var(--bs-primary)}
            .warden-toast.wt-info .wt-icon{color:var(--bs-primary)}
            .warden-toast.wt-warn{border-left:3px solid var(--bs-warning)}
            .warden-toast.wt-warn .wt-icon{color:var(--bs-warning)}
            .warden-toast.wt-error{border-left:3px solid var(--bs-danger)}
            .warden-toast.wt-error .wt-icon{color:var(--bs-danger)}
            """;
}
