package kr.lunaf.varstore.codec;

import kr.lunaf.varstore.api.VarKey;
import kr.lunaf.varstore.api.ValueType;
import java.util.Objects;

/** Explicit object adapter for a STRING key; ordinary STRING reads remain ordinary reads. */
public record CodecKey<T>(VarKey<String> storageKey, Codec<T> codec) {
    public CodecKey {
        Objects.requireNonNull(storageKey); Objects.requireNonNull(codec);
        if (storageKey.type() != ValueType.STRING) throw new IllegalArgumentException("Codec storage key must be STRING");
        if (codec.id() == null || !codec.id().matches("[a-z0-9][a-z0-9._/-]{0,63}")) throw new IllegalArgumentException("Invalid codec ID");
        if (codec.schemaVersion() < 1) throw new IllegalArgumentException("Schema version must be positive");
    }
    public static <T> CodecKey<T> of(String name, Codec<T> codec) { return new CodecKey<>(VarKey.stringKey(name), codec); }
}
