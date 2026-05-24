# Dashboard: Tickets

`/dash/tickets` is the support ticket workflow. Members open tickets via Discord (button or slash command); mods triage and respond from the dashboard or directly in the Discord thread. Visible to **Mod and above** for the queue; settings + panels are **Config admin**.

---

## How tickets work

1. A member clicks a button on a ticket **panel** message in your Discord guild
2. Warden creates a private channel (or thread) and pings the configured ticket role
3. The member describes the issue; mods reply in the channel; either party can close
4. On close, Warden generates an HTML transcript and DMs a public-token link to the reporter
5. The ticket is moved to the archive

---

## Dashboard surfaces

### Queue: `/dash/tickets`

Filter by status (open / closed / archived), sort by creation, last reply, or priority. Each row links to a detail page.

### Detail: `/dash/tickets/{id}`

- Full conversation log (mirrored from the Discord channel)
- Sidebar: status, assignee, category, mode, priority, attached notes
- Quick actions: assign, change category, change priority, close, reopen
- Internal-only notes that are not posted back to Discord

### Categories: `/dash/tickets/categories`

Categories group tickets by type (bug, support, appeal, etc.). Each category has:

- Name
- Description shown to members opening a ticket
- Default ticket role(s) pinged
- Channel category (where new tickets are created)
- Optional auto-close after N days of inactivity

### Panels: `/dash/tickets/panels`

A panel is the Discord message with buttons that members click to open a ticket. Each panel has:

- Title + body + colour
- One or more buttons, each tied to a category
- Target channel (where the panel message is posted)
- Required role gate (only members with this role can open tickets)

### Settings: `/dash/tickets/settings`

- Enable / disable the ticket system
- Default ticket role pinged when no category-specific role is set
- Maximum open tickets per member
- Transcript retention period (default: 30 days)
- Whether to mirror ticket channels to a logging channel for off-Discord access

---

## Transcripts

On ticket close, Warden generates an HTML transcript of the full conversation including attachments. The reporter receives a DM with a tokenised link to `/tickets/transcript/{token}`; the token is unguessable and expires per the retention setting. Attachments are served from `/tickets/transcript-asset/{ticketId}/{messageId}/{name}` with the same token gate.

The transcript page does not require a dashboard session; the token is the credential. Useful for users who do not have any dashboard role but want a record of their interaction.

---

## Modes

Tickets can be opened in two modes:

- **Private channel**: a new channel is created, accessible only to the reporter and the ticket role. The default.
- **Thread**: a thread is opened off a parent channel, again role-restricted. Useful for high-volume tickets where channel-list clutter is a concern.

Configurable per category.
