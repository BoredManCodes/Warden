package io.warden.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.Services;
import io.warden.WardenPlugin;
import io.warden.llm.ManifestClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates, encrypts, stores, and analyses debug reports.
 *
 * Encryption: AES-256-GCM with a freshly generated key per report.
 * The key is stored in the DB (for share-link reconstruction) and
 * returned to the caller once for inclusion in the viewer URL fragment.
 * The viewer decrypts client-side so the key is never sent back to the
 * server after the initial creation response.
 *
 * LLM analysis runs asynchronously on the background executor after the
 * report is persisted. When analysis completes the plaintext is updated
 * and re-encrypted with a new IV (same key, still stored in the DB row).
 */
public final class DebugService {

    private static final String ANALYSIS_SYSTEM_PROMPT = """
You are a diagnostic AI for the Warden plugin, a Paper Minecraft server plugin that provides:
- Discord bot integration (JDA) for member onboarding and community management
- Self-hosted web dashboard (Javalin/Jetty) for server administrators
- SQLite database for persistent storage
- Optional LLM integration (OpenAI-compatible endpoint) for automated application triage
- Modular features: Moderation, Levels/XP, Reaction Roles, Polls/Giveaways, Tickets, Feedback, Alerts, Autoresponders, Event Scheduler

The debug JSON also includes a "files" array. Each entry has a "path" and "content" field containing the raw text of config files (plugins/Warden/config.yml, paper configs, spigot.yml, bukkit.yml, server.properties, other plugin configs such as GrimAC and DiscordSRV) and the last 500 lines of logs/latest.log. Use these to identify specific misconfiguration values, errors in the log, or conflicts between plugins.

Analyze the provided debug JSON and identify any errors, misconfigurations, or noteworthy items.

Common issues to look for:
- discord.ready = false → bot token wrong/missing, network issue, or guild_id mismatch
- config.discord.bot_token = "[NOT SET]" → Discord integration completely broken
- config.discord.guild_id blank → bot cannot find the server guild
- web.running = false → port conflict or bind error
- config.web.session_secret = "[NOT SET]" → web onboarding flow disabled
- llm.api_key_set = false → triage falls back to manual review only
- config.discord.client_id or client_secret blank → OAuth sign-in to dashboard disabled
- system.heap_used_mb approaching heap_max_mb → memory pressure risk

Return ONLY valid JSON (no prose, no markdown, no code fences) shaped exactly like this:
{
  "summary": "One sentence overall assessment of plugin health",
  "findings": [
    {
      "severity": "error",
      "title": "Short title under 60 chars",
      "detail": "Clear explanation of what the issue is and why it matters",
      "fix": "Specific, actionable resolution steps"
    }
  ]
}

Severity values: "error" (broken/non-functional), "warning" (suboptimal/degraded), "info" (note or confirmation).
If everything looks healthy, return an empty findings array with a positive summary.
""";

    private static final ObjectMapper MAPPER  = new ObjectMapper();
    private static final SecureRandom RANDOM  = new SecureRandom();
    private static final int          IV_LEN  = 12;
    private static final int          KEY_LEN = 32;

    private final WardenPlugin     plugin;
    private final Services         services;
    private final DebugReportDao   dao;
    private final DebugCollector   collector;
    private final Logger           log;

    public DebugService(WardenPlugin plugin, Services services, DebugReportDao dao) {
        this.plugin    = plugin;
        this.services  = services;
        this.dao       = dao;
        this.collector = new DebugCollector(plugin, services);
        this.log       = plugin.getLogger();
    }

    public record GenerateResult(String id, String keyB64Url) {}

    /**
     * Collect the current plugin state, encrypt it, persist it, and kick off
     * an async LLM analysis. Returns the report ID and the base64url decryption
     * key that should be embedded in the viewer URL fragment.
     */
    public GenerateResult generate(String label) throws Exception {
        Map<String, Object> data = collector.collect();
        String plaintext = MAPPER.writeValueAsString(data);

        byte[] keyBytes = new byte[KEY_LEN];
        RANDOM.nextBytes(keyBytes);

        String encryptedB64 = encrypt(plaintext, keyBytes);
        String keyB64Url    = Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
        String id           = UUID.randomUUID().toString().replace("-", "");

        String analysisStatus = "pending";
        try {
            var settings = services.settingsDao.get();
            if (!isSet(settings.llmApiKey())) analysisStatus = "skipped";
        } catch (Exception ignored) {}

        DebugReport report = new DebugReport(
                id, System.currentTimeMillis(),
                label == null ? "" : label.trim(),
                encryptedB64, keyB64Url, analysisStatus);
        dao.insert(report);

        if ("pending".equals(analysisStatus)) {
            String finalId = id;
            byte[] finalKey = keyBytes;
            String finalPlaintext = plaintext;
            services.bgExecutor.submit(() -> runAnalysis(finalId, finalPlaintext, finalKey));
        }

        return new GenerateResult(id, keyB64Url);
    }

    /** Returns the full viewer URL for a report (key in URL fragment). */
    public String viewerUrl(String id, String keyB64Url) {
        return services.config.webPublicUrl() + "/debug/" + id + "#k=" + keyB64Url;
    }

    public void deleteReport(String id) throws Exception {
        dao.delete(id);
    }

    // ── Async LLM analysis ────────────────────────────────────────────────────

    private void runAnalysis(String id, String plaintext, byte[] keyBytes) {
        try {
            var settings = services.settingsDao.get();
            if (!isSet(settings.llmApiKey())) {
                dao.updatePayloadAndStatus(id, dao.findById(id).encryptedPayload(), "skipped");
                return;
            }
            var endpoint = new ManifestClient.Endpoint(
                    settings.llmApiKey(), settings.llmBaseUrl(), settings.llmModel());

            String analysisJson = services.manifest.requestText(
                    endpoint, ANALYSIS_SYSTEM_PROMPT, plaintext);

            // Merge analysis into the original JSON.
            ObjectNode root = (ObjectNode) MAPPER.readTree(plaintext);
            try {
                JsonNode analysis = MAPPER.readTree(analysisJson);
                root.set("analysis", analysis);
            } catch (Exception e) {
                // LLM returned non-JSON; store raw in a text node.
                root.put("analysis_raw", analysisJson);
            }
            String updatedPlaintext = MAPPER.writeValueAsString(root);
            String newEncrypted     = encrypt(updatedPlaintext, keyBytes);

            dao.updatePayloadAndStatus(id, newEncrypted, "done");
            log.info("[debug] analysis complete for report " + id);
        } catch (Exception e) {
            log.log(Level.WARNING, "[debug] LLM analysis failed for report " + id, e);
            try {
                DebugReport existing = dao.findById(id);
                if (existing != null) {
                    dao.updatePayloadAndStatus(id, existing.encryptedPayload(), "failed");
                }
            } catch (Exception ignored) {}
        }
    }

    // ── Crypto ────────────────────────────────────────────────────────────────

    /**
     * AES-256-GCM encrypt. Returns base64( iv || ciphertext+tag ).
     * The IV is 12 bytes (standard GCM recommendation); the 128-bit auth tag
     * is appended by the JCA provider and included in the returned ciphertext.
     */
    static String encrypt(String plaintext, byte[] keyBytes) throws Exception {
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, combined, 0,       IV_LEN);
        System.arraycopy(ct, 0, combined, IV_LEN,  ct.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
