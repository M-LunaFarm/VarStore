package kr.lunaf.varstore.codec;

import kr.lunaf.varstore.api.*;
import java.util.*;
import java.util.concurrent.*;

/** Object reads and prepared STRING writes on an existing addressed Data handle.
 * Write receipts deliberately retain their original STRING wire values and operation
 * IDs; a decoding error after a confirmed commit must not masquerade as write failure. */
public final class ObjectData {
    private final CodecAdapter adapter;
    private final VarStore.Data data;
    ObjectData(CodecAdapter adapter, VarStore.Data data) { this.adapter = adapter; this.data = data; }

    public <T> CompletionStage<Optional<T>> get(CodecKey<T> key) {
        return getVersioned(key).thenApply(value -> value.map(VersionedValue::value));
    }

    public <T> CompletionStage<Optional<VersionedValue<T>>> getVersioned(CodecKey<T> key) {
        adapter.ensureOpen();
        return data.getVersioned(key.storageKey()).thenCompose(value -> {
            if (value.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
            VersionedValue<String> stored = value.orElseThrow();
            return adapter.decode(key, stored.value()).thenApply(decoded -> Optional.of(new VersionedValue<>(decoded, stored.version())));
        });
    }

    public <T> CompletionStage<WriteReceipt<String>> set(CodecKey<T> key, EncodedValue<T> snapshot, UUID operationId) {
        validate(key, snapshot); return data.set(key.storageKey(), snapshot.envelope(), operationId);
    }

    public <T> CompletionStage<WriteReceipt<String>> setIfAbsent(CodecKey<T> key, EncodedValue<T> snapshot, UUID operationId) {
        validate(key, snapshot); return data.setIfAbsent(key.storageKey(), snapshot.envelope(), operationId);
    }

    public <T> CompletionStage<WriteReceipt<String>> compareAndSet(CodecKey<T> key, VersionToken version, EncodedValue<T> snapshot, UUID operationId) {
        validate(key, snapshot); return data.compareAndSet(key.storageKey(), version, snapshot.envelope(), operationId);
    }

    public CompletionStage<WriteReceipt<Void>> delete(CodecKey<?> key, UUID operationId) {
        adapter.ensureOpen(); return data.delete(key.storageKey(), operationId);
    }

    private <T> void validate(CodecKey<T> key, EncodedValue<T> snapshot) {
        adapter.ensureOpen(); Objects.requireNonNull(key); Objects.requireNonNull(snapshot);
        if (!snapshot.codecId().equals(key.codec().id())) throw new CodecException(CodecError.CODEC_MISMATCH, "Snapshot belongs to a different codec");
        if (snapshot.schemaVersion() != key.codec().schemaVersion()) throw new CodecException(CodecError.UNSUPPORTED_VERSION, "Snapshot is not the current write schema");
    }
}
