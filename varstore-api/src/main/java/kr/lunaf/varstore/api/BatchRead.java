package kr.lunaf.varstore.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One database statement snapshot; each requested key has an explicit optional entry. */
public record BatchRead(Map<VarKey<?>, Optional<VersionedValue<?>>> values) {
    public BatchRead {
        values = Map.copyOf(values);
        if (values.size() > 64) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Batch exceeds 64 keys");
    }
    public Set<VarKey<?>> keys() { return values.keySet(); }
    public <T> Optional<T> get(VarKey<T> key) { return getVersioned(key).map(VersionedValue::value); }
    public <T> Optional<VersionedValue<T>> getVersioned(VarKey<T> key) {
        Optional<VersionedValue<?>> result = values.get(key);
        if (result == null) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Key was not included in this batch");
        return result.map(value -> new VersionedValue<>(key.cast(value.value()), value.version()));
    }
}
