package kr.lunaf.varstore.codec;

import java.math.BigDecimal;
import java.util.*;

/** Immutable JSON data only. No class names, reflective object construction or Java serialization. */
public sealed interface JsonValue permits JsonValue.ObjectValue, JsonValue.ArrayValue,
        JsonValue.StringValue, JsonValue.NumberValue, JsonValue.BooleanValue, JsonValue.NullValue {
    record ObjectValue(Map<String, JsonValue> fields) implements JsonValue {
        public ObjectValue {
            Objects.requireNonNull(fields);
            // Object member order is not JSON data. Canonical ordering keeps the
            // prepared STRING/fingerprint stable across map types and JVM hash seeds.
            SortedMap<String, JsonValue> snapshot = new TreeMap<>();
            fields.forEach((key, value) -> snapshot.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
            fields = Collections.unmodifiableMap(snapshot);
        }
    }
    record ArrayValue(List<JsonValue> values) implements JsonValue {
        public ArrayValue { values = List.copyOf(values); }
    }
    record StringValue(String value) implements JsonValue {
        public StringValue { Objects.requireNonNull(value); }
    }
    record NumberValue(BigDecimal value) implements JsonValue {
        public NumberValue {
            Objects.requireNonNull(value);
            if (value.precision() > 16_384 || Math.abs((long) value.scale()) > 10_000)
                throw new IllegalArgumentException("JSON number is too large");
        }
    }
    record BooleanValue(boolean value) implements JsonValue {}
    enum NullValue implements JsonValue { INSTANCE }

    static ObjectValue object(Map<String, JsonValue> fields) { return new ObjectValue(fields); }
    static ArrayValue array(List<JsonValue> values) { return new ArrayValue(values); }
    static StringValue string(String value) { return new StringValue(value); }
    static NumberValue number(long value) { return new NumberValue(BigDecimal.valueOf(value)); }
    static NumberValue number(BigDecimal value) { return new NumberValue(value); }
    static BooleanValue bool(boolean value) { return new BooleanValue(value); }
    static NullValue nil() { return NullValue.INSTANCE; }
}
