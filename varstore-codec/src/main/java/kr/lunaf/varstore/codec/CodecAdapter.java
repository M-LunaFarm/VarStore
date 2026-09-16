package kr.lunaf.varstore.codec;

import kr.lunaf.varstore.api.VarStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded object adapter. Preparing a mutable object is an explicit caller-side
 * snapshot step, before storage submission; run expensive custom encoders on your
 * worker while exclusively owning that object. Only EncodedValue enters write queues.
 * Reads parse/decode on bounded adapter workers, never on the completing game thread. */
public final class CodecAdapter implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Set<DecodeTask<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public CodecAdapter() { this(2, 128); }
    public CodecAdapter(int workers, int queueCapacity) {
        if (workers < 1 || workers > 16 || queueCapacity < 1 || queueCapacity > 65_536)
            throw new IllegalArgumentException("Invalid codec worker limits");
        AtomicInteger number = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "varstore-codec-" + number.incrementAndGet());
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public ObjectData data(VarStore.Data data) { ensureOpen(); return new ObjectData(this, Objects.requireNonNull(data)); }

    /** Synchronous CPU-only preparation. No JDBC, queue acceptance or persistence
     * occurs here. Retaining/modifying the original object cannot alter this result. */
    public <T> EncodedValue<T> prepare(CodecKey<T> key, T value) {
        ensureOpen(); Objects.requireNonNull(key); Objects.requireNonNull(value);
        Codec<T> codec = key.codec();
        try {
            LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>();
            fields.put("codec", JsonValue.string(codec.id()));
            fields.put("schema", JsonValue.number(codec.schemaVersion()));
            fields.put("payload", Objects.requireNonNull(codec.encode(value), "Codec payload"));
            String envelope = BoundedJson.write(JsonValue.object(fields));
            return new EncodedValue<>(codec.id(), codec.schemaVersion(), envelope, envelope.getBytes(StandardCharsets.UTF_8).length);
        } catch (CodecException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CodecException(CodecError.ENCODE_FAILED, "Codec could not prepare a snapshot", failure); }
    }

    /** Explicit envelope decoding; used only for keys opted into this adapter. */
    public <T> CompletionStage<T> decode(CodecKey<T> key, String envelope) {
        Objects.requireNonNull(key); Objects.requireNonNull(envelope);
        if (envelope.length() > BoundedJson.MAX_BYTES)
            return CompletableFuture.failedFuture(new CodecException(CodecError.VALUE_TOO_LARGE, "Envelope exceeds 16384 bytes"));
        // At most the bounded STRING limit is retained per queued task. Parsing and
        // UTF-8 counting occur on workers. No caller-supplied object deserialization.
        DecodeTask<T> task = new DecodeTask<>(key, envelope);
        pending.add(task);
        if (closed) { pending.remove(task); return CompletableFuture.failedFuture(new CodecException(CodecError.CLOSED, "Codec adapter is closed")); }
        try { executor.execute(task); }
        catch (RejectedExecutionException rejected) {
            pending.remove(task);
            task.result.completeExceptionally(new CodecException(closed ? CodecError.CLOSED : CodecError.OVERLOADED, "Codec worker capacity unavailable"));
        }
        return task.result.minimalCompletionStage();
    }

    public int pendingDecodes() { return pending.size(); }

    private <T> T decodeNow(CodecKey<T> key, String envelope) {
        JsonValue tree = BoundedJson.parse(envelope);
        if (!(tree instanceof JsonValue.ObjectValue object) || !object.fields().keySet().equals(Set.of("codec", "schema", "payload")))
            throw new CodecException(CodecError.INVALID_ENVELOPE, "Expected codec, schema and payload fields");
        if (!(object.fields().get("codec") instanceof JsonValue.StringValue id)
                || !(object.fields().get("schema") instanceof JsonValue.NumberValue version))
            throw new CodecException(CodecError.INVALID_ENVELOPE, "Invalid codec metadata");
        int schema;
        try { schema = version.value().intValueExact(); }
        catch (ArithmeticException error) { throw new CodecException(CodecError.INVALID_ENVELOPE, "Schema version must be an integer"); }
        if (schema < 1) throw new CodecException(CodecError.INVALID_ENVELOPE, "Schema version must be positive");
        if (!id.value().equals(key.codec().id())) throw new CodecException(CodecError.CODEC_MISMATCH, "Stored codec ID does not match the explicit key");
        try {
            if (!key.codec().supportsVersion(schema)) throw new CodecException(CodecError.UNSUPPORTED_VERSION, "Stored schema version is unsupported");
            return Objects.requireNonNull(key.codec().decode(schema, object.fields().get("payload")), "Decoded object");
        } catch (CodecException failure) { throw failure; }
        catch (RuntimeException failure) { throw new CodecException(CodecError.DECODE_FAILED, "Codec could not decode the stored payload", failure); }
    }

    void ensureOpen() { if (closed) throw new CodecException(CodecError.CLOSED, "Codec adapter is closed"); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        executor.shutdownNow();
        CodecException error = new CodecException(CodecError.CLOSED, "Codec adapter is closed");
        // Complete accepted requests even if a custom codec ignores interruption.
        // Such user code remains the caller's responsibility and must not block.
        for (DecodeTask<?> task : pending) task.result.completeExceptionally(error);
        pending.removeIf(task -> !task.started);
    }

    private final class DecodeTask<T> implements Runnable {
        final CodecKey<T> key; final String envelope;
        final CompletableFuture<T> result = new CompletableFuture<>();
        volatile boolean started;
        DecodeTask(CodecKey<T> key, String envelope) { this.key = key; this.envelope = envelope; }
        @Override public void run() {
            started = true;
            try {
                if (closed) result.completeExceptionally(new CodecException(CodecError.CLOSED, "Codec adapter is closed"));
                else result.complete(decodeNow(key, envelope));
            } catch (Throwable error) { result.completeExceptionally(error); }
            finally { pending.remove(this); }
        }
    }
}
