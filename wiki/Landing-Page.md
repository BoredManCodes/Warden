# Landing Page

The public landing site lives at `/` and is the front door for visitors who do not have a Discord OAuth session. It is highly customisable: editable HTML, CSS, hero image, brand image, accent colour, stats counters, FAQ, and feature cards.

---

## The three public pages

| Path | Source | What it is |
|---|---|---|
| `/` | `plugins/Warden/www/landing.html` | Landing template. Hero, stats strip, features, community CTA, FAQ. |
| `/rules` | `/dash/config` -> Rules tab (Markdown) | Same Markdown rendered as HTML inside the shared landing chrome. See [Rules Page](Rules-Page.md). |
| `/map` | `plugins/Warden/www/map.html` | Iframe embed of whichever web map plugin you run. See [Map Page](Map-Page.md). |

A fourth route, `/leaderboard`, serves a public stats page if you enable it. See [Leaderboard Page](Leaderboard-Page.md).

---

## The Landing tab on /dash/config

This is where almost everything is edited. Reachable by Owner, Config admin, and Web manager.

### Mode

- **Enabled**: serve the landing template at `/`
- **Disabled**: serve a 404 at `/`; useful if you do not want a public site
- **Redirect**: 302 to a URL you supply, e.g. your existing community website

### Identity

- **Server name**: shown in the hero and the browser title
- **Tagline**: one-liner shown below the server name
- **Server address**: e.g. `mc.example.com`. Copyable on the landing page.
- **Join button URL**: where the **Join** call-to-action button goes (typically your Discord invite or your server browser entry)

### Map

- **Map provider**: free-text label (Dynmap, Pl3xMap, BlueMap, squaremap, "Custom")
- **Map URL**: the iframe `src` for the `/map` page
- **Nav link label**: what the top nav shows for the map link

### Branding

- **Brand image URL**: a small icon used in the nav and the favicon. Falls back to your Discord guild icon if blank, then to the bundled Warden icon.
- **Hero image URL**: a full-bleed background of the hero section with a dark scrim, replacing the default grid pattern
- **Accent colour**: hex picker; replaces the `--accent` CSS variable in `landing.css`

### Stats

The landing page shows two live stat counters by default: "Players online" and "Discord members". You can:

- Rename either label
- Override the value with a PlaceholderAPI placeholder (e.g. `%server_online%`, `%bedwars_active_games%`)

Warden runs fine without PAPI installed: missing placeholders silently fall back to the built-in value. See [PlaceholderAPI bridge in the README](https://github.com/your-org/warden) for the full list.

### Feature cards

A reorderable list of cards (icon + title + body). Use this for highlighting plugin features, community values, anything you want the front page to surface.

The Generate-features-from-plugins button at the top of this list calls your AI gateway with the list of plugins installed on your server and asks for N draft cards. You then edit / polish / reorder before saving. See [AI Assistance](AI-Assistance.md).

### FAQ

A reorderable list of question / answer pairs. Rendered as an accordion on the landing page. Each entry has a **Polish with AI** button next to both the question and the answer.

### Promo video

Optional embed. Paste a YouTube / Vimeo URL; the landing template renders an iframe. Leave blank to omit the video block.

### Google Analytics

Optional. Paste your GA4 measurement id; the landing template emits the tag. Leave blank to skip.

---

## Template fields reference

A collapsible panel at the top of the Landing tab lists every `{{...}}` placeholder the renderer supports, grouped by which file consumes it. Copy-paste straight into your template; no docs round-trip required.

The reference is generated from the actual placeholder map at runtime, so it always matches what the renderer accepts.

---

## Bringing your own template

`plugins/Warden/www/` is unpacked on first run with the bundled templates and CSS, then **never overwritten**. Edit anything you want without losing your changes on upgrade. Drop in a fully custom `landing.html`, `landing.css`, and any assets; the files are served at `/www/*`.

Reference any of the `{{...}}` placeholders or skip the ones you do not need. Unknown tokens render literally, so typos surface immediately.

If you upgrade from an older Warden and the landing page is not picking up a new feature (e.g. `{{hero_image_block}}`), your template predates the placeholder. Either:

- Delete `landing.html` (and `landing.css` if styling looks off) and restart to get fresh defaults
- Open the file and add the placeholder manually using the Template fields reference

---

## Polish with AI

Every editable string on the Landing tab has a small AI button next to it: FAQ question, FAQ answer, feature title, feature body. Clicking it replaces the text in place with a polished version.

CTRL+Z works: the replacement uses `execCommand('insertText')` so the browser's native undo stack restores the original wording.

See [AI Assistance](AI-Assistance.md) for the full feature set.
