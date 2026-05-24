package io.warden.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.warden.onboarding.model.AnswerValue;
import io.warden.onboarding.model.LandingFaq;
import io.warden.onboarding.model.LandingFeature;

import java.util.ArrayList;
import java.util.List;

/** Tiny Jackson helpers used by DAOs. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<String> out = new ArrayList<>(n.size());
            for (JsonNode el : n) {
                if (el.isTextual()) out.add(el.asText());
                else out.add(el.toString());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String writeStringList(List<String> values) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (String v : values) arr.add(v);
        return arr.toString();
    }

    /** Round-trip an AnswerValue through the JSON form used in the answers table. */
    public static String writeAnswer(AnswerValue v) {
        if (v instanceof AnswerValue.Single s) {
            return MAPPER.valueToTree(s.value() == null ? "" : s.value()).toString();
        }
        if (v instanceof AnswerValue.Multi m) {
            return writeStringList(m.values());
        }
        return "\"\"";
    }

    public static AnswerValue readAnswer(String json) {
        if (json == null || json.isBlank()) return AnswerValue.of("");
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n.isArray()) {
                List<String> out = new ArrayList<>(n.size());
                for (JsonNode el : n) out.add(el.isTextual() ? el.asText() : el.toString());
                return AnswerValue.of(out);
            }
            if (n.isTextual()) return AnswerValue.of(n.asText());
            return AnswerValue.of(n.toString());
        } catch (Exception e) {
            return AnswerValue.of(json);
        }
    }

    public static List<LandingFeature> readFeatureList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<LandingFeature> out = new ArrayList<>(n.size());
            for (JsonNode el : n) {
                if (!el.isObject()) continue;
                out.add(new LandingFeature(
                        textOrEmpty(el, "icon"),
                        textOrEmpty(el, "title"),
                        textOrEmpty(el, "body")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String writeFeatureList(List<LandingFeature> values) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (values != null) {
            for (LandingFeature f : values) {
                if (f == null) continue;
                ObjectNode o = arr.addObject();
                o.put("icon",  f.icon()  == null ? "" : f.icon());
                o.put("title", f.title() == null ? "" : f.title());
                o.put("body",  f.body()  == null ? "" : f.body());
            }
        }
        return arr.toString();
    }

    public static List<LandingFaq> readFaqList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<LandingFaq> out = new ArrayList<>(n.size());
            for (JsonNode el : n) {
                if (!el.isObject()) continue;
                out.add(new LandingFaq(
                        textOrEmpty(el, "question"),
                        textOrEmpty(el, "answer")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String writeFaqList(List<LandingFaq> values) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (values != null) {
            for (LandingFaq q : values) {
                if (q == null) continue;
                ObjectNode o = arr.addObject();
                o.put("question", q.question() == null ? "" : q.question());
                o.put("answer",   q.answer()   == null ? "" : q.answer());
            }
        }
        return arr.toString();
    }

    private static String textOrEmpty(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    public static String writeObject(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
