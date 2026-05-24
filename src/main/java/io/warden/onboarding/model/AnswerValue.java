package io.warden.onboarding.model;

import java.util.List;

/**
 * Sum type for an answer value: a single string or a list of strings.
 * Stored as JSON in the DB so the shape can round-trip without loss.
 */
public sealed interface AnswerValue {
    record Single(String value) implements AnswerValue {}
    record Multi(List<String> values) implements AnswerValue {}

    static AnswerValue of(String s) { return new Single(s); }
    static AnswerValue of(List<String> vs) { return new Multi(List.copyOf(vs)); }

    default String asSingle() {
        if (this instanceof Single s) return s.value;
        throw new IllegalStateException("Not a single-value answer");
    }
    default List<String> asMulti() {
        if (this instanceof Multi m) return m.values;
        throw new IllegalStateException("Not a multi-value answer");
    }
    default boolean isEmpty() {
        if (this instanceof Single s) return s.value == null || s.value.isBlank();
        if (this instanceof Multi m) return m.values.isEmpty();
        return true;
    }
    default String display() {
        if (this instanceof Single s) return s.value == null ? "" : s.value;
        return String.join(", ", ((Multi) this).values);
    }
}
