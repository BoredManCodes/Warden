package io.warden.web.handlers;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.warden.discord.DiscordService;
import io.warden.tickets.Ticket;
import io.warden.tickets.TicketCategory;
import io.warden.tickets.TicketDao;
import io.warden.tickets.TicketMessage;
import io.warden.tickets.TicketPanel;
import io.warden.tickets.TicketService;
import io.warden.tickets.TicketStatus;
import io.warden.tickets.TicketsConfig;
import io.warden.web.auth.DashAuth;
import io.warden.web.auth.SessionCookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DashTicketsHandlers {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TicketService service;
    private final DiscordService discord;
    private final GuildLookup lookup;

    public DashTicketsHandlers(TicketService service, DiscordService discord, GuildLookup lookup) {
        this.service = service;
        this.discord = discord;
        this.lookup = lookup;
    }

    /* ============================ List ============================ */

    public void list(Context ctx) throws Exception {
        String statusFilter = ctx.queryParam("status");
        TicketStatus parsedStatus = (statusFilter == null || statusFilter.isBlank() || "all".equalsIgnoreCase(statusFilter))
                ? null : TicketStatus.fromWire(statusFilter);

        List<Ticket> rows = service.tickets().list(parsedStatus, null, 250);
        List<TicketCategory> cats = service.categories().listAll();
        Map<Long, TicketCategory> catById = new HashMap<>();
        for (TicketCategory c : cats) catById.put(c.id(), c);

        Map<TicketStatus, Integer> counts = new HashMap<>();
        for (TicketDao.StatusCount sc : service.tickets().countsByStatus()) {
            counts.merge(sc.status(), sc.count(), Integer::sum);
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Tickets · Warden", "tickets", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">Tickets</h1>");
        h.append("<div class=btn-group>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/tickets/panels\">Panels</a>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/tickets/categories\">Categories</a>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/tickets/settings\">Settings</a>");
        h.append("</div></div>");

        h.append("<div class=\"mb-3\">");
        h.append(filterPill("All", null, parsedStatus, totalCount(counts)));
        for (TicketStatus s : TicketStatus.values()) {
            h.append(filterPill(s.label(), s, parsedStatus, counts.getOrDefault(s, 0)));
        }
        h.append("</div>");

        if (rows.isEmpty()) {
            h.append("<p class=\"text-secondary\">No tickets match this filter.</p>");
        } else {
            h.append("<div class=\"table-responsive\"><table class=\"table table-hover align-middle\">");
            h.append("<thead><tr><th>#</th><th>Subject</th><th>From</th><th>Category</th>"
                    + "<th>Mode</th><th>Status</th><th>Assignee</th><th>Updated</th></tr></thead><tbody>");
            for (Ticket t : rows) {
                TicketCategory cat = t.categoryId() == null ? null : catById.get(t.categoryId());
                h.append("<tr style=\"cursor:pointer\" onclick=\"window.location='/dash/tickets/")
                        .append(t.id()).append("'\">")
                        .append("<td><code>#").append(t.id()).append("</code></td>")
                        .append("<td>").append(Layout.escape(t.subject())).append("</td>")
                        .append("<td>").append(Layout.escape(displayName(t))).append("</td>")
                        .append("<td>").append(cat == null
                                ? "<span class=text-secondary>(uncategorised)</span>"
                                : Layout.escape(cat.name())).append("</td>")
                        .append("<td>").append(modeBadge(t.mode())).append("</td>")
                        .append("<td>").append(statusBadge(t.status())).append("</td>")
                        .append("<td>").append(t.assigneeName() == null || t.assigneeName().isBlank()
                                ? "<span class=text-secondary>-</span>"
                                : Layout.escape(t.assigneeName())).append("</td>")
                        .append("<td><span class=\"text-secondary small\">").append(TS.format(Instant.ofEpochMilli(t.lastActivityAt())))
                        .append("</span></td>")
                        .append("</tr>");
            }
            h.append("</tbody></table></div>");
        }

        // Closed tickets with a transcript get their own section so staff
        // can pull up old conversations without paging through the main list.
        List<Ticket> closed = service.tickets().listClosedWithTranscript(50);
        if (!closed.isEmpty()) {
            h.append("<h2 class=\"h5 mt-5\">Closed tickets</h2>");
            h.append("<p class=\"text-secondary small\">The 50 most recently closed tickets that have a transcript.</p>");
            h.append("<div class=\"table-responsive\"><table class=\"table table-hover align-middle\">");
            h.append("<thead><tr><th>#</th><th>Subject</th><th>From</th><th>Category</th>"
                    + "<th>Closed</th><th>Transcript</th></tr></thead><tbody>");
            for (Ticket t : closed) {
                TicketCategory cat = t.categoryId() == null ? null : catById.get(t.categoryId());
                long when = t.closedAt() == null ? t.lastActivityAt() : t.closedAt();
                h.append("<tr>")
                        .append("<td><a href=\"/dash/tickets/").append(t.id()).append("\"><code>#")
                        .append(t.id()).append("</code></a></td>")
                        .append("<td>").append(Layout.escape(t.subject())).append("</td>")
                        .append("<td>").append(Layout.escape(displayName(t))).append("</td>")
                        .append("<td>").append(cat == null
                                ? "<span class=text-secondary>(uncategorised)</span>"
                                : Layout.escape(cat.name())).append("</td>")
                        .append("<td><span class=\"text-secondary small\">")
                        .append(TS.format(Instant.ofEpochMilli(when))).append("</span></td>")
                        .append("<td><a class=\"btn btn-sm btn-outline-secondary\" target=\"_blank\" rel=\"noopener\" href=\"/tickets/transcript/")
                        .append(Layout.escape(t.transcriptToken()))
                        .append("\"><i class=\"bi bi-file-text\"></i> View transcript</a></td>")
                        .append("</tr>");
            }
            h.append("</tbody></table></div>");
        }

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static int totalCount(Map<TicketStatus, Integer> counts) {
        int sum = 0;
        for (int n : counts.values()) sum += n;
        return sum;
    }

    private static String filterPill(String label, TicketStatus status, TicketStatus current, int count) {
        String href = "/dash/tickets" + (status == null ? "" : "?status=" + status.wire());
        boolean active = (status == null && current == null) || (status != null && status == current);
        String cls = "btn btn-sm me-1 mb-1 " + (active ? "btn-primary" : "btn-outline-secondary");
        return "<a class=\"" + cls + "\" href=\"" + href + "\">"
                + Layout.escape(label) + " <span class=badge text-bg-light ms-1>" + count + "</span></a>";
    }

    private static String statusBadge(TicketStatus s) {
        String cls = switch (s) {
            case OPEN -> "text-bg-primary";
            case IN_PROGRESS -> "text-bg-warning";
            case RESOLVED -> "text-bg-success";
            case CLOSED -> "text-bg-secondary";
        };
        return "<span class=\"badge " + cls + "\">" + Layout.escape(s.label()) + "</span>";
    }

    private static String modeBadge(String mode) {
        boolean channel = TicketsConfig.MODE_CHANNEL.equalsIgnoreCase(mode);
        return "<span class=\"badge " + (channel ? "text-bg-info" : "text-bg-light")
                + "\">" + (channel ? "Channel" : "DM") + "</span>";
    }

    private String displayName(Ticket t) {
        if (t.discordUsername() != null && !t.discordUsername().isBlank()) {
            return t.discordUsername();
        }
        return lookup.userName(t.discordId()).orElseGet(t::discordId);
    }

    /* ============================ Detail ============================ */

    public void detail(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Ticket t = service.tickets().find(id).orElse(null);
        if (t == null) {
            ctx.status(404).html("Ticket not found.");
            return;
        }
        List<TicketMessage> msgs = service.tickets().messages(id);
        TicketCategory cat = t.categoryId() == null
                ? null
                : service.categories().find(t.categoryId()).orElse(null);

        long lastMessageId = 0L;
        for (TicketMessage m : msgs) {
            if (m.id() > lastMessageId) lastMessageId = m.id();
        }

        StringBuilder h = new StringBuilder(8192);
        h.append(Layout.head("Ticket #" + id + " · Warden", "tickets", ctx));
        h.append("<div class=\"d-flex align-items-center mb-3\">");
        h.append("<a class=\"btn btn-sm btn-outline-secondary me-3\" href=\"/dash/tickets\">"
                + "<i class=\"bi bi-arrow-left\"></i> Back</a>");
        h.append("<h1 class=\"h4 mb-0\">#").append(id).append(" ").append(Layout.escape(t.subject())).append("</h1>");
        h.append("<span class=\"ms-3\">").append(statusBadge(t.status())).append("</span>");
        h.append("<span class=\"ms-2\">").append(modeBadge(t.mode())).append("</span>");
        h.append("<span class=\"ms-3 small text-secondary\" id=\"ticket-live-indicator\" title=\"Auto-refreshes every few seconds\">"
                + "<i class=\"bi bi-broadcast me-1\"></i>Live</span>");
        h.append("</div>");

        h.append("<div class=\"row g-3 mb-3\">");
        h.append("<div class=\"col-md-8\">");
        h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
        h.append("<div class=\"d-flex justify-content-between align-items-start mb-2\">");
        h.append("<div><strong>").append(Layout.escape(displayName(t))).append("</strong></div>");
        h.append("<div class=\"text-secondary small\">").append(TS.format(Instant.ofEpochMilli(t.createdAt()))).append("</div>");
        h.append("</div>");
        h.append("<div class=\"mt-2\" style=\"white-space:pre-wrap\">")
                .append(Layout.escape(t.body())).append("</div>");
        h.append("</div></div>");

        h.append("<div id=\"ticket-messages\">");
        for (TicketMessage m : msgs) {
            if (m.id() == msgs.get(0).id() && TicketMessage.KIND_USER.equals(m.authorKind())
                    && (m.attachmentsJson() == null || m.attachmentsJson().isBlank())) {
                // First user message duplicates ticket body (and has no extra attachments), skip.
                continue;
            }
            boolean internal = TicketMessage.KIND_INTERNAL.equals(m.authorKind());
            h.append("<div class=\"card mb-2").append(internal ? " border-warning" : "").append("\">");
            String headerCls = switch (m.authorKind()) {
                case TicketMessage.KIND_STAFF -> "bg-primary-subtle";
                case TicketMessage.KIND_INTERNAL -> "bg-warning-subtle";
                case TicketMessage.KIND_SYSTEM -> "bg-body-tertiary";
                default -> "";
            };
            h.append("<div class=\"card-header py-2 ").append(headerCls).append("\">");
            if (internal) {
                h.append("<i class=\"bi bi-shield-lock me-1\" title=\"Staff-only, hidden from reporter\"></i>");
            }
            h.append("<strong>").append(Layout.escape(authorLabel(m))).append("</strong>")
                    .append(" <span class=\"text-secondary small ms-2\">")
                    .append(TS.format(Instant.ofEpochMilli(m.createdAt())))
                    .append("</span>");
            if (internal) {
                h.append(" <span class=\"badge text-bg-warning ms-2\">Internal</span>");
            }
            h.append("</div>");
            h.append("<div class=\"card-body py-2\">");
            if (m.body() != null && !m.body().isBlank()) {
                h.append("<div style=\"white-space:pre-wrap\">").append(Layout.escape(m.body())).append("</div>");
            }
            List<TicketMessage.Attachment> atts = m.attachments();
            if (!atts.isEmpty()) {
                h.append("<div class=\"mt-2 d-flex flex-wrap gap-2\">");
                for (TicketMessage.Attachment a : atts) {
                    h.append(renderAttachmentChip(id, m.id(), a));
                }
                h.append("</div>");
            }
            h.append("</div></div>");
        }
        h.append("</div>");

        boolean closedLock = false;
        try { closedLock = service.config().get().closedLockReplies() && t.status().terminal(); }
        catch (Exception ignored) {}

        if (!closedLock) {
            h.append("<form method=post enctype=\"multipart/form-data\" action=\"/dash/tickets/").append(id).append("/reply\" class=\"mt-3\">");
            h.append("<label class=form-label d-flex align-items-center justify-content-between\">")
                    .append("<span>Reply</span>")
                    .append("<button type=\"button\" id=\"ai-reply-toggle\" class=\"btn btn-sm btn-outline-secondary\">")
                    .append("<i class=\"bi bi-stars me-1\"></i>Suggest with AI</button>")
                    .append("</label>");
            h.append("<div id=\"ai-reply-panel\" class=\"border rounded p-2 mb-2 bg-body-tertiary\" style=\"display:none\">")
                    .append("<div class=\"input-group input-group-sm\">")
                    .append("<input id=\"ai-reply-guidance\" class=\"form-control\" type=\"text\" maxlength=\"500\" ")
                    .append("placeholder=\"Optional steer: e.g. apologise and offer a refund of in-game coins\">")
                    .append("<button type=\"button\" id=\"ai-reply-go\" class=\"btn btn-primary\">")
                    .append("<i class=\"bi bi-magic me-1\"></i>Draft</button>")
                    .append("</div>")
                    .append("<div id=\"ai-reply-status\" class=\"small text-secondary mt-1\"></div>")
                    .append("</div>");
            h.append("<textarea class=\"form-control\" id=\"reply-body\" name=body rows=4 ")
                    .append("placeholder=\"Reply to the reporter. They'll get a DM or a message in their ticket channel.\"></textarea>");
            h.append("<div class=\"mt-2\"><label class=\"form-label small mb-1\">Attachments</label>")
                    .append("<input class=\"form-control form-control-sm\" type=file name=\"files\" multiple></div>");
            h.append("<div class=\"mt-2\"><button class=\"btn btn-primary\">Send reply</button></div>");
            h.append("</form>");
            h.append("<script>(function(){")
                    .append("var tog=document.getElementById('ai-reply-toggle');")
                    .append("var pane=document.getElementById('ai-reply-panel');")
                    .append("var go=document.getElementById('ai-reply-go');")
                    .append("var gIn=document.getElementById('ai-reply-guidance');")
                    .append("var stat=document.getElementById('ai-reply-status');")
                    .append("var body=document.getElementById('reply-body');")
                    .append("if(tog){tog.addEventListener('click',function(){")
                    .append("var open=pane.style.display!=='none';pane.style.display=open?'none':'';")
                    .append("if(!open){setTimeout(function(){gIn&&gIn.focus();},0);}")
                    .append("});}")
                    .append("if(go){go.addEventListener('click',function(){")
                    .append("go.disabled=true;stat.textContent='Asking the AI...';")
                    .append("var b=new URLSearchParams();b.set('guidance',(gIn.value||'').trim());")
                    .append("fetch('/dash/ai/tickets/").append(id).append("/reply',{method:'POST',credentials:'same-origin',")
                    .append("headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b.toString()})")
                    .append(".then(function(r){return r.json().catch(function(){return{ok:false,message:'HTTP '+r.status};});})")
                    .append(".then(function(j){")
                    .append("if(!j||!j.ok){stat.textContent='Failed: '+((j&&j.message)||'unknown');return;}")
                    .append("if(body){body.value=j.draft||'';body.focus();}")
                    .append("stat.textContent='Drafted. Review before sending.';")
                    .append("})")
                    .append(".catch(function(e){stat.textContent='Network error: '+(e.message||e);})")
                    .append(".finally(function(){go.disabled=false;});")
                    .append("});}")
                    .append("})();</script>");
        } else {
            h.append("<p class=\"text-secondary mt-3\"><em>This ticket is ").append(t.status().label().toLowerCase())
                    .append(". Reopen it to reply.</em></p>");
        }

        // Internal notes can be added in any status; they never reach the reporter.
        h.append("<form method=post action=\"/dash/tickets/").append(id)
                .append("/note\" class=\"mt-3 p-3 rounded border border-warning-subtle bg-warning-subtle\">");
        h.append("<label class=\"form-label d-flex align-items-center gap-2 mb-1\">")
                .append("<i class=\"bi bi-shield-lock\"></i><strong>Internal note</strong>")
                .append("<span class=\"badge text-bg-warning\">Staff only</span></label>");
        h.append("<p class=\"small text-secondary mb-2\">Visible to staff on this dashboard only. "
                + "Not DM'd to the reporter, not posted to the ticket channel, and excluded from transcripts.</p>");
        h.append("<textarea class=\"form-control\" name=body rows=3 required ")
                .append("placeholder=\"Leave a note for other staff about this ticket…\"></textarea>");
        h.append("<div class=\"mt-2 text-end\"><button class=\"btn btn-warning\">")
                .append("<i class=\"bi bi-shield-lock me-1\"></i>Save internal note</button></div>");
        h.append("</form>");
        h.append("</div>");

        h.append("<div class=\"col-md-4\">");
        h.append("<div class=\"card\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Details</h2>");
        h.append("<dl class=\"row mb-0 small\">");
        h.append("<dt class=col-5>Category</dt><dd class=col-7>").append(cat == null ? "(uncategorised)" : Layout.escape(cat.name())).append("</dd>");
        h.append("<dt class=col-5>Reporter</dt><dd class=col-7>").append(Layout.escape(displayName(t))).append("</dd>");
        h.append("<dt class=col-5>Mode</dt><dd class=col-7>").append(modeBadge(t.mode())).append("</dd>");
        if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
            h.append("<dt class=col-5>Channel</dt><dd class=col-7>")
                    .append(Layout.escape(lookup.channelName(t.channelId()).orElse("(channel unavailable)")))
                    .append("</dd>");
        }
        h.append("<dt class=col-5>Opened</dt><dd class=col-7>").append(TS.format(Instant.ofEpochMilli(t.createdAt()))).append("</dd>");
        h.append("<dt class=col-5>Updated</dt><dd class=col-7>").append(TS.format(Instant.ofEpochMilli(t.lastActivityAt()))).append("</dd>");
        h.append("<dt class=col-5>Assignee</dt><dd class=col-7>").append(
                t.assigneeName() == null || t.assigneeName().isBlank() ? "-" : Layout.escape(t.assigneeName())
        ).append("</dd>");
        h.append("</dl>");
        h.append("</div></div>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Actions</h2>");
        h.append("<form method=post action=\"/dash/tickets/").append(id).append("/status\" class=\"d-flex gap-2 mb-2\">");
        h.append("<select class=form-select form-select-sm name=status>");
        for (TicketStatus s : TicketStatus.values()) {
            h.append("<option value=\"").append(s.wire()).append("\"")
                    .append(s == t.status() ? " selected" : "").append(">")
                    .append(Layout.escape(s.label())).append("</option>");
        }
        h.append("</select>");
        h.append("<button class=\"btn btn-sm btn-outline-primary\">Set</button>");
        h.append("</form>");
        h.append("<form method=post action=\"/dash/tickets/").append(id).append("/assign\">");
        h.append("<button class=\"btn btn-sm btn-outline-secondary w-100\">Take this ticket</button>");
        h.append("</form>");
        h.append("</div></div>");

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Migrate to channel</h2>");
        if (t.isChannelMode() && t.channelId() != null && !t.channelId().isBlank()) {
            String chName = lookup.channelName(t.channelId()).orElse("(channel unavailable)");
            h.append("<p class=\"small text-secondary mb-0\">This ticket runs in its own channel "
                    + "<strong>").append(Layout.escape(chName))
                    .append("</strong>. Replies on the dashboard post directly there.</p>");
        } else {
            h.append("<p class=\"small text-secondary mb-2\">Move this DM-only ticket into a private Discord "
                    + "channel. The history is replayed under each author's original name, the reporter is "
                    + "DM'd a link and pinged in the new channel, and replies from then on behave like any "
                    + "channel ticket (the reporter posts in the channel, dashboard replies post back to it).</p>");
            h.append("<form method=post action=\"/dash/tickets/").append(id).append("/migrate\" ")
                    .append("data-confirm=\"Create a Discord channel and move this ticket into it?\" ")
                    .append("data-confirm-title=\"Migrate to channel\" data-confirm-ok=\"Migrate\">");
            h.append("<button class=\"btn btn-sm btn-outline-primary w-100\"><i class=\"bi bi-discord\"></i> Migrate to channel</button>");
            h.append("</form>");
        }
        h.append("</div></div>");

        h.append(renderParticipantsCard(id, t));

        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Transcript</h2>");
        if (t.hasTranscript()) {
            h.append("<p class=\"small text-secondary mb-2\">Generated ")
                    .append(t.transcriptGeneratedAt() == null ? "-"
                            : TS.format(Instant.ofEpochMilli(t.transcriptGeneratedAt())))
                    .append("</p>");
            h.append("<a class=\"btn btn-sm btn-outline-primary w-100 mb-2\" target=\"_blank\" rel=\"noopener\" href=\"/tickets/transcript/")
                    .append(Layout.escape(t.transcriptToken()))
                    .append("\"><i class=\"bi bi-file-text\"></i> Open transcript</a>");
        } else {
            h.append("<p class=\"small text-secondary mb-2\">A transcript is rendered automatically when the ticket is closed.</p>");
        }
        h.append("<form method=post action=\"/dash/tickets/").append(id).append("/transcript/regen\">");
        h.append("<button class=\"btn btn-sm btn-outline-secondary w-100\">")
                .append(t.hasTranscript() ? "Regenerate transcript" : "Generate transcript now")
                .append("</button>");
        h.append("</form>");
        h.append("</div></div>");

        h.append("</div>");
        h.append("</div>");

        var sess = DashAuth.sessionOf(ctx);
        String selfDiscordId = sess.map(SessionCookie.Session::discordId).orElse("");
        h.append(buildLiveScript(id, lastMessageId, selfDiscordId));

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    /**
     * Inline JS that polls /dash/tickets/:id/messages.json for new messages,
     * appends them to the conversation, and plays a short bonk on every new
     * user reply (synthesised via WebAudio so no audio asset is bundled).
     * Polling pauses while the document is hidden to avoid running while the
     * tab is in the background.
     */
    private static String buildLiveScript(long ticketId, long lastMessageId, String selfDiscordId) {
        return "<script>(function(){"
                + "var TICKET_ID=" + ticketId + ";"
                + "var lastId=" + lastMessageId + ";"
                + "var SELF=" + JSON.valueToTree(selfDiscordId).toString() + ";"
                + "var container=document.getElementById('ticket-messages');"
                + "if(!container)return;"
                + "var indicator=document.getElementById('ticket-live-indicator');"
                + "var audioCtx=null;"
                + "function bonk(){"
                + "try{"
                + "if(!audioCtx){audioCtx=new (window.AudioContext||window.webkitAudioContext)();}"
                + "var t=audioCtx.currentTime;"
                + "var o=audioCtx.createOscillator();"
                + "var g=audioCtx.createGain();"
                + "o.type='sine';"
                + "o.frequency.setValueAtTime(220,t);"
                + "o.frequency.exponentialRampToValueAtTime(70,t+0.15);"
                + "g.gain.setValueAtTime(0.0001,t);"
                + "g.gain.exponentialRampToValueAtTime(0.35,t+0.01);"
                + "g.gain.exponentialRampToValueAtTime(0.0001,t+0.22);"
                + "o.connect(g);g.connect(audioCtx.destination);"
                + "o.start(t);o.stop(t+0.25);"
                + "}catch(e){}"
                + "}"
                + "function escapeHtml(s){return (s==null?'':String(s)).replace(/[&<>\"']/g,function(c){"
                + "return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;','\\'':'&#39;'}[c];});}"
                + "function render(m){"
                + "var hdrCls=m.kind==='staff'?'bg-primary-subtle':(m.kind==='internal'?'bg-warning-subtle':(m.kind==='system'?'bg-body-tertiary':''));"
                + "var card=document.createElement('div');"
                + "card.className='card mb-2'+(m.kind==='internal'?' border-warning':'');"
                + "var atts='';"
                + "if(m.attachments&&m.attachments.length){"
                + "atts='<div class=\"mt-2 d-flex flex-wrap gap-2\">'+m.attachments.map(function(a){"
                + "return '<a class=\"btn btn-sm btn-outline-secondary\" target=_blank rel=noopener href=\"'+escapeHtml(a.href)+'\"><i class=\"bi bi-paperclip\"></i> '+escapeHtml(a.name)+(a.sizeHuman?' <span class=\"text-secondary small\">('+escapeHtml(a.sizeHuman)+')</span>':'')+'</a>';"
                + "}).join('')+'</div>';"
                + "}"
                + "var bodyHtml=m.body?'<div style=\"white-space:pre-wrap\">'+escapeHtml(m.body)+'</div>':'';"
                + "var prefix=m.kind==='internal'?'<i class=\"bi bi-shield-lock me-1\"></i>':'';"
                + "var suffix=m.kind==='internal'?' <span class=\"badge text-bg-warning ms-2\">Internal</span>':'';"
                + "card.innerHTML='<div class=\"card-header py-2 '+hdrCls+'\">'+prefix+'<strong>'+escapeHtml(m.authorLabel)+'</strong> <span class=\"text-secondary small ms-2\">'+escapeHtml(m.timeHuman)+'</span>'+suffix+'</div>'"
                + "+'<div class=\"card-body py-2\">'+bodyHtml+atts+'</div>';"
                + "container.appendChild(card);"
                + "}"
                + "function tick(){"
                + "if(document.hidden)return;"
                + "fetch('/dash/tickets/'+TICKET_ID+'/messages.json?since='+encodeURIComponent(lastId),"
                + "{credentials:'same-origin',headers:{'Accept':'application/json'}})"
                + ".then(function(r){if(!r.ok)throw new Error('http '+r.status);return r.json();})"
                + ".then(function(data){"
                + "if(!data||!data.messages)return;"
                + "var bonkable=false;"
                + "data.messages.forEach(function(m){"
                + "render(m);"
                + "if(m.id>lastId)lastId=m.id;"
                + "if(m.kind==='user' && m.authorId!==SELF)bonkable=true;"
                + "});"
                + "if(bonkable)bonk();"
                + "if(indicator)indicator.classList.remove('text-danger');"
                + "})"
                + ".catch(function(){if(indicator)indicator.classList.add('text-danger');});"
                + "}"
                + "setInterval(tick,4000);"
                + "document.addEventListener('visibilitychange',function(){if(!document.hidden)tick();});"
                + "})();</script>";
    }

    /**
     * Returns ticket messages newer than ?since=<id> as JSON. The detail page
     * polls this every few seconds so new replies show up without a full
     * reload, and the dashboard plays a "bonk" sound the moment a user reply
     * arrives.
     */
    public void messagesJson(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        long since = 0L;
        try { since = Long.parseLong(ctx.queryParam("since")); } catch (Exception ignored) {}
        Ticket t = service.tickets().find(id).orElse(null);
        if (t == null) { ctx.status(404).json(java.util.Map.of("error", "not found")); return; }
        List<TicketMessage> msgs = service.tickets().messagesSince(id, since);
        ArrayNode arr = JSON.createArrayNode();
        for (TicketMessage m : msgs) {
            ObjectNode o = JSON.createObjectNode();
            o.put("id", m.id());
            o.put("kind", m.authorKind());
            o.put("authorId", m.authorId() == null ? "" : m.authorId());
            o.put("authorLabel", authorLabel(m));
            o.put("body", m.body() == null ? "" : m.body());
            o.put("timeHuman", TS.format(Instant.ofEpochMilli(m.createdAt())));
            ArrayNode atts = JSON.createArrayNode();
            for (TicketMessage.Attachment a : m.attachments()) {
                String href;
                if (a.storedPath() != null && !a.storedPath().isBlank()) {
                    href = "/dash/tickets/" + id + "/attachments/" + m.id() + "/" + urlEncode(a.name());
                } else if (a.discordUrl() != null && !a.discordUrl().isBlank()) {
                    href = a.discordUrl();
                } else {
                    href = "#";
                }
                ObjectNode an = JSON.createObjectNode();
                an.put("name", a.name() == null ? "file" : a.name());
                an.put("href", href);
                an.put("sizeHuman", a.size() > 0 ? humanSize(a.size()) : "");
                atts.add(an);
            }
            o.set("attachments", atts);
            arr.add(o);
        }
        ObjectNode out = JSON.createObjectNode();
        out.set("messages", arr);
        out.put("lastId", service.tickets().latestMessageId(id));
        ctx.json(out);
    }

    public void migrate(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        var jda = discord == null ? null : discord.jda();
        var sess = DashAuth.sessionOf(ctx);
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        TicketService.MigrateResult result = service.migrateToChannel(jda, id, staffName);
        String target = "/dash/tickets/" + id;
        switch (result) {
            case OK -> ctx.redirect(Layout.flashRedirect(target, "success",
                    "Ticket migrated to a Discord channel. The reporter has been DM'd a link."));
            case ALREADY_MIGRATED -> ctx.redirect(Layout.flashRedirect(target, "info",
                    "This ticket already runs in a Discord channel."));
            case NOT_FOUND -> ctx.redirect(Layout.flashRedirect("/dash/tickets", "error",
                    "That ticket no longer exists."));
            case NO_CATEGORY -> ctx.redirect(Layout.flashRedirect(target, "warn",
                    "Set a ticket Discord category in /dash/tickets/settings first."));
            default -> ctx.redirect(Layout.flashRedirect(target, "error",
                    "Couldn't migrate this ticket. Check the server logs for details."));
        }
    }

    /** Add a user (by Discord ID) to a channel-mode ticket. */
    public void participantAdd(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String userId = ctx.formParam("user_id");
        if (userId != null) userId = userId.trim();
        var jda = discord == null ? null : discord.jda();
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        TicketService.ParticipantOutcome out = service.addParticipant(jda, id, userId, staffId, staffName);
        ctx.redirect(participantRedirect(id, out, true, userId));
    }

    /**
     * Autocomplete endpoint for the "add participant" search box. Hits the
     * Discord HTTP API via JDA's prefix-search; returns up to 10 candidates
     * with a display name + handle so staff don't have to copy IDs.
     */
    public void membersSearch(Context ctx) throws Exception {
        String q = ctx.queryParam("q");
        ObjectNode out = JSON.createObjectNode();
        ArrayNode arr = out.putArray("members");
        if (q == null || q.trim().length() < 2) {
            ctx.json(out);
            return;
        }
        var jda = discord == null ? null : discord.jda();
        if (jda == null) { ctx.json(out); return; }
        var guild = jda.getGuildById(lookup.guildId());
        if (guild == null) { ctx.json(out); return; }

        try {
            var members = guild.retrieveMembersByPrefix(q.trim(), 10).get();
            if (members != null) {
                for (var m : members) {
                    String global = m.getUser().getGlobalName();
                    String displayName = global != null && !global.isBlank()
                            ? global : m.getEffectiveName();
                    ObjectNode o = JSON.createObjectNode();
                    o.put("id", m.getId());
                    o.put("name", displayName);
                    o.put("handle", "@" + m.getUser().getName());
                    o.put("avatar", m.getUser().getEffectiveAvatarUrl());
                    arr.add(o);
                }
            }
        } catch (Exception e) {
            // Network/cache hiccup - just return what we have (empty list).
        }
        ctx.json(out);
    }

    /** Remove a user from a channel-mode ticket. */
    public void participantRemove(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String userId = ctx.pathParam("userId");
        var jda = discord == null ? null : discord.jda();
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        TicketService.ParticipantOutcome out = service.removeParticipant(jda, id, userId, staffId, staffName);
        ctx.redirect(participantRedirect(id, out, false, userId));
    }

    private static String participantRedirect(long ticketId, TicketService.ParticipantOutcome out,
                                              boolean adding, String userId) {
        String target = "/dash/tickets/" + ticketId;
        String label = adding ? "added" : "removed";
        return switch (out.result()) {
            case OK -> Layout.flashRedirect(target, "success",
                    (out.addedUserName() == null || out.addedUserName().isBlank()
                            ? "User " : out.addedUserName() + " ")
                            + label + ".");
            case NOT_FOUND -> Layout.flashRedirect("/dash/tickets", "error",
                    "That ticket no longer exists.");
            case NOT_A_CHANNEL_TICKET -> Layout.flashRedirect(target, "warn",
                    "Adding people only works for channel tickets. Migrate this ticket to a channel first, "
                            + "then add participants from the Participants card.");
            case CHANNEL_MISSING -> Layout.flashRedirect(target, "error",
                    "The ticket's Discord channel is missing. It may have been deleted; you'll need to "
                            + "open a fresh ticket or recreate the channel.");
            case USER_NOT_FOUND -> Layout.flashRedirect(target, "warn",
                    "Couldn't find a member with ID " + (userId == null ? "" : userId)
                            + " in this Discord server.");
            case ALREADY_ADDED -> Layout.flashRedirect(target, "info",
                    "That user is already a participant in this ticket.");
            case NOT_A_PARTICIPANT -> Layout.flashRedirect(target, "info",
                    "That user wasn't a participant in this ticket.");
            case IS_REPORTER -> Layout.flashRedirect(target, "warn",
                    "That user is the ticket reporter and is added automatically.");
            default -> Layout.flashRedirect(target, "error",
                    "Couldn't " + (adding ? "add" : "remove") + " that user. Check server logs for details.");
        };
    }

    private static String renderAttachmentChip(long ticketId, long messageId, TicketMessage.Attachment a) {
        String name = a.name() == null || a.name().isBlank() ? "file" : a.name();
        String href;
        if (a.storedPath() != null && !a.storedPath().isBlank()) {
            href = "/dash/tickets/" + ticketId + "/attachments/" + messageId + "/" + urlEncode(name);
        } else if (a.discordUrl() != null && !a.discordUrl().isBlank()) {
            href = a.discordUrl();
        } else {
            href = "#";
        }
        String sizeNote = a.size() > 0 ? " <span class=\"text-secondary small\">(" + humanSize(a.size()) + ")</span>" : "";
        return "<a class=\"btn btn-sm btn-outline-secondary\" target=_blank rel=noopener href=\""
                + Layout.escape(href) + "\"><i class=\"bi bi-paperclip\"></i> "
                + Layout.escape(name) + sizeNote + "</a>";
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return (bytes / (1024L * 1024 * 1024)) + " GB";
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String authorLabel(TicketMessage m) {
        String kind = m.authorKind();
        String name = m.authorName() == null || m.authorName().isBlank() ? "(unknown)" : m.authorName();
        return switch (kind) {
            case TicketMessage.KIND_STAFF -> name + " (staff)";
            case TicketMessage.KIND_INTERNAL -> name + " (internal note)";
            case TicketMessage.KIND_SYSTEM -> "System";
            default -> name;
        };
    }

    /**
     * Append a staff-only internal note. Stored in the ticket conversation but
     * tagged as internal so it is never DM'd, never mirrored to the ticket
     * channel, and never included in the transcript shared with the reporter.
     */
    public void note(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String body = ctx.formParam("body");
        if (body == null || body.isBlank()) {
            ctx.redirect("/dash/tickets/" + id);
            return;
        }
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        service.postInternalNote(id, staffId, staffName, body);
        ctx.redirect("/dash/tickets/" + id);
    }

    public void reply(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        String body = ctx.formParam("body");
        List<UploadedFile> uploads = ctx.uploadedFiles("files");
        List<TicketMessage.Attachment> attachments = new ArrayList<>();
        if (uploads != null) {
            for (UploadedFile uf : uploads) {
                if (uf == null || uf.filename() == null || uf.filename().isBlank()) continue;
                long size = uf.size();
                if (size <= 0) continue;
                try (InputStream in = uf.content()) {
                    TicketMessage.Attachment a = service.storeUploadedFile(id, uf.filename(), in, size);
                    attachments.add(a);
                }
            }
        }
        if ((body == null || body.isBlank()) && attachments.isEmpty()) {
            ctx.redirect("/dash/tickets/" + id);
            return;
        }
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        service.replyAsStaff(discord != null ? discord.jda() : null, id, staffId, staffName, body, attachments);
        ctx.redirect("/dash/tickets/" + id);
    }

    /** Streams a previously-uploaded attachment back to the staffer's browser. */
    public void attachment(Context ctx) throws Exception {
        long ticketId = Long.parseLong(ctx.pathParam("id"));
        long messageId = Long.parseLong(ctx.pathParam("msgId"));
        String name = ctx.pathParam("name");
        Ticket t = service.tickets().find(ticketId).orElse(null);
        if (t == null) {
            ctx.status(404).html("Ticket not found.");
            return;
        }
        TicketMessage match = null;
        for (TicketMessage m : service.tickets().messages(ticketId)) {
            if (m.id() == messageId) { match = m; break; }
        }
        if (match == null) { ctx.status(404).html("Message not found."); return; }
        TicketMessage.Attachment found = null;
        for (TicketMessage.Attachment a : match.attachments()) {
            if (name.equals(a.name())) { found = a; break; }
        }
        if (found == null || found.storedPath() == null || found.storedPath().isBlank()) {
            ctx.status(404).html("Attachment not found.");
            return;
        }
        Path p = Path.of(found.storedPath());
        // Constrain to the configured attachments dir to avoid path traversal.
        Path base = service.attachmentsDir().toAbsolutePath().normalize();
        if (!p.toAbsolutePath().normalize().startsWith(base)) {
            ctx.status(403).html("Attachment outside permitted directory.");
            return;
        }
        if (!Files.exists(p)) { ctx.status(404).html("Attachment file missing."); return; }
        ctx.header("Content-Disposition", "inline; filename=\"" + sanitiseHeader(found.name()) + "\"");
        ctx.contentType(java.net.URLConnection.guessContentTypeFromName(found.name()) == null
                ? "application/octet-stream"
                : java.net.URLConnection.guessContentTypeFromName(found.name()));
        ctx.result(Files.newInputStream(p));
    }

    private static String sanitiseHeader(String s) {
        if (s == null) return "file";
        return s.replaceAll("[\\r\\n\"]", "_");
    }

    public void changeStatus(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        TicketStatus next = TicketStatus.fromWire(ctx.formParam("status"));
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        service.changeStatus(discord != null ? discord.jda() : null, id, next, staffId, staffName);
        ctx.redirect("/dash/tickets/" + id);
    }

    public void regenerateTranscript(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Ticket t = service.tickets().find(id).orElse(null);
        if (t != null) {
            service.transcripts().generate(discord != null ? discord.jda() : null, t);
        }
        ctx.redirect("/dash/tickets/" + id);
    }

    public void assign(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        var sess = DashAuth.sessionOf(ctx);
        String staffId = sess.map(SessionCookie.Session::discordId).orElse("");
        String staffName = sess.map(s -> {
            String n = s.displayName();
            if (n == null || n.isBlank()) n = s.username();
            return n == null ? "Staff" : n;
        }).orElse("Staff");
        service.assign(id, staffId, staffName);
        ctx.redirect("/dash/tickets/" + id);
    }

    /**
     * Sidebar card for adding/removing extra participants on a channel-mode
     * ticket. For DM-only tickets it explains that adding people needs a
     * channel and points at the "Migrate to channel" card next to it. For
     * channel tickets we render the current non-reporter VIEW_CHANNEL
     * overrides plus an add-by-ID form.
     */
    private String renderParticipantsCard(long ticketId, Ticket t) {
        StringBuilder h = new StringBuilder(512);
        h.append("<div class=\"card mt-3\"><div class=\"card-body\">");
        h.append("<h2 class=\"h6\">Participants</h2>");

        var jda = discord == null ? null : discord.jda();
        var chOpt = service.participantChannelId(t);

        if (chOpt.isEmpty()) {
            h.append("<p class=\"small text-secondary mb-0\">Adding extra people to a ticket only works for "
                    + "tickets that live in a Discord channel. Use <strong>Migrate to channel</strong> above to "
                    + "move this DM ticket into a private channel, then come back here to add participants.</p>");
            h.append("</div></div>");
            return h.toString();
        }

        if (jda == null) {
            h.append("<p class=\"small text-secondary mb-0\">Discord isn't connected right now, so participants "
                    + "can't be listed or modified. Try again in a moment.</p>");
            h.append("</div></div>");
            return h.toString();
        }

        List<TicketService.Participant> participants = service.listParticipants(jda, t);

        h.append("<p class=\"small text-secondary mb-2\">Reporter <strong>")
                .append(Layout.escape(displayName(t)))
                .append("</strong> is added automatically. Add other staff or stakeholders below.</p>");

        if (participants.isEmpty()) {
            h.append("<p class=\"small text-secondary mb-2\"><em>No extra participants yet.</em></p>");
        } else {
            h.append("<ul class=\"list-unstyled small mb-2\">");
            for (TicketService.Participant p : participants) {
                h.append("<li class=\"d-flex align-items-center justify-content-between gap-2 py-1\">")
                        .append("<span><i class=\"bi bi-person me-1\"></i>")
                        .append(Layout.escape(p.userName()))
                        .append("</span>")
                        .append("<form method=post action=\"/dash/tickets/").append(ticketId)
                        .append("/participants/").append(Layout.escape(p.userId())).append("/remove\" class=\"m-0\" ")
                        .append("data-confirm=\"Remove ")
                        .append(Layout.escape(p.userName()))
                        .append(" from this ticket?\" data-confirm-kind=\"danger\" data-confirm-ok=\"Remove\">")
                        .append("<button class=\"btn btn-sm btn-outline-danger\" title=\"Remove\">")
                        .append("<i class=\"bi bi-x-lg\"></i></button></form>")
                        .append("</li>");
            }
            h.append("</ul>");
        }

        h.append("<form method=post action=\"/dash/tickets/").append(ticketId).append("/participants\" class=\"position-relative\" data-participant-add>");
        h.append("<div class=\"input-group input-group-sm\">");
        h.append("<input class=\"form-control\" type=\"search\" autocomplete=\"off\" "
                + "data-participant-search placeholder=\"Search by name…\" aria-label=\"Search members\">");
        h.append("<button class=\"btn btn-primary text-nowrap\" type=\"submit\" disabled data-participant-submit>"
                + "<i class=\"bi bi-person-plus\"></i> Add</button>");
        h.append("</div>");
        h.append("<input type=\"hidden\" name=\"user_id\" data-participant-id value=\"\">");
        h.append("<div class=\"small text-secondary mt-1\" data-participant-selected></div>");
        h.append("<ul class=\"list-group position-absolute w-100 shadow-sm mt-1 d-none\" "
                + "style=\"z-index:50;max-height:240px;overflow:auto\" data-participant-results></ul>");
        h.append("</form>");
        h.append("<p class=\"form-text small mt-2 mb-0\">Type at least 2 characters of a name to search this server's members.</p>");

        h.append(buildParticipantSearchScript());

        h.append("</div></div>");
        return h.toString();
    }

    /**
     * Inline JS for the participant add form: debounced search against
     * /dash/tickets/members/search, click-to-select dropdown, hidden user_id
     * populated on select.
     */
    private static String buildParticipantSearchScript() {
        return "<script>(function(){"
                + "var form=document.querySelector('[data-participant-add]');"
                + "if(!form||form.dataset.bound)return;form.dataset.bound='1';"
                + "var input=form.querySelector('[data-participant-search]');"
                + "var hidden=form.querySelector('[data-participant-id]');"
                + "var submit=form.querySelector('[data-participant-submit]');"
                + "var selected=form.querySelector('[data-participant-selected]');"
                + "var list=form.querySelector('[data-participant-results]');"
                + "var timer=null;var lastQ='';"
                + "function esc(s){return (s==null?'':String(s)).replace(/[&<>\"']/g,function(c){"
                + "return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;','\\'':'&#39;'}[c];});}"
                + "function clearPicked(){hidden.value='';selected.textContent='';submit.disabled=true;}"
                + "function hide(){list.classList.add('d-none');list.innerHTML='';}"
                + "function render(items){"
                + "list.innerHTML='';"
                + "if(!items||!items.length){"
                + "list.innerHTML='<li class=\"list-group-item small text-secondary\">No matches.</li>';"
                + "list.classList.remove('d-none');return;"
                + "}"
                + "items.forEach(function(m){"
                + "var li=document.createElement('li');"
                + "li.className='list-group-item list-group-item-action d-flex align-items-center gap-2 py-1';"
                + "li.style.cursor='pointer';"
                + "li.innerHTML='<img src=\"'+esc(m.avatar)+'\" width=24 height=24 class=rounded-circle alt=\"\">'"
                + "+'<div class=\"flex-grow-1\"><div>'+esc(m.name)+'</div>'"
                + "+'<div class=\"small text-secondary\">'+esc(m.handle)+'</div></div>';"
                + "li.addEventListener('click',function(){"
                + "hidden.value=m.id;"
                + "selected.innerHTML='<i class=\"bi bi-check-circle text-success\"></i> Selected <strong>'+esc(m.name)+'</strong>';"
                + "input.value=m.name;"
                + "submit.disabled=false;"
                + "hide();"
                + "});"
                + "list.appendChild(li);"
                + "});"
                + "list.classList.remove('d-none');"
                + "}"
                + "function search(){"
                + "var q=input.value.trim();"
                + "if(q===lastQ)return;lastQ=q;"
                + "if(q.length<2){clearPicked();hide();return;}"
                + "fetch('/dash/tickets/members/search?q='+encodeURIComponent(q),"
                + "{credentials:'same-origin',headers:{'Accept':'application/json'}})"
                + ".then(function(r){return r.ok?r.json():{members:[]};})"
                + ".then(function(d){render(d.members||[]);})"
                + ".catch(function(){hide();});"
                + "}"
                + "input.addEventListener('input',function(){"
                + "clearPicked();"
                + "if(timer)clearTimeout(timer);"
                + "timer=setTimeout(search,250);"
                + "});"
                + "input.addEventListener('focus',function(){if(input.value.trim().length>=2)search();});"
                + "document.addEventListener('click',function(e){if(!form.contains(e.target))hide();});"
                + "form.addEventListener('submit',function(e){"
                + "if(!hidden.value){e.preventDefault();input.focus();}"
                + "});"
                + "})();</script>";
    }

    /* ============================ Categories ============================ */

    public void categoriesPage(Context ctx) throws Exception {
        List<TicketCategory> cats = service.categories().listAll();
        var categoryOpts = lookup.channelCategories();
        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Ticket categories · Warden", "tickets", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">Ticket categories</h1>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/tickets\">Back to list</a>");
        h.append("</div>");
        h.append("<p class=\"text-secondary\">Categories appear as buttons on the panel. Each can override "
                + "the global delivery mode and the parent Discord category that ticket channels are created under.</p>");

        h.append("<div class=\"table-responsive\"><table class=\"table align-middle\">");
        h.append("<thead><tr><th>Slug</th><th>Name</th><th>Description</th><th>Emoji</th>"
                + "<th>Style</th><th>Order</th><th>Enabled</th>"
                + "<th>Mode</th><th>Discord category</th><th></th></tr></thead><tbody>");
        for (TicketCategory c : cats) {
            h.append("<tr><form method=post action=\"/dash/tickets/categories/").append(c.id()).append("\">");
            h.append("<td><code>").append(Layout.escape(c.slug())).append("</code></td>");
            h.append("<td><input class=form-control form-control-sm name=name value=\"")
                    .append(Layout.escape(c.name())).append("\"></td>");
            h.append("<td><input class=form-control form-control-sm name=description value=\"")
                    .append(Layout.escape(c.description())).append("\"></td>");
            h.append("<td><input class=form-control form-control-sm name=emoji value=\"")
                    .append(Layout.escape(c.emoji())).append("\" placeholder=\":bulb: or <:x:1>\"></td>");
            h.append("<td>").append(styleSelect("button_style", c.buttonStyle())).append("</td>");
            h.append("<td style=\"max-width:6rem\"><input class=form-control form-control-sm type=number name=sort_order value=\"")
                    .append(c.sortOrder()).append("\"></td>");
            h.append("<td><div class=\"form-check\"><input class=form-check-input type=checkbox name=enabled value=1")
                    .append(c.enabled() ? " checked" : "").append("></div></td>");
            h.append("<td>").append(modeSelect("delivery_mode", c.normalisedDeliveryMode(), true)).append("</td>");
            h.append("<td>").append(GuildLookup.selectInline("channel_category_id",
                            GuildLookup.withDefaults(categoryOpts, c.channelCategoryId()),
                            c.channelCategoryId(), "form-select form-select-sm", "(inherit)"))
                    .append("</td>");
            h.append("<td class=\"d-flex gap-1\">");
            h.append("<button class=\"btn btn-sm btn-outline-primary\">Save</button>");
            h.append("</form>");
            h.append("<form method=post action=\"/dash/tickets/categories/").append(c.id())
                    .append("/delete\" class=m-0 data-confirm=\"Delete this ticket category?\" data-confirm-kind=\"danger\">")
                    .append("<button class=\"btn btn-sm btn-outline-danger ms-1\">Delete</button></form>");
            h.append("</td>");
            h.append("</tr>");
        }
        h.append("</tbody></table></div>");

        h.append("<h2 class=\"h5 mt-4\">Add category</h2>");
        h.append("<form method=post action=\"/dash/tickets/categories\" class=\"row g-2\">");
        h.append("<div class=col-md-2><input class=form-control name=slug placeholder=\"slug\" required></div>");
        h.append("<div class=col-md-2><input class=form-control name=name placeholder=\"Name\" required></div>");
        h.append("<div class=col-md-2><input class=form-control name=description placeholder=\"Description\"></div>");
        h.append("<div class=col-md-1><input class=form-control name=emoji placeholder=\"emoji\"></div>");
        h.append("<div class=col-md-2>").append(styleSelect("button_style", null)).append("</div>");
        h.append("<div class=col-md-1>").append(modeSelect("delivery_mode", TicketCategory.MODE_INHERIT, true)).append("</div>");
        h.append("<div class=col-md-1>").append(GuildLookup.selectInline("channel_category_id",
                        categoryOpts, null, "form-select", "(inherit)")).append("</div>");
        h.append("<div class=col-md-1><button class=\"btn btn-primary w-100\">Add</button></div>");
        h.append("</form>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    private static String styleSelect(String name, String selected) {
        String s = selected == null ? "SECONDARY" : selected.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        out.append("<select class=\"form-select form-select-sm\" name=").append(name).append(">");
        for (String opt : List.of("PRIMARY", "SECONDARY", "SUCCESS", "DANGER")) {
            out.append("<option value=\"").append(opt).append("\"")
                    .append(opt.equals(s) ? " selected" : "")
                    .append(">").append(opt).append("</option>");
        }
        out.append("</select>");
        return out.toString();
    }

    private static String modeSelect(String name, String selected, boolean includeInherit) {
        StringBuilder out = new StringBuilder();
        out.append("<select class=\"form-select form-select-sm\" name=").append(name).append(">");
        if (includeInherit) {
            out.append("<option value=\"inherit\"")
                    .append("inherit".equalsIgnoreCase(selected) ? " selected" : "")
                    .append(">Inherit default</option>");
        }
        out.append("<option value=\"dm\"")
                .append("dm".equalsIgnoreCase(selected) ? " selected" : "")
                .append(">DM only</option>");
        out.append("<option value=\"channel\"")
                .append("channel".equalsIgnoreCase(selected) ? " selected" : "")
                .append(">Create channel</option>");
        out.append("</select>");
        return out.toString();
    }

    public void categoryCreate(Context ctx) throws Exception {
        String slug = ctx.formParam("slug");
        String name = ctx.formParam("name");
        if (slug == null || slug.isBlank() || name == null || name.isBlank()) {
            ctx.redirect("/dash/tickets/categories");
            return;
        }
        service.categories().create(
                slug.trim(),
                name,
                ctx.formParam("description"),
                ctx.formParam("emoji"),
                ctx.formParam("button_style"),
                intOr(ctx, "sort_order", 100),
                true,
                ctx.formParam("delivery_mode"),
                ctx.formParam("channel_category_id"));
        ctx.redirect("/dash/tickets/categories");
    }

    public void categoryUpdate(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.categories().update(id,
                ctx.formParam("name"),
                ctx.formParam("description"),
                ctx.formParam("emoji"),
                ctx.formParam("button_style"),
                intOr(ctx, "sort_order", 0),
                "1".equals(ctx.formParam("enabled")),
                ctx.formParam("delivery_mode"),
                ctx.formParam("channel_category_id"));
        ctx.redirect("/dash/tickets/categories");
    }

    public void categoryDelete(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.categories().delete(id);
        ctx.redirect("/dash/tickets/categories");
    }

    /* ============================ Panels ============================ */

    public void panelsPage(Context ctx) throws Exception {
        List<TicketPanel> panels = service.panels().listAll();
        var channelOpts = lookup.textChannels();
        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Ticket panels · Warden", "tickets", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">Ticket panels</h1>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/tickets\">Back to list</a>");
        h.append("</div>");
        h.append("<p class=\"text-secondary\">Panels are the Discord message users click to open a ticket. "
                + "You can also create one in-Discord with <code>/ticket-panel</code>.</p>");

        h.append("<form method=post action=\"/dash/tickets/panels\" class=\"row g-2 mb-4 align-items-end\">");
        h.append("<div class=col-md-3><label class=form-label>Channel</label>")
                .append(GuildLookup.selectInline("channel_id", channelOpts, null, "form-select", "(select channel)"))
                .append("</div>");
        h.append("<div class=col-md-3><label class=form-label>Title</label><input class=form-control name=title value=\"Open a ticket\"></div>");
        h.append("<div class=col-md-4><label class=form-label>Description</label>"
                + "<input class=form-control name=description value=\"Pick a category below to open a ticket.\"></div>");
        h.append("<div class=col-md-2><button class=\"btn btn-primary w-100\">Create</button></div>");
        h.append("</form>");

        for (TicketPanel p : panels) {
            h.append("<div class=\"card mb-3\"><div class=\"card-body\">");
            h.append("<h2 class=\"h5\">Panel #").append(p.id()).append("</h2>");
            h.append("<form method=post action=\"/dash/tickets/panels/").append(p.id()).append("\" class=\"row g-2\">");
            h.append("<div class=col-md-3><label class=form-label>Channel</label>")
                    .append(GuildLookup.selectInline("channel_id",
                            GuildLookup.withDefaults(channelOpts, p.channelId()), p.channelId(),
                            "form-select", "(select channel)"))
                    .append("</div>");
            h.append("<div class=col-md-3><label class=form-label>Title</label><input class=form-control name=title value=\"")
                    .append(Layout.escape(p.title())).append("\"></div>");
            h.append("<div class=col-md-4><label class=form-label>Description</label><input class=form-control name=description value=\"")
                    .append(Layout.escape(p.description())).append("\"></div>");
            h.append("<div class=col-md-2><label class=form-label>Color</label><input class=form-control type=color name=color_hex value=\"")
                    .append(Layout.escape(p.colorHex())).append("\"></div>");
            h.append("<div class=col-12><button class=\"btn btn-sm btn-outline-primary\">Save</button></div>");
            h.append("</form>");
            h.append("<div class=\"mt-3 d-flex gap-2 align-items-center\">");
            h.append("<form method=post action=\"/dash/tickets/panels/").append(p.id()).append("/post\" class=m-0>")
                    .append("<button class=\"btn btn-sm btn-primary\">Post / refresh in Discord</button></form>");
            if (p.messageId() != null && !p.messageId().isBlank()
                    && p.channelId() != null && !p.channelId().isBlank()) {
                String msgUrl = "https://discord.com/channels/" + Layout.escape(lookup.guildId())
                        + "/" + Layout.escape(p.channelId()) + "/" + Layout.escape(p.messageId());
                h.append("<a class=\"text-secondary small\" target=_blank rel=noopener href=\"")
                        .append(msgUrl).append("\">Posted in Discord <i class=\"bi bi-box-arrow-up-right\"></i></a>");
            }
            h.append("<form method=post action=\"/dash/tickets/panels/").append(p.id())
                    .append("/delete\" class=\"m-0 ms-auto\" data-confirm=\"Delete this ticket panel?\" data-confirm-kind=\"danger\">")
                    .append("<button class=\"btn btn-sm btn-outline-danger\">Delete</button></form>");
            h.append("</div>");
            h.append("</div></div>");
        }

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void panelCreate(Context ctx) throws Exception {
        String channelId = ctx.formParam("channel_id");
        if (channelId == null || channelId.isBlank()) {
            ctx.redirect("/dash/tickets/panels");
            return;
        }
        service.panels().create(channelId,
                ctx.formParam("title"),
                ctx.formParam("description"),
                "#5865F2");
        ctx.redirect("/dash/tickets/panels");
    }

    public void panelUpdate(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.panels().update(id,
                ctx.formParam("channel_id"),
                ctx.formParam("title"),
                ctx.formParam("description"),
                ctx.formParam("color_hex"));
        ctx.redirect("/dash/tickets/panels");
    }

    public void panelDelete(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        service.panels().delete(id);
        ctx.redirect("/dash/tickets/panels");
    }

    public void panelPost(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        if (discord != null && discord.jda() != null) {
            service.postOrUpdatePanel(discord.jda(), id);
        }
        ctx.redirect("/dash/tickets/panels");
    }

    /* ============================ Settings ============================ */

    public void settingsPage(Context ctx) throws Exception {
        TicketsConfig cfg = service.config().get();
        var channelOpts = lookup.textChannels();
        var categoryOpts = lookup.channelCategories();

        StringBuilder h = new StringBuilder(4096);
        h.append(Layout.head("Ticket settings · Warden", "tickets", ctx));
        h.append("<div class=\"d-flex align-items-center justify-content-between mb-3\">");
        h.append("<h1 class=\"h3 mb-0\">Ticket settings</h1>");
        h.append("<a class=\"btn btn-sm btn-outline-secondary\" href=\"/dash/tickets\">Back to list</a>");
        h.append("</div>");

        h.append("<form method=post action=\"/dash/tickets/settings\" class=\"row g-3\" style=\"max-width:760px\">");
        h.append("<div class=col-12><label class=form-label>Default delivery mode")
                .append(Layout.infoIcon("DM only keeps the conversation in the reporter's DMs. "
                        + "Create channel spins up a private text channel for each new ticket under the chosen Discord category."))
                .append("</label>")
                .append(modeSelect("default_mode", cfg.normalisedDefaultMode(), false))
                .append("</div>");

        h.append("<div class=col-12><label class=form-label>Discord category for ticket channels")
                .append(Layout.infoIcon("Required when default mode is 'Create channel'. "
                        + "Per-category overrides on the categories page can also point at a different Discord category."))
                .append("</label>")
                .append(GuildLookup.selectInline("channel_category_id",
                        GuildLookup.withDefaults(categoryOpts, cfg.channelCategoryId()),
                        cfg.channelCategoryId(), "form-select", "(none)"))
                .append("</div>");

        h.append("<div class=col-12><label class=form-label>Staff notification channel")
                .append(Layout.infoIcon("New tickets in DM mode are announced here with a link back to the dashboard."))
                .append("</label>")
                .append(GuildLookup.selectInline("staff_channel_id",
                        GuildLookup.withDefaults(channelOpts, cfg.staffChannelId()),
                        cfg.staffChannelId(), "form-select", "(none)"))
                .append("</div>");

        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=dm_open value=1 id=dm_open")
                .append(cfg.dmReporterOnOpen() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=dm_open>DM reporter when their ticket is opened</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=dm_reply value=1 id=dm_reply")
                .append(cfg.dmReporterOnReply() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=dm_reply>DM reporter when staff replies (DM-mode tickets)</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=dm_status value=1 id=dm_status")
                .append(cfg.dmReporterOnStatus() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=dm_status>DM reporter on status change</label>")
                .append("</div></div>");
        h.append("<div class=col-md-6><div class=form-check>")
                .append("<input class=form-check-input type=checkbox name=closed_lock value=1 id=closed_lock")
                .append(cfg.closedLockReplies() ? " checked" : "").append(">")
                .append("<label class=form-check-label for=closed_lock>Lock replies once a ticket is resolved or closed</label>")
                .append("</div></div>");

        h.append("<div class=col-12><label class=form-label>Acknowledgement DM template")
                .append(Layout.infoIcon("Sent to the reporter right after they submit. Leave empty to skip."))
                .append("</label>")
                .append("<textarea class=form-control name=open_ack rows=2>")
                .append(Layout.escape(cfg.openAckMessage()))
                .append("</textarea></div>");

        h.append("<div class=col-12><button class=\"btn btn-primary\">Save</button></div>");
        h.append("</form>");

        h.append(Layout.foot());
        ctx.html(h.toString());
    }

    public void settingsSave(Context ctx) throws Exception {
        String defaultMode = ctx.formParam("default_mode");
        if (defaultMode == null || (!"dm".equalsIgnoreCase(defaultMode) && !"channel".equalsIgnoreCase(defaultMode))) {
            defaultMode = TicketsConfig.MODE_DM;
        }
        TicketsConfig cfg = new TicketsConfig(
                ctx.formParam("staff_channel_id"),
                "1".equals(ctx.formParam("dm_open")),
                "1".equals(ctx.formParam("dm_reply")),
                "1".equals(ctx.formParam("dm_status")),
                ctx.formParam("open_ack"),
                "1".equals(ctx.formParam("closed_lock")),
                defaultMode.toLowerCase(Locale.ROOT),
                ctx.formParam("channel_category_id"));
        service.config().save(cfg);
        ctx.redirect("/dash/tickets/settings");
    }

    /* ============================ Helpers ============================ */

    private static int intOr(Context ctx, String key, int fallback) {
        try { return Integer.parseInt(ctx.formParam(key)); }
        catch (Exception e) { return fallback; }
    }
}
