# AI Assistance

Warden integrates with any OpenAI-Responses-compatible LLM gateway for three optional features. The integration is configured on `/dash/config` -> **AI** tab and the gateway is called over HTTPS; nothing leaves your server unless you fill in the key.

---

## Compatible gateways

Anything that speaks `POST /v1/responses` with Bearer auth:

- Official OpenAI API
- [Manifest](https://app.manifest.build)
- OpenRouter
- A local Ollama instance with the OpenAI-compat shim enabled
- Anything else that implements the Responses API

Configure on the AI tab:

- **API key**: your provider's bearer token
- **Base URL**: e.g. `https://api.openai.com`, `https://api.manifest.build`, `http://localhost:11434`
- **Model**: e.g. `gpt-4o-mini`, `claude-opus-4-7`, `llama3.3`

---

## Test connection

Click **Test connection** on the AI tab without saving. Warden asks the gateway for a joke, displays the latency + response, and tells you exactly what failed if it did. Useful for diagnosing 401s, model-not-found errors, and base-URL typos.

---

## Feature 1: LLM triage

When the triage mode on `/dash/config` -> **Triage** tab is set to **LLM auto** or **LLM only**, the LLM is asked for an approve / deny / escalate verdict on each submitted application.

The prompt includes:

- Your rules (Markdown)
- The questions you asked
- The answers the applicant gave
- A schema the LLM must respond in (`{ "verdict": "...", "confidence": 0.0-1.0, "reason": "..." }`)

Warden parses the response, applies the verdict (or escalates on uncertainty), and stores the LLM reasoning alongside the application so mods see it on `/dash/pending`. See [Onboarding Flow](Onboarding-Flow.md).

Failure handling:

- Transient errors (429, 5xx, IO) retry with exponential backoff + full jitter (3 retries, 500 ms base, 8 s cap)
- Persistent failures **escalate** the application; never silently lose it
- 4xx config errors fail fast and surface in the audit log

---

## Feature 2: Polish with AI

Every editable public-facing string has a small AI button next to it. Clicking it asks the LLM to rewrite the text in a more polished form while preserving meaning, then replaces the text in place.

Strings with Polish buttons:

- Rules (Markdown)
- Welcome message templates (Delivery tab)
- Approval / denial DM templates (Approve / Deny tabs)
- Landing page tagline
- Each FAQ question and answer
- Each feature card title and body

CTRL+Z works: the replacement uses `execCommand('insertText')` so the browser's native undo stack restores the original wording in one keystroke.

---

## Feature 3: Generate feature cards from installed plugins

A button at the top of the feature-cards editor (on the Landing tab) asks the LLM to draft N feature cards based on the actual plugins installed on your server, read via Bukkit's `PluginManager`.

Options:

- **Count**: 1 to 12
- **Replace existing**: if checked, clears the current cards before inserting; if unchecked, appends

The result lands in the editor for you to edit, polish, reorder, or delete before saving.

---

## Privacy

Nothing about your server is sent to the gateway unless you call one of the three features. Triage sends rules + questions + answers; Polish sends the text being polished; Generate sends a list of plugin names + descriptions. No member identities, no audit log, no Minecraft data, no Discord messages.

---

## Cost ballpark

Triage averages 1-2k tokens per application (rules + questions + answers in, structured verdict out). Polish averages 200-1000 tokens per call. Generate averages 2-5k tokens per run (plugin list in, card list out).

At GPT-4o-mini rates, a busy server is in the cents-per-month range. At Claude Opus rates, more like dollars-per-month. Pick the cheapest model that gives acceptable results.
