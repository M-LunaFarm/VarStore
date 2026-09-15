package kr.lunaf.varstore.api;

import java.util.Objects;

/** Closed mutation vocabulary: no user code runs while database locks are held. */
public record Mutation(Target<?> target, Kind kind, Object value, long delta) {
    public enum Kind { SET, DELETE, INCREMENT }
    public Mutation {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(kind, "kind");
        if (kind == Kind.SET) target.type().validate(value);
        else if (value != null) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Only SET carries a value");
        if (kind != Kind.INCREMENT && delta != 0) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Only INCREMENT carries a delta");
        if (kind == Kind.INCREMENT && target.type() != ValueType.LONG)
            throw new VarStoreException(ErrorCode.TYPE_MISMATCH, "INCREMENT requires LONG");
    }
    public static <T> Mutation set(Target<T> target, T value) { return new Mutation(target, Kind.SET, value, 0); }
    public static Mutation delete(Target<?> target) { return new Mutation(target, Kind.DELETE, null, 0); }
    public static Mutation increment(Target<Long> target, long delta) { return new Mutation(target, Kind.INCREMENT, null, delta); }
}
