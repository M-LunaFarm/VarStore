package kr.lunaf.varstore.api;

import java.util.Objects;
import java.util.UUID;

/** A named, typed key. Prefer factories to ensure the Java and stored type agree. */
public record VarKey<T>(String name, ValueType type) {
    public VarKey { Names.key(name); Objects.requireNonNull(type, "type"); }
    public static VarKey<String> stringKey(String name) { return new VarKey<>(name, ValueType.STRING); }
    public static VarKey<Long> longKey(String name) { return new VarKey<>(name, ValueType.LONG); }
    public static VarKey<Boolean> booleanKey(String name) { return new VarKey<>(name, ValueType.BOOLEAN); }
    public static VarKey<UUID> uuidKey(String name) { return new VarKey<>(name, ValueType.UUID); }
    @SuppressWarnings("unchecked") public T cast(Object value) {
        type.validate(value);
        return (T) value;
    }
}
