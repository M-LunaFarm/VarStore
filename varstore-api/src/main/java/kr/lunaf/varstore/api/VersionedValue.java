package kr.lunaf.varstore.api;

import java.util.Objects;

/** A living value and the exact version observed in the same database read. */
public record VersionedValue<T>(T value, VersionToken version) {
    public VersionedValue { Objects.requireNonNull(value, "value"); Objects.requireNonNull(version, "version"); }
}
