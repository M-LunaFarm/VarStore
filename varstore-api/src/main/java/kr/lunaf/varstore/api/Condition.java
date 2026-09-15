package kr.lunaf.varstore.api;

import java.util.Objects;

/** Evaluated on locked rows before any mutations. LONG_RANGE is inclusive. */
public record Condition(Target<?> target, Kind kind, VersionToken version, long minimum, long maximum) {
    public enum Kind { EXISTS, ABSENT, VERSION, LONG_RANGE }
    public Condition {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.VERSION) != (version != null))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Only VERSION requires a version token");
        if (kind == Kind.LONG_RANGE && (target.type() != ValueType.LONG || minimum > maximum))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Invalid LONG_RANGE condition");
        if (kind != Kind.LONG_RANGE && (minimum != 0 || maximum != 0))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Only LONG_RANGE carries bounds");
    }
    public static Condition exists(Target<?> target) { return new Condition(target, Kind.EXISTS, null, 0, 0); }
    public static Condition absent(Target<?> target) { return new Condition(target, Kind.ABSENT, null, 0, 0); }
    public static Condition version(Target<?> target, VersionToken version) { return new Condition(target, Kind.VERSION, version, 0, 0); }
    public static Condition longRange(Target<Long> target, long minimum, long maximum) { return new Condition(target, Kind.LONG_RANGE, null, minimum, maximum); }
}
