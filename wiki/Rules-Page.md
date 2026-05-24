# Rules Page

The rules are the first thing a new member sees in the onboarding flow. They are stored as Markdown, edited on `/dash/config` -> **Rules** tab, and rendered as HTML at two points:

- During onboarding (DM or web) before any questions are asked
- On the public `/rules` page, inside the same landing chrome as `/`

---

## Editing

The Rules editor is a plain textarea with a Markdown preview. Supported subset:

- Headings (`#`, `##`, `###`)
- Bold (`**bold**`), italic (`*italic*`)
- Bullet lists (`-` or `*`)
- Numbered lists (`1.`, `2.`)
- Inline code (`` `code` ``) and fenced code blocks
- Links (`[label](https://example.com)`)
- Horizontal rules (`---`)
- Blockquotes (`>`)

Everything is rendered through the in-house MarkdownLite parser; no HTML injection through user input. If you need richer rendering, edit `plugins/Warden/www/rules.html` directly.

---

## Polish with AI

The rules textarea has a **Polish with AI** button. It rewrites the rules using your configured AI gateway while preserving the structure. CTRL+Z restores the original. See [AI Assistance](AI-Assistance.md).

---

## Acceptance gate

In the onboarding flow, the member must click **I agree** before they can answer any questions. Disagreeing terminates the flow without recording a denial; they can try again later. See [Onboarding Flow](Onboarding-Flow.md).

---

## Notes

- The same Markdown text powers both `/rules` (public, no session required) and the in-flow rules step (gated to the joining member). One edit; both update.
- The `/rules` page is rate-limited at the same `/onboard/*` cap (20 / minute per IP). See [Rate Limits](Rate-Limits.md).
- Linking to `/rules` directly from elsewhere on your site is fine; it does not require a session.
