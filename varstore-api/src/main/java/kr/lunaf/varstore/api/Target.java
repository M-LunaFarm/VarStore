package kr.lunaf.varstore.api;

import java.util.Objects;

/** Typed full address used in a declarative transaction. */
public record Target<T>(Address address, ValueType type) {
    public Target { Objects.requireNonNull(address, "address"); Objects.requireNonNull(type, "type"); }
    public static <T> Target<T> of(Address address, VarKey<T> key) {
        if (!address.key().equals(key.name())) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Address and key names differ");
        return new Target<>(address, key.type());
    }
    public VarKey<T> key() { return new VarKey<>(address.key(), type); }
}
