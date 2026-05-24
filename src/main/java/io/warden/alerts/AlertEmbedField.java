package io.warden.alerts;

/** One field inside an alert's optional Discord embed. Stored as a JSON array on the alert row. */
public record AlertEmbedField(String name, String value, boolean inline) {

    public AlertEmbedField {
        if (name == null) name = "";
        if (value == null) value = "";
    }
}
