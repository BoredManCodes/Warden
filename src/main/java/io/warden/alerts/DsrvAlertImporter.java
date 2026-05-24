package io.warden.alerts;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Parses a DSRV-format alert (or list of alerts, or a full
 * {@code alerts.yml} with an {@code Alerts:} wrapper) into Warden
 * {@link Alert} records.
 *
 * Field mapping (DSRV → Warden):
 * <ul>
 *   <li>{@code Trigger} → {@link Alert#triggerClass()} (FQCN or short name)</li>
 *   <li>{@code Conditions} (list) → joined with newlines into {@link Alert#conditions()}</li>
 *   <li>{@code Channel} → not auto-mapped (DSRV uses channel <i>names</i> we don't index;
 *       we stash the value as a {@code # Channel: NAME} hint at the top of the
 *       conditions block so the operator can pick the matching ID after import)</li>
 *   <li>{@code Async} → {@link Alert#asyncDispatch()}</li>
 *   <li>{@code Content} → {@link Alert#messageContent()}</li>
 *   <li>{@code Embed} subtree → embed fields ({@code Title.Text}, {@code Color},
 *       {@code Description}, {@code Thumbnail.Url}, {@code Image.Url},
 *       {@code Footer.Text}, {@code Fields}[].(Name|Value|Inline))</li>
 * </ul>
 *
 * Imported alerts always have {@link Alert#expressionsEnabled()} = true,
 * because DSRV templates use SpEL inside braces.
 *
 * Common DSRV root shortcuts (when expressions are enabled) like
 * {@code user.name}, {@code user.world}, {@code user.uuid} are passed through
 * unchanged - the SpEL context wired up by {@link AlertService} exposes a
 * {@code user} root that resolves to the Bukkit Player on player-class events,
 * which is structurally compatible with DSRV's player roots.
 */
public final class DsrvAlertImporter {

    private DsrvAlertImporter() {}

    public record Result(List<Alert> alerts, List<String> warnings) {}

    /**
     * Resolve a DSRV channel-name (the keys under {@code Channels:} in its
     * config.yml) to a Discord channel snowflake id. The importer falls back
     * to leaving the alert's channel id blank when this returns empty, and
     * always also adds a {@code # DSRV Channel: NAME} hint to the conditions
     * block so a human can still tell where it came from.
     */
    @FunctionalInterface
    public interface ChannelResolver extends Function<String, Optional<String>> {}

    /** Parse a YAML blob. Throws if the YAML is malformed. */
    public static Result parse(String yamlText) {
        return parse(yamlText, name -> Optional.empty());
    }

    public static Result parse(String yamlText, ChannelResolver channelResolver) {
        if (yamlText == null || yamlText.isBlank()) {
            return new Result(List.of(), List.of("Pasted text is empty."));
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root;
        try {
            root = yaml.load(yamlText);
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML parse failed: " + e.getMessage(), e);
        }

        List<Map<String, Object>> rawAlerts = extractAlertList(root);
        List<String> warnings = new ArrayList<>();
        List<Alert> out = new ArrayList<>();

        ChannelResolver resolver = channelResolver != null ? channelResolver : name -> Optional.empty();

        int idx = 0;
        for (Map<String, Object> raw : rawAlerts) {
            idx++;
            try {
                Alert a = convert(raw, idx, warnings, resolver);
                if (a != null) out.add(a);
            } catch (Exception e) {
                warnings.add("Alert #" + idx + " could not be converted: " + e.getMessage());
            }
        }

        if (out.isEmpty() && warnings.isEmpty()) {
            warnings.add("No alerts were found in the pasted YAML. Expected a Trigger key, "
                    + "a list of alerts, or a top-level 'Alerts:' wrapper.");
        }
        return new Result(out, warnings);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractAlertList(Object root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null) return out;
        // Full alerts.yml: top-level map with an "Alerts:" list.
        if (root instanceof Map<?, ?> rootMap) {
            // Case A: this map IS a single alert (has Trigger).
            if (rootMap.containsKey("Trigger") || rootMap.containsKey("trigger")) {
                out.add((Map<String, Object>) rootMap);
                return out;
            }
            // Case B: full alerts.yml shape.
            Object wrapper = rootMap.get("Alerts");
            if (wrapper == null) wrapper = rootMap.get("alerts");
            if (wrapper instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                }
                return out;
            }
            // Case C: map of name→alert.
            for (var e : rootMap.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m && (m.containsKey("Trigger") || m.containsKey("trigger"))) {
                    Map<String, Object> mm = new LinkedHashMap<>((Map<String, Object>) m);
                    mm.putIfAbsent("Name", String.valueOf(e.getKey()));
                    out.add(mm);
                }
            }
            return out;
        }
        // Bare list of alerts.
        if (root instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Alert convert(Map<String, Object> raw, int idx, List<String> warnings,
                                 ChannelResolver channelResolver) {
        String trigger = strOr(raw, "Trigger", "trigger", "");
        if (trigger.isBlank()) {
            warnings.add("Alert #" + idx + ": missing Trigger - skipped.");
            return null;
        }

        String name = strOr(raw, "Name", "name",
                "Imported - " + shortName(trigger));

        String channelHint = strOr(raw, "Channel", "channel", "");
        String resolvedChannelId = "";
        if (!channelHint.isBlank()) {
            // Snowflakes pass through as-is; named entries get resolved via DSRV's config.
            if (channelHint.matches("\\d{16,21}")) {
                resolvedChannelId = channelHint;
            } else {
                Optional<String> looked = channelResolver.apply(channelHint);
                if (looked.isPresent() && !looked.get().isBlank()) {
                    resolvedChannelId = looked.get();
                } else {
                    warnings.add("Alert #" + idx + " (\"" + name + "\"): DSRV channel \"" + channelHint
                            + "\" couldn't be resolved (DiscordSRV may not be installed, or its "
                            + "config.yml has no matching entry under Channels:). Pick the channel "
                            + "from the dropdown after import.");
                }
            }
        }

        List<String> conditionLines = new ArrayList<>();
        if (!channelHint.isBlank()) conditionLines.add("# DSRV Channel: " + channelHint);
        Object condRaw = raw.getOrDefault("Conditions", raw.get("conditions"));
        if (condRaw instanceof List<?> condList) {
            for (Object c : condList) {
                if (c == null) continue;
                conditionLines.add(c.toString());
            }
        } else if (condRaw instanceof String s && !s.isBlank()) {
            conditionLines.add(s);
        }
        String conditions = String.join("\n", conditionLines);

        boolean async = boolOr(raw, "Async", "async", false);

        String content = strOr(raw, "Content", "content", "");

        boolean embedEnabled = false;
        String eTitle = "", eDesc = "", eColor = "#5865F2";
        String eThumb = "", eImage = "", eFooter = "";
        String eAuthorName = "", eAuthorIcon = "";
        List<AlertEmbedField> embedFields = new ArrayList<>();

        Object embedRaw = raw.getOrDefault("Embed", raw.get("embed"));
        if (embedRaw instanceof Map<?, ?> embedMap0) {
            embedEnabled = true;
            Map<String, Object> embed = (Map<String, Object>) embedMap0;

            Object title = embed.getOrDefault("Title", embed.get("title"));
            if (title instanceof Map<?, ?> tm) {
                eTitle = strOr((Map<String, Object>) tm, "Text", "text", "");
            } else if (title instanceof String s) {
                eTitle = s;
            }

            Object color = embed.getOrDefault("Color", embed.get("color"));
            eColor = coerceColor(color, eColor);

            Object desc = embed.getOrDefault("Description", embed.get("description"));
            eDesc = (desc == null) ? "" : desc.toString();

            Object thumb = embed.getOrDefault("Thumbnail", embed.get("thumbnail"));
            if (thumb instanceof Map<?, ?> tm) {
                eThumb = strOr((Map<String, Object>) tm, "Url", "url", "");
            } else if (thumb instanceof String s) {
                eThumb = s;
            }

            Object image = embed.getOrDefault("Image", embed.get("image"));
            if (image instanceof Map<?, ?> im) {
                eImage = strOr((Map<String, Object>) im, "Url", "url", "");
            } else if (image instanceof String s) {
                eImage = s;
            }

            Object footer = embed.getOrDefault("Footer", embed.get("footer"));
            if (footer instanceof Map<?, ?> fm) {
                eFooter = strOr((Map<String, Object>) fm, "Text", "text", "");
            } else if (footer instanceof String s) {
                eFooter = s;
            }

            Object author = embed.getOrDefault("Author", embed.get("author"));
            if (author instanceof Map<?, ?> am) {
                Map<String, Object> amap = (Map<String, Object>) am;
                eAuthorName = strOr(amap, "Name", "name", "");
                // DSRV uses ImageUrl for the author thumbnail; older configs use IconUrl/Url.
                String icon = strOr(amap, "ImageUrl", "imageUrl", "");
                if (icon.isBlank()) icon = strOr(amap, "IconUrl", "iconUrl", "");
                if (icon.isBlank()) icon = strOr(amap, "Url", "url", "");
                eAuthorIcon = icon;
            } else if (author instanceof String s) {
                eAuthorName = s;
            }

            Object fields = embed.getOrDefault("Fields", embed.get("fields"));
            if (fields instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> fmap)) continue;
                    Map<String, Object> fm = (Map<String, Object>) fmap;
                    String fName  = strOr(fm, "Name", "name", "");
                    String fValue = strOr(fm, "Value", "value", "");
                    boolean fInline = boolOr(fm, "Inline", "inline", false);
                    embedFields.add(new AlertEmbedField(fName, fValue, fInline));
                }
            }
        }

        Object buttonsRaw = raw.getOrDefault("Buttons", raw.get("buttons"));
        if (buttonsRaw instanceof List<?> btnList && !btnList.isEmpty()) {
            warnings.add("Alert #" + idx + " (\"" + name + "\"): " + btnList.size()
                    + " Discord button(s) found in source but Warden alerts don't support "
                    + "interactive buttons yet; they were dropped. Run the command via "
                    + "Console commands or wire a separate command-driven button elsewhere.");
        }

        // Best-effort: derive a built-in event key when the trigger class matches one;
        // otherwise stash the FQCN in the event field too so the picker still has a label.
        String eventKey = "";
        for (AlertEvent ae : AlertEvent.values()) {
            if (ae.bukkitClass() != null && ae.bukkitClass().equalsIgnoreCase(trigger)) {
                eventKey = ae.key();
                break;
            }
        }
        if (eventKey.isBlank()) eventKey = "player_join"; // dummy placeholder so the picker has something

        return new Alert(
                0,
                name,
                true,                  // enabled by default after import
                eventKey,
                resolvedChannelId,     // resolved via DSRV config when possible; else blank for picker
                content,
                embedEnabled,
                eTitle,
                eDesc,
                eColor,
                eThumb,
                eImage,
                eFooter,
                eAuthorName,
                eAuthorIcon,
                embedFields,
                "",                    // console_commands - DSRV alerts don't run commands
                "",                    // asplayer_commands
                "",                    // papi_player_uuid
                trigger,               // trigger_class - this is the key bit
                conditions,
                true,                  // expressions_enabled - DSRV templates use SpEL
                async,
                0, 0);
    }

    private static String strOr(Map<String, Object> m, String primary, String alt, String fallback) {
        Object v = m.get(primary);
        if (v == null && alt != null) v = m.get(alt);
        if (v == null) return fallback;
        return v.toString();
    }

    private static boolean boolOr(Map<String, Object> m, String primary, String alt, boolean fallback) {
        Object v = m.get(primary);
        if (v == null && alt != null) v = m.get(alt);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }

    private static String coerceColor(Object raw, String fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) {
            // DSRV permits raw integer RGB
            int rgb = n.intValue() & 0xFFFFFF;
            return String.format("#%06X", rgb);
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) return fallback;
        if (s.startsWith("0x") || s.startsWith("0X")) s = "#" + s.substring(2);
        if (s.startsWith("&h") || s.startsWith("&H")) s = "#" + s.substring(2);
        if (!s.startsWith("#")) s = "#" + s;
        // Validate hex length
        String hex = s.substring(1);
        if (hex.length() == 6 && hex.matches("[0-9A-Fa-f]{6}")) return "#" + hex.toUpperCase();
        return fallback;
    }

    private static String shortName(String fqcn) {
        if (fqcn == null) return "";
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }
}
