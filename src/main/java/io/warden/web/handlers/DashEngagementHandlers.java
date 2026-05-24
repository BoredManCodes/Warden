package io.warden.web.handlers;

import io.javalin.http.Context;
import io.warden.discord.DiscordService;
import io.warden.engagement.EngagementService;
import io.warden.engagement.Giveaway;
import io.warden.engagement.Poll;
import io.warden.engagement.ReminderDao;
import io.warden.moderation.DurationParser;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.SessionCookie;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DashEngagementHandlers {

    private final EngagementService engagement;
    private final DiscordService discord;
    private final GuildLookup lookup;

    public DashEngagementHandlers(EngagementService engagement, DiscordService discord, GuildLookup lookup) {
        this.engagement = engagement;
        this.discord = discord;
        this.lookup = lookup;
    }

    public void page(Context ctx) throws Exception {
        String tab = ctx.queryParam("tab");
        if (tab == null || tab.isBlank()) tab = "polls";

        var openPolls = engagement.polls().listOpen();
        var openGiveaways = engagement.giveaways().listOpen();
        var pendingReminders = engagement.reminders().listAllPending();

        var roleOpts = lookup.roles();
        var channelOpts = lookup.textChannels();
        var emojiOpts = lookup.customEmojis();

        String flash = ctx.queryParam("msg");
        String err = ctx.queryParam("err");

        PollDraft pollDraft = new PollDraft(
                ctx.queryParam("d_channel"),
                ctx.queryParam("d_question"),
                ctx.queryParam("d_options"),
                ctx.queryParam("d_duration"),
                "1".equals(ctx.queryParam("d_anon")),
                "1".equals(ctx.queryParam("d_multi")));
        GiveawayDraft givDraft = new GiveawayDraft(
                ctx.queryParam("g_channel"),
                ctx.queryParam("g_winners"),
                ctx.queryParam("g_duration"),
                ctx.queryParam("g_prize"),
                ctx.queryParam("g_desc"),
                ctx.queryParam("g_role"));

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Polls and giveaways · Warden", "engagement", ctx));
        h.append("<h1 class=\"h3 mb-3\">Polls, giveaways and reminders</h1>");
        h.append("<p class=\"text-secondary\">Create polls and giveaways below, or use the Discord slash commands ")
                .append("<code>/poll</code>, <code>/giveaway</code>, <code>/remind</code>. ")
                .append("This page lists what's currently active across the server.</p>");

        if (!lookup.discordConnected()) {
            h.append("<div class=\"alert alert-warning\" role=alert>Discord isn't connected; channel and role dropdowns are empty. ")
                    .append("Set the Discord bot token and guild id in <code>plugins/Warden/config.yml</code> and restart.</div>");
        }
        if (flash != null && !flash.isBlank()) {
            h.append("<div class=\"alert alert-success\" role=alert>").append(Layout.escape(flash)).append("</div>");
        }
        if (err != null && !err.isBlank()) {
            h.append("<div class=\"alert alert-danger\" role=alert>").append(Layout.escape(err)).append("</div>");
        }

        h.append("<div class=\"cfg-tabs\">");
        h.append("<input class=cfg-tabradio name=eng_tab type=radio id=eng-polls ").append(tab.equals("polls") ? "checked" : "").append(">");
        h.append("<input class=cfg-tabradio name=eng_tab type=radio id=eng-giv ").append(tab.equals("giveaways") ? "checked" : "").append(">");
        h.append("<input class=cfg-tabradio name=eng_tab type=radio id=eng-rem ").append(tab.equals("reminders") ? "checked" : "").append(">");
        h.append("<div class=cfg-tab-bar>");
        h.append("<label for=eng-polls class=cfg-tablabel>Polls (").append(openPolls.size()).append(")</label>");
        h.append("<label for=eng-giv class=cfg-tablabel>Giveaways (").append(openGiveaways.size()).append(")</label>");
        h.append("<label for=eng-rem class=cfg-tablabel>Reminders (").append(pendingReminders.size()).append(")</label>");
        h.append("</div><div class=cfg-tab-panels>");

        // ---- Polls panel ----
        h.append("<div class=cfg-tab-panel data-tab=polls>");
        h.append(renderPollForm(channelOpts, emojiOpts, pollDraft));
        h.append("<h2 class=\"h5 mt-4 mb-2\">Open polls</h2>");
        h.append("<table class=\"table table-sm align-middle\"><thead><tr><th>#</th><th>Question</th><th>Options</th><th>Ends</th><th>Status</th><th></th></tr></thead><tbody>");
        if (openPolls.isEmpty()) {
            h.append("<tr><td colspan=6 class=\"text-secondary text-center py-3\">No open polls.</td></tr>");
        }
        for (Poll p : openPolls) {
            h.append("<tr><td>").append(p.id()).append("</td>")
                    .append("<td>").append(Layout.escape(p.question())).append("</td>")
                    .append("<td>").append(p.options().size()).append("</td>")
                    .append("<td>").append(p.endsAt() == null ? "no end" : relativeTimeTag(p.endsAt())).append("</td>")
                    .append("<td>").append(p.open(System.currentTimeMillis()) ? "open" : "closed").append("</td>")
                    .append("<td><form method=post action=\"/dash/engagement/polls/").append(p.id()).append("/close\" class=m-0>")
                    .append("<button class=\"btn btn-sm btn-outline-secondary\" data-bs-toggle=tooltip data-bs-title=\"Close the poll now and post final results in Discord\">Close now</button></form></td></tr>");
        }
        h.append("</tbody></table></div>");

        // ---- Giveaways panel ----
        h.append("<div class=cfg-tab-panel data-tab=giveaways>");
        h.append(renderGiveawayForm(channelOpts, roleOpts, givDraft));
        h.append("<h2 class=\"h5 mt-4 mb-2\">Open giveaways</h2>");
        h.append("<table class=\"table table-sm align-middle\"><thead><tr><th>#</th><th>Prize</th><th>Winners</th><th>Ends</th><th></th></tr></thead><tbody>");
        if (openGiveaways.isEmpty()) {
            h.append("<tr><td colspan=5 class=\"text-secondary text-center py-3\">No open giveaways.</td></tr>");
        }
        for (Giveaway g : openGiveaways) {
            h.append("<tr><td>").append(g.id()).append("</td>")
                    .append("<td>").append(Layout.escape(g.prize())).append("</td>")
                    .append("<td>").append(g.winners()).append("</td>")
                    .append("<td>").append(relativeTimeTag(g.endsAt())).append("</td>")
                    .append("<td><form method=post action=\"/dash/engagement/giveaways/").append(g.id())
                    .append("/draw\" class=\"d-inline\"><button class=\"btn btn-sm btn-outline-primary\" data-bs-toggle=tooltip data-bs-title=\"Pick winners immediately\">Draw now</button></form> ")
                    .append("<form method=post action=\"/dash/engagement/giveaways/").append(g.id())
                    .append("/cancel\" class=\"d-inline ms-1\"><button class=\"btn btn-sm btn-outline-danger\" data-bs-toggle=tooltip data-bs-title=\"Cancel without drawing\">Cancel</button></form></td></tr>");
        }
        h.append("</tbody></table></div>");

        // ---- Reminders panel ----
        h.append("<div class=cfg-tab-panel data-tab=reminders>");
        h.append("<p class=\"text-secondary\">Reminders are user-scoped; create one with the Discord <code>/remind</code> command.</p>");
        h.append("<table class=\"table table-sm align-middle\"><thead><tr><th>#</th><th>User</th><th>Message</th><th>Fires</th></tr></thead><tbody>");
        if (pendingReminders.isEmpty()) {
            h.append("<tr><td colspan=4 class=\"text-secondary text-center py-3\">No pending reminders.</td></tr>");
        }
        for (ReminderDao.Reminder r : pendingReminders) {
            String userLabel = lookup.userName(r.discordId()).orElseGet(r::discordId);
            h.append("<tr><td>").append(r.id()).append("</td>")
                    .append("<td>").append(Layout.escape(userLabel)).append("</td>")
                    .append("<td>").append(Layout.escape(r.message())).append("</td>")
                    .append("<td>").append(relativeTimeTag(r.firesAt())).append("</td></tr>");
        }
        h.append("</tbody></table></div>");

        h.append("</div></div>");
        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private String renderPollForm(List<GuildLookup.Option> channelOpts,
                                  List<GuildLookup.EmojiOption> emojiOpts,
                                  PollDraft draft) {
        boolean hasEmojis = emojiOpts != null && !emojiOpts.isEmpty();
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        s.append("<h2 class=\"h5 mb-3\">Create a poll</h2>");
        s.append("<form method=post action=\"/dash/engagement/polls/new\" class=\"row g-3\">");

        s.append("<div class=\"col-md-6\">")
                .append("<label class=form-label for=p_channel>Channel")
                .append(Layout.infoIcon("Discord text channel that will host the poll message and buttons."))
                .append("</label>")
                .append(GuildLookup.selectInline("channel_id", channelOpts, draft.channelId(), "form-select", "(select channel)").replace("<select ", "<select id=p_channel required "))
                .append("</div>");

        s.append("<div class=\"col-md-6\">")
                .append("<label class=form-label for=p_duration>Duration")
                .append(Layout.infoIcon("How long voting stays open. Examples: 30s, 10m, 1h, 7d. Leave blank for no auto-close."))
                .append("</label>")
                .append("<input id=p_duration name=duration class=form-control placeholder=\"e.g. 1h or leave blank\" value=\"")
                .append(Layout.escape(draft.duration())).append("\">")
                .append("</div>");

        s.append("<div class=\"col-12\">")
                .append("<label class=form-label for=p_question>Question")
                .append(Layout.infoIcon("The question shown at the top of the poll embed."))
                .append("</label>")
                .append("<div class=\"input-group\">")
                .append("<input id=p_question name=question class=form-control required maxlength=200 placeholder=\"What should we vote on?\" value=\"")
                .append(Layout.escape(draft.question())).append("\">")
                .append(pollEmojiTriggerButton(hasEmojis, "btn-outline-secondary"))
                .append("</div>")
                .append("</div>");

        // Options widget: list with drag-to-reorder, edit-in-place, and delete.
        // The actual form submission value is the hidden #p_options textarea,
        // synced to the visible list by the script below. Keeps the server-side
        // contract (newline-separated options) unchanged.
        List<String> initialOpts = draft.optionsList();
        s.append("<div class=\"col-12\">")
                .append("<label class=form-label>Options")
                .append(Layout.infoIcon("2 to 10 options. Drag the handle to reorder, click the X to remove."))
                .append("</label>")
                .append("<div id=p_options_list class=poll-opts data-min=2 data-max=10>");
        if (initialOpts.isEmpty()) {
            s.append(pollOptionRow("", "Option one", hasEmojis))
                    .append(pollOptionRow("", "Option two", hasEmojis));
        } else {
            for (String opt : initialOpts) s.append(pollOptionRow(opt, "Option text", hasEmojis));
        }
        s.append("</div>")
                .append("<div class=\"d-flex align-items-center gap-2 mt-2\">")
                .append("<button type=button id=p_options_add class=\"btn btn-sm btn-outline-primary\">")
                .append("<i class=\"bi bi-plus-lg me-1\"></i>Add option</button>")
                .append("<span id=p_options_count class=\"text-secondary small\"></span>")
                .append("</div>")
                .append("<textarea id=p_options name=options required hidden tabindex=-1 aria-hidden=true></textarea>")
                .append("</div>");

        s.append("<div class=\"col-md-6\">")
                .append("<div class=\"form-check\">")
                .append("<input class=form-check-input type=checkbox id=p_anon name=anonymous value=1")
                .append(draft.anonymous() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=p_anon>Anonymous voting")
                .append(Layout.infoIcon("Hides individual voter names; only totals are shown."))
                .append("</label></div></div>");

        s.append("<div class=\"col-md-6\">")
                .append("<div class=\"form-check\">")
                .append("<input class=form-check-input type=checkbox id=p_multi name=multi_choice value=1")
                .append(draft.multi() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=p_multi>Multi-choice")
                .append(Layout.infoIcon("Lets each voter pick more than one option (and toggle them off again)."))
                .append("</label></div></div>");

        s.append("<div class=\"col-12\">")
                .append("<button class=\"btn btn-primary\" type=submit>Create poll</button>")
                .append("</div>");

        s.append("</form></div></div>");
        s.append(POLL_OPTIONS_STYLE);
        s.append(pollOptionsScript(hasEmojis));
        s.append(pollEmojiPickerPopover(emojiOpts));
        return s.toString();
    }

    private static String pollOptionRow(String value, String placeholder, boolean hasEmojis) {
        return "<div class=poll-opt draggable=true>"
                + "<span class=poll-opt-grip aria-hidden=true title=\"Drag to reorder\"><i class=\"bi bi-grip-vertical\"></i></span>"
                + "<input class=\"form-control poll-opt-input\" type=text maxlength=80 placeholder=\""
                + Layout.escape(placeholder) + "\" value=\""
                + Layout.escape(value == null ? "" : value) + "\">"
                + pollEmojiTriggerButton(hasEmojis, "btn-sm btn-outline-secondary poll-opt-emoji")
                + "<button type=button class=\"btn btn-sm btn-outline-danger poll-opt-del\" title=\"Remove option\" aria-label=\"Remove option\">"
                + "<i class=\"bi bi-x-lg\"></i></button>"
                + "</div>";
    }

    /** Emoji-trigger button that opens the shared picker popover. Hidden when no server emojis are available. */
    private static String pollEmojiTriggerButton(boolean enabled, String btnClass) {
        if (!enabled) return "";
        return "<button type=button class=\"btn " + btnClass + " poll-emoji-trigger\""
                + " title=\"Insert a server emoji\" aria-label=\"Insert a server emoji\">"
                + "<i class=\"bi bi-emoji-smile\"></i></button>";
    }

    /** Shared popover + CSS/JS used by every {@code .poll-emoji-trigger} on the page. Empty when no custom emojis exist. */
    private static String pollEmojiPickerPopover(List<GuildLookup.EmojiOption> emojiOpts) {
        if (emojiOpts == null || emojiOpts.isEmpty()) return "";
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"poll-emoji-popover\" id=\"poll-emoji-popover\" hidden>");
        s.append("<div class=\"poll-emoji-head\">")
                .append("<input type=\"search\" class=\"form-control form-control-sm poll-emoji-filter\" placeholder=\"Search server emojis...\" autocomplete=\"off\">")
                .append("</div>");
        s.append("<div class=\"poll-emoji-grid\">");
        for (GuildLookup.EmojiOption e : emojiOpts) {
            s.append("<button type=button class=\"poll-emoji-btn\" data-mention=\"")
                    .append(Layout.escape(e.mention())).append("\" data-name=\"")
                    .append(Layout.escape(e.name())).append("\" title=\":")
                    .append(Layout.escape(e.name())).append(":\">")
                    .append("<img loading=lazy src=\"").append(Layout.escape(e.imageUrl()))
                    .append("\" alt=\":").append(Layout.escape(e.name())).append(":\"></button>");
        }
        s.append("</div>");
        s.append("<div class=\"poll-emoji-foot text-secondary\">")
                .append("Tip: paste a unicode emoji like ❤️ straight into the field.")
                .append("</div>");
        s.append("</div>");
        s.append(POLL_EMOJI_PICKER_STYLE);
        s.append(POLL_EMOJI_PICKER_SCRIPT);
        return s.toString();
    }

    private static final String POLL_OPTIONS_STYLE =
            "<style>"
            + ".poll-opts{display:flex;flex-direction:column;gap:.4rem}"
            + ".poll-opt{display:flex;align-items:center;gap:.5rem;"
            + "background:var(--bs-tertiary-bg);border:1px solid var(--bs-border-color);"
            + "border-radius:.4rem;padding:.35rem .45rem;transition:background .12s,border-color .12s,opacity .12s,transform .12s}"
            + ".poll-opt:hover{border-color:rgba(13,110,253,.35)}"
            + ".poll-opt.dragging{opacity:.4}"
            + ".poll-opt.drop-target{border-color:var(--bs-primary);background:rgba(13,110,253,.08)}"
            + ".poll-opt-grip{cursor:grab;color:var(--bs-secondary-color);padding:.2rem .15rem;"
            + "user-select:none;display:flex;align-items:center;font-size:1.1rem;line-height:1}"
            + ".poll-opt-grip:active{cursor:grabbing}"
            + ".poll-opt-input{flex:1 1 auto}"
            + ".poll-opt-emoji{flex:0 0 auto;padding:.2rem .5rem;line-height:1}"
            + ".poll-opt-del{flex:0 0 auto;padding:.2rem .5rem;line-height:1}"
            + "</style>";

    private static final String POLL_EMOJI_PICKER_STYLE =
            "<style>"
            + ".poll-emoji-popover{position:fixed;z-index:1080;width:min(20rem,calc(100vw - 2rem));"
            + "background:var(--bs-body-bg);border:1px solid var(--bs-border-color);"
            + "border-radius:.5rem;box-shadow:0 8px 28px rgba(0,0,0,.18),0 2px 6px rgba(0,0,0,.10);"
            + "padding:.5rem;display:flex;flex-direction:column;gap:.4rem}"
            + ".poll-emoji-popover[hidden]{display:none}"
            + ".poll-emoji-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(2rem,1fr));"
            + "gap:.2rem;max-height:14rem;overflow-y:auto;padding:.1rem}"
            + ".poll-emoji-btn{display:flex;align-items:center;justify-content:center;"
            + "width:2rem;height:2rem;padding:0;border:1px solid transparent;border-radius:.35rem;"
            + "background:transparent;cursor:pointer;transition:background .12s,border-color .12s}"
            + ".poll-emoji-btn:hover{background:rgba(127,127,127,.12);border-color:var(--bs-border-color)}"
            + ".poll-emoji-btn img{max-width:1.5rem;max-height:1.5rem;object-fit:contain;display:block}"
            + ".poll-emoji-foot{font-size:.78rem;line-height:1.2}"
            + ".poll-emoji-grid .poll-emoji-btn.is-hidden{display:none}"
            + "</style>";

    /**
     * Shared popover used by every {@code .poll-emoji-trigger} on the polls panel.
     * Uses event delegation so dynamically-added option rows pick up the trigger
     * without needing per-row wiring. The trigger's adjacent input is located via
     * a sibling lookup, which works for both the question's input-group wrapper
     * and the {@code .poll-opt} row.
     */
    private static final String POLL_EMOJI_PICKER_SCRIPT =
            "<script>(function(){"
            + "var pop=document.getElementById('poll-emoji-popover');"
            + "if(!pop)return;"
            + "var filter=pop.querySelector('.poll-emoji-filter');"
            + "var activeInput=null;"
            + "function close(){pop.hidden=true;activeInput=null;}"
            + "function findInputFor(btn){"
            + "var parent=btn.parentElement;"
            + "if(!parent)return null;"
            + "return parent.querySelector('input.form-control')||null;"
            + "}"
            + "function position(btn){"
            + "var r=btn.getBoundingClientRect();"
            + "var pw=pop.offsetWidth||320;"
            + "var left=Math.min(r.right-pw,window.innerWidth-pw-8);"
            + "if(left<8)left=8;"
            + "pop.style.left=left+'px';"
            + "pop.style.top=(r.bottom+6)+'px';"
            + "}"
            + "function openAt(btn,input){"
            + "activeInput=input;pop.hidden=false;position(btn);"
            + "if(filter){try{filter.value='';filter.focus();}catch(_){}"
            + "pop.querySelectorAll('.poll-emoji-btn').forEach(function(b){b.classList.remove('is-hidden');});}"
            + "}"
            + "document.addEventListener('click',function(e){"
            + "var trigger=e.target.closest('.poll-emoji-trigger');"
            + "if(trigger){"
            + "e.preventDefault();e.stopPropagation();"
            + "var input=findInputFor(trigger);"
            + "if(!input)return;"
            + "if(!pop.hidden&&activeInput===input){close();return;}"
            + "openAt(trigger,input);return;"
            + "}"
            + "if(pop.contains(e.target))return;"
            + "close();"
            + "});"
            + "pop.addEventListener('click',function(e){"
            + "var b=e.target.closest('.poll-emoji-btn');"
            + "if(!b||!activeInput)return;"
            + "var mention=b.getAttribute('data-mention')||'';"
            + "var i=activeInput;var s=i.selectionStart;var en=i.selectionEnd;var v=i.value||'';"
            + "if(typeof s==='number'&&typeof en==='number'){"
            + "i.value=v.slice(0,s)+mention+v.slice(en);"
            + "var pos=s+mention.length;try{i.setSelectionRange(pos,pos);}catch(_){}"
            + "}else{i.value=v+mention;}"
            + "close();"
            + "try{i.focus();}catch(_){};"
            + "i.dispatchEvent(new Event('input',{bubbles:true}));"
            + "});"
            + "if(filter)filter.addEventListener('input',function(){"
            + "var q=filter.value.toLowerCase().trim();"
            + "pop.querySelectorAll('.poll-emoji-btn').forEach(function(b){"
            + "var n=(b.getAttribute('data-name')||'').toLowerCase();"
            + "b.classList.toggle('is-hidden',q!==''&&n.indexOf(q)===-1);"
            + "});});"
            + "document.addEventListener('keydown',function(e){if(e.key==='Escape')close();});"
            + "window.addEventListener('resize',close);"
            + "window.addEventListener('scroll',close,true);"
            + "})();</script>";

    private static String pollOptionsScript(boolean hasEmojis) {
        String emojiBtnFragment = hasEmojis
                ? "+'<button type=button class=\"btn btn-sm btn-outline-secondary poll-opt-emoji poll-emoji-trigger\" title=\"Insert a server emoji\" aria-label=\"Insert a server emoji\"><i class=\"bi bi-emoji-smile\"></i></button>'"
                : "";
        return "<script>(function(){"
                + "var list=document.getElementById('p_options_list');"
                + "if(!list)return;"
                + "var hidden=document.getElementById('p_options');"
                + "var addBtn=document.getElementById('p_options_add');"
                + "var countEl=document.getElementById('p_options_count');"
                + "var MIN=parseInt(list.dataset.min||'2',10),MAX=parseInt(list.dataset.max||'10',10);"
                + "function rows(){return Array.prototype.slice.call(list.querySelectorAll('.poll-opt'));}"
                + "function sync(){"
                + "var vals=rows().map(function(r){var i=r.querySelector('.poll-opt-input');return i?i.value:'';})"
                + ".filter(function(v){return v.trim().length>0;});"
                + "hidden.value=vals.join('\\n');"
                + "var n=rows().length;"
                + "addBtn.disabled=(n>=MAX);"
                + "rows().forEach(function(r){var d=r.querySelector('.poll-opt-del');if(d)d.disabled=(n<=MIN);});"
                + "if(countEl)countEl.textContent=n+'/'+MAX+' option'+(n===1?'':'s');"
                + "}"
                + "function wire(row){"
                + "var del=row.querySelector('.poll-opt-del');"
                + "if(del)del.addEventListener('click',function(){if(rows().length<=MIN)return;row.remove();sync();});"
                + "var input=row.querySelector('.poll-opt-input');"
                + "if(input)input.addEventListener('input',sync);"
                + "row.addEventListener('dragstart',function(e){row.classList.add('dragging');try{e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain','');}catch(_){};});"
                + "row.addEventListener('dragend',function(){row.classList.remove('dragging');rows().forEach(function(r){r.classList.remove('drop-target');});sync();});"
                + "row.addEventListener('dragover',function(e){e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect='move';"
                + "var dragging=list.querySelector('.poll-opt.dragging');if(!dragging||dragging===row)return;"
                + "var rect=row.getBoundingClientRect();var before=(e.clientY-rect.top)<(rect.height/2);"
                + "rows().forEach(function(r){r.classList.remove('drop-target');});row.classList.add('drop-target');"
                + "if(before)list.insertBefore(dragging,row);else list.insertBefore(dragging,row.nextSibling);});"
                + "row.addEventListener('dragleave',function(){row.classList.remove('drop-target');});"
                + "}"
                + "rows().forEach(wire);"
                + "addBtn.addEventListener('click',function(){"
                + "if(rows().length>=MAX)return;"
                + "var tpl=document.createElement('div');"
                + "tpl.innerHTML='<div class=poll-opt draggable=true>'"
                + "+'<span class=poll-opt-grip aria-hidden=true title=\"Drag to reorder\"><i class=\"bi bi-grip-vertical\"></i></span>'"
                + "+'<input class=\"form-control poll-opt-input\" type=text maxlength=80 placeholder=\"Option text\">'"
                + emojiBtnFragment
                + "+'<button type=button class=\"btn btn-sm btn-outline-danger poll-opt-del\" title=\"Remove option\" aria-label=\"Remove option\"><i class=\"bi bi-x-lg\"></i></button>'"
                + "+'</div>';"
                + "var row=tpl.firstChild;list.appendChild(row);wire(row);sync();"
                + "var inp=row.querySelector('.poll-opt-input');if(inp)inp.focus();"
                + "});"
                + "var form=list.closest('form');"
                + "if(form)form.addEventListener('submit',function(){sync();});"
                + "sync();"
                + "})();</script>";
    }

    /** Snapshot of poll-form fields used to re-populate the form after a validation error. */
    private record PollDraft(String channelId, String question, String options,
                             String duration, boolean anonymous, boolean multi) {
        PollDraft(String channelId, String question, String options,
                  String duration, boolean anonymous, boolean multi) {
            this.channelId = nz(channelId);
            this.question = nz(question);
            this.options = nz(options);
            this.duration = nz(duration);
            this.anonymous = anonymous;
            this.multi = multi;
        }
        List<String> optionsList() {
            if (options.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            for (String line : options.split("\\r?\\n")) {
                if (!line.isBlank()) out.add(line);
            }
            return out;
        }
        private static String nz(String v) { return v == null ? "" : v; }
    }

    private String renderGiveawayForm(List<GuildLookup.Option> channelOpts, List<GuildLookup.Option> roleOpts, GiveawayDraft draft) {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        s.append("<h2 class=\"h5 mb-3\">Create a giveaway</h2>");
        s.append("<form method=post action=\"/dash/engagement/giveaways/new\" class=\"row g-3\">");

        s.append("<div class=\"col-md-6\">")
                .append("<label class=form-label for=g_channel>Channel")
                .append(Layout.infoIcon("Discord text channel that will host the giveaway embed and Enter/Leave buttons."))
                .append("</label>")
                .append(GuildLookup.selectInline("channel_id", channelOpts, draft.channelId(), "form-select", "(select channel)").replace("<select ", "<select id=g_channel required "))
                .append("</div>");

        s.append("<div class=\"col-md-3\">")
                .append("<label class=form-label for=g_winners>Winners")
                .append(Layout.infoIcon("Number of winners drawn when the giveaway ends."))
                .append("</label>")
                .append("<input id=g_winners name=winners class=form-control type=number min=1 max=50 value=\"")
                .append(Layout.escape(draft.winnersOr("1"))).append("\" required>")
                .append("</div>");

        s.append("<div class=\"col-md-3\">")
                .append("<label class=form-label for=g_duration>Duration")
                .append(Layout.infoIcon("How long the giveaway runs before it draws. Examples: 1h, 1d, 7d."))
                .append("</label>")
                .append("<input id=g_duration name=duration class=form-control required placeholder=\"e.g. 1d\" value=\"")
                .append(Layout.escape(draft.duration())).append("\">")
                .append("</div>");

        s.append("<div class=\"col-12\">")
                .append("<label class=form-label for=g_prize>Prize")
                .append(Layout.infoIcon("Short prize name shown in the embed title."))
                .append("</label>")
                .append("<input id=g_prize name=prize class=form-control required maxlength=200 placeholder=\"What's the prize?\" value=\"")
                .append(Layout.escape(draft.prize())).append("\">")
                .append("</div>");

        s.append("<div class=\"col-12\">")
                .append("<label class=form-label for=g_desc>Description (optional)")
                .append(Layout.infoIcon("Extra context displayed under the prize."))
                .append("</label>")
                .append("<textarea id=g_desc name=description class=form-control rows=2 maxlength=1000>")
                .append(Layout.escape(draft.description())).append("</textarea>")
                .append("</div>");

        s.append("<div class=\"col-12\">")
                .append("<label class=form-label for=g_role>Required role (optional)")
                .append(Layout.infoIcon("If set, only members with this role can enter. Leave unset to allow anyone."))
                .append("</label>")
                .append(GuildLookup.selectInline("required_role", roleOpts, draft.requiredRole(), "form-select", "(no requirement)").replace("<select ", "<select id=g_role "))
                .append("</div>");

        s.append("<div class=\"col-12\">")
                .append("<button class=\"btn btn-primary\" type=submit>Create giveaway</button>")
                .append("</div>");

        s.append("</form></div></div>");
        return s.toString();
    }

    /** Snapshot of giveaway-form fields used to re-populate the form after a validation error. */
    private record GiveawayDraft(String channelId, String winners, String duration,
                                  String prize, String description, String requiredRole) {
        GiveawayDraft(String channelId, String winners, String duration,
                       String prize, String description, String requiredRole) {
            this.channelId = nz(channelId);
            this.winners = nz(winners);
            this.duration = nz(duration);
            this.prize = nz(prize);
            this.description = nz(description);
            this.requiredRole = nz(requiredRole);
        }
        String winnersOr(String fallback) { return winners.isEmpty() ? fallback : winners; }
        private static String nz(String v) { return v == null ? "" : v; }
    }

    public void createPoll(Context ctx) throws Exception {
        String channelId = trim(ctx.formParam("channel_id"));
        String question = trim(ctx.formParam("question"));
        String optionsRaw = ctx.formParam("options");
        String durationRaw = trim(ctx.formParam("duration"));
        boolean anonymous = "1".equals(ctx.formParam("anonymous"));
        boolean multi = "1".equals(ctx.formParam("multi_choice"));

        if (discord == null || discord.jda() == null) {
            ctx.redirect(pollErrorRedirect("Discord not connected.",
                    channelId, question, optionsRaw, durationRaw, anonymous, multi));
            return;
        }
        if (channelId.isEmpty() || question.isEmpty() || optionsRaw == null || optionsRaw.isBlank()) {
            ctx.redirect(pollErrorRedirect("Channel, question and options are required.",
                    channelId, question, optionsRaw, durationRaw, anonymous, multi));
            return;
        }
        List<String> options = new ArrayList<>();
        for (String line : optionsRaw.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) options.add(t);
        }
        if (options.size() < 2 || options.size() > 10) {
            ctx.redirect(pollErrorRedirect("Need between 2 and 10 options.",
                    channelId, question, optionsRaw, durationRaw, anonymous, multi));
            return;
        }
        Long endsAt = null;
        if (!durationRaw.isEmpty()) {
            int secs = DurationParser.parse(durationRaw);
            if (secs <= 0) {
                ctx.redirect(pollErrorRedirect("Bad duration. Use 30s, 10m, 1h, 7d.",
                        channelId, question, optionsRaw, durationRaw, anonymous, multi));
                return;
            }
            endsAt = System.currentTimeMillis() + secs * 1000L;
        }
        String creatorId = creatorOf(ctx);
        long id = engagement.createPoll(discord.jda(), channelId, creatorId, question, options, anonymous, multi, endsAt);
        if (id < 0) {
            ctx.redirect(pollErrorRedirect("Failed to create poll. Check the bot can post in that channel.",
                    channelId, question, optionsRaw, durationRaw, anonymous, multi));
            return;
        }
        ctx.redirect("/dash/engagement?tab=polls&msg=" + urlEnc("Poll #" + id + " created."));
    }

    private static String pollErrorRedirect(String err, String channelId, String question,
                                            String optionsRaw, String durationRaw,
                                            boolean anonymous, boolean multi) {
        StringBuilder u = new StringBuilder("/dash/engagement?tab=polls&err=").append(urlEnc(err));
        if (channelId != null && !channelId.isEmpty()) u.append("&d_channel=").append(urlEnc(channelId));
        if (question != null && !question.isEmpty()) u.append("&d_question=").append(urlEnc(question));
        if (optionsRaw != null && !optionsRaw.isEmpty()) u.append("&d_options=").append(urlEnc(optionsRaw));
        if (durationRaw != null && !durationRaw.isEmpty()) u.append("&d_duration=").append(urlEnc(durationRaw));
        if (anonymous) u.append("&d_anon=1");
        if (multi) u.append("&d_multi=1");
        return u.toString();
    }

    public void createGiveaway(Context ctx) throws Exception {
        String channelId = trim(ctx.formParam("channel_id"));
        String prize = trim(ctx.formParam("prize"));
        String description = trim(ctx.formParam("description"));
        String requiredRole = trim(ctx.formParam("required_role"));
        String durationRaw = trim(ctx.formParam("duration"));
        String winnersRaw = trim(ctx.formParam("winners"));
        int winners = 1;
        try { winners = Math.max(1, Integer.parseInt(winnersRaw)); }
        catch (NumberFormatException ignored) {}

        if (discord == null || discord.jda() == null) {
            ctx.redirect(giveawayErrorRedirect("Discord not connected.",
                    channelId, winnersRaw, durationRaw, prize, description, requiredRole));
            return;
        }
        if (channelId.isEmpty() || prize.isEmpty() || durationRaw.isEmpty()) {
            ctx.redirect(giveawayErrorRedirect("Channel, prize and duration are required.",
                    channelId, winnersRaw, durationRaw, prize, description, requiredRole));
            return;
        }
        int secs = DurationParser.parse(durationRaw);
        if (secs <= 0) {
            ctx.redirect(giveawayErrorRedirect("Bad duration. Use 1h, 1d, 7d.",
                    channelId, winnersRaw, durationRaw, prize, description, requiredRole));
            return;
        }
        long endsAt = System.currentTimeMillis() + secs * 1000L;
        String creatorId = creatorOf(ctx);
        long id = engagement.createGiveaway(discord.jda(), channelId, creatorId, prize, description,
                winners, requiredRole, endsAt);
        if (id < 0) {
            ctx.redirect(giveawayErrorRedirect("Failed to create giveaway. Check the bot can post in that channel.",
                    channelId, winnersRaw, durationRaw, prize, description, requiredRole));
            return;
        }
        ctx.redirect("/dash/engagement?tab=giveaways&msg=" + urlEnc("Giveaway #" + id + " started."));
    }

    private static String giveawayErrorRedirect(String err, String channelId, String winnersRaw,
                                                String durationRaw, String prize, String description,
                                                String requiredRole) {
        StringBuilder u = new StringBuilder("/dash/engagement?tab=giveaways&err=").append(urlEnc(err));
        if (channelId != null && !channelId.isEmpty()) u.append("&g_channel=").append(urlEnc(channelId));
        if (winnersRaw != null && !winnersRaw.isEmpty()) u.append("&g_winners=").append(urlEnc(winnersRaw));
        if (durationRaw != null && !durationRaw.isEmpty()) u.append("&g_duration=").append(urlEnc(durationRaw));
        if (prize != null && !prize.isEmpty()) u.append("&g_prize=").append(urlEnc(prize));
        if (description != null && !description.isEmpty()) u.append("&g_desc=").append(urlEnc(description));
        if (requiredRole != null && !requiredRole.isEmpty()) u.append("&g_role=").append(urlEnc(requiredRole));
        return u.toString();
    }

    public void closePoll(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        engagement.polls().close(id);
        if (discord != null && discord.jda() != null) engagement.postPoll(discord.jda(), id);
        ctx.redirect("/dash/engagement?tab=polls");
    }

    public void drawGiveaway(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        if (discord != null && discord.jda() != null) engagement.drawWinners(discord.jda(), id);
        ctx.redirect("/dash/engagement?tab=giveaways");
    }

    public void cancelGiveaway(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        engagement.giveaways().cancel(id);
        if (discord != null && discord.jda() != null) engagement.postGiveaway(discord.jda(), id);
        ctx.redirect("/dash/engagement?tab=giveaways");
    }

    private static String creatorOf(Context ctx) {
        Optional<SessionCookie.Session> sess = DashAuth.sessionOf(ctx);
        return sess.map(SessionCookie.Session::discordId).orElse("");
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    private static String urlEnc(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String relativeTimeTag(long epochMillis) {
        return "<time datetime=\"" + java.time.Instant.ofEpochMilli(epochMillis) + "\">"
                + relativeTime(epochMillis) + "</time>";
    }

    private static String relativeTime(long epochMillis) {
        long diffSec = (epochMillis - System.currentTimeMillis()) / 1000L;
        boolean future = diffSec >= 0;
        long abs = Math.abs(diffSec);
        long n;
        String unit;
        if (abs < 60) { n = abs; unit = "second"; }
        else if (abs < 3600) { n = abs / 60; unit = "minute"; }
        else if (abs < 86400) { n = abs / 3600; unit = "hour"; }
        else { n = abs / 86400; unit = "day"; }
        if (n == 0) return "now";
        String plural = n == 1 ? "" : "s";
        return future ? ("in " + n + " " + unit + plural) : (n + " " + unit + plural + " ago");
    }
}
