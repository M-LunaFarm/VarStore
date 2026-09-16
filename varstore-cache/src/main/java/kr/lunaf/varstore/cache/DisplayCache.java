package kr.lunaf.varstore.cache;

import kr.lunaf.varstore.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Explicit display cache with one budget shared by all consumers. Never changes
 * VarStore.get() semantics. Loading reservations consume both entry and byte limits.
 * String loads reserve the full 16-KiB wire limit in addition to an old snapshot.
 * Limits account for encoded data plus conservative metadata, not JVM heap size. */
public final class DisplayCache implements AutoCloseable {
    final Object lock = new Object();
    private final CacheLimits limits;
    private final LongSupplier clock;
    private final LinkedHashMap<Slot, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Set<CacheHandle> handles = Collections.newSetFromMap(new IdentityHashMap<>());
    private long bytes, hits, misses, loads, coalesced, invalidations, discarded, evictions, failures, rejected;
    private boolean closed;

    public DisplayCache(CacheLimits limits) { this(limits, System::nanoTime); }
    DisplayCache(CacheLimits limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
    }

    /** A handle is namespace-scoped. Await ready() before loading: its independent
     * ephemeral subscription is activated before a database read can begin. */
    public CacheHandle open(VarStore store, String namespace, CacheLimits localLimits) {
        Objects.requireNonNull(store); Objects.requireNonNull(namespace); Objects.requireNonNull(localLimits);
        // Reuse the public address grammar without sending a request.
        new Address("cache", namespace, ScopeKind.NETWORK, "_", Owner.system("cache"), "validate");
        if (!(store instanceof VarStoreExtensions extensions))
            throw new IllegalArgumentException("The store does not provide invalidation capabilities");
        CacheHandle handle = new CacheHandle(this, store, extensions, namespace, localLimits);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Display cache is closed");
            if (handles.size() >= limits.maxEntries()) throw new VarStoreException(ErrorCode.OVERLOADED, "Cache handle limit reached");
            handles.add(handle);
        }
        try { handle.start(); } catch (RuntimeException failure) { handle.close(); throw failure; }
        return handle;
    }

    <T> CachedValue<T> peek(CacheHandle handle, VarStore.Data data, KeyDefinition<T> definition, Duration maxAge) {
        Address address = address(handle, data, definition); long maxNanos = maxAge(maxAge);
        synchronized (lock) {
            ErrorCode unavailable = handle.unavailable();
            if (unavailable != null) return unavailable(unavailable);
            Entry e = entries.get(new Slot(handle, address));
            if (e == null) { misses++; return empty(CacheState.MISS); }
            if (e.type != definition.key().type()) return unavailable(ErrorCode.TYPE_MISMATCH);
            CachedValue<T> result = snapshot(e, maxNanos);
            if (result.state() == CacheState.VALUE || result.state() == CacheState.ABSENT) hits++; else misses++;
            return result;
        }
    }

    <T> CompletionStage<CachedValue<T>> get(CacheHandle handle, VarStore.Data data, KeyDefinition<T> definition, Duration maxAge) {
        Address address = address(handle, data, definition); long maxNanos = maxAge(maxAge);
        Entry entry; long generation; CompletableFuture<CachedValue<Object>> loading;
        synchronized (lock) {
            ErrorCode unavailable = handle.unavailable();
            if (unavailable != null) return CompletableFuture.completedFuture(unavailable(unavailable));
            Slot slot = new Slot(handle, address);
            entry = entries.get(slot);
            if (entry != null && entry.type != definition.key().type())
                return CompletableFuture.completedFuture(unavailable(ErrorCode.TYPE_MISMATCH));
            if (entry != null) {
                CachedValue<T> value = snapshot(entry, maxNanos);
                if (value.state() == CacheState.VALUE || value.state() == CacheState.ABSENT) {
                    hits++; return CompletableFuture.completedFuture(value);
                }
                if (entry.loading != null) {
                    coalesced++; return copy(entry.loading, maxNanos);
                }
            }
            misses++;
            boolean fresh = entry == null;
            if (fresh) entry = new Entry(slot, definition.key().type());
            long reservation = maxValueBytes(entry.type) + (fresh ? entry.baseBytes : 0);
            if (!reserve(handle, entry, reservation, fresh)) {
                rejected++; return CompletableFuture.completedFuture(unavailable(ErrorCode.OVERLOADED));
            }
            if (fresh) { entries.put(slot, entry); handle.entryCount++; }
            bytes += reservation; handle.bytes += reservation; entry.bytes += reservation;
            entry.readStarted = clock.getAsLong(); generation = entry.generation;
            entry.loading = loading = new CompletableFuture<>(); loads++;
        }
        Entry target = entry;
        try {
            data.getVersioned(definition.key()).whenComplete((value, error) -> finish(target, generation, value, error));
        } catch (Throwable error) { finish(target, generation, null, error); }
        return copy(loading, maxNanos);
    }

    private boolean reserve(CacheHandle handle, Entry protectedEntry, long addedBytes, boolean fresh) {
        if (addedBytes > limits.maxBytes() || addedBytes + protectedEntry.bytes > handle.limits.maxBytes()) return false;
        while (true) {
            boolean local = handle.bytes + addedBytes > handle.limits.maxBytes()
                    || handle.entryCount + (fresh ? 1 : 0) > handle.limits.maxEntries();
            boolean global = bytes + addedBytes > limits.maxBytes()
                    || entries.size() + (fresh ? 1 : 0) > limits.maxEntries();
            if (!local && !global) return true;
            Entry victim = null;
            for (Entry e : entries.values()) {
                if (e != protectedEntry && e.loading == null && (!local || e.slot.handle == handle)) { victim = e; break; }
            }
            if (victim == null) return false;
            remove(victim); evictions++;
        }
    }

    private void finish(Entry e, long generation, Optional<? extends VersionedValue<?>> value, Throwable error) {
        CompletableFuture<CachedValue<Object>> promise; CachedValue<Object> result;
        synchronized (lock) {
            promise = e.loading;
            if (promise == null) return;
            e.loading = null;
            long reservation = maxValueBytes(e.type);
            e.bytes -= reservation; bytes -= reservation; e.slot.handle.bytes -= reservation;
            if (closed || e.slot.handle.closed || e.generation != generation || e.slot.handle.unavailable() != null) {
                discarded++;
                result = e.slot.handle.closed || closed ? unavailable(ErrorCode.SHUTTING_DOWN) : empty(CacheState.STALE);
                remove(e);
            } else if (error != null) {
                failures++; e.error = errorCode(error); result = snapshot(e, Long.MAX_VALUE);
            } else {
                Objects.requireNonNull(value, "Read result");
                Object old = e.value;
                VersionedValue<?> loaded = value.orElse(null);
                e.value = loaded == null ? null : loaded.value();
                e.version = loaded == null ? null : loaded.version();
                e.hasSnapshot = true; e.error = null; e.invalidated = false;
                long delta = valueBytes(e.type, e.value) - valueBytes(e.type, old);
                e.bytes += delta; bytes += delta; e.slot.handle.bytes += delta;
                e.snapshotStarted = e.readStarted;
                result = snapshot(e, Long.MAX_VALUE);
            }
        }
        // Never run arbitrary completion callbacks under the cache lock.
        promise.complete(result);
    }

    private <T> CompletionStage<CachedValue<T>> copy(CompletableFuture<CachedValue<Object>> future, long maxNanos) {
        return future.thenApply(v -> {
            CacheState state = v.state();
            if ((state == CacheState.VALUE || state == CacheState.ABSENT) && v.age().toNanos() >= maxNanos) state = CacheState.STALE;
            @SuppressWarnings("unchecked") Optional<T> value = (Optional<T>) (Optional<?>) v.value();
            return new CachedValue<>(state, value, v.version(), v.age(), v.error());
        }).minimalCompletionStage();
    }

    void invalidate(CacheHandle handle, Address address) {
        synchronized (lock) {
            if (!address.namespace().equals(handle.namespace)) return;
            Entry entry = entries.get(new Slot(handle, address));
            if (entry != null) invalidate(entry);
        }
    }

    void invalidateAll(CacheHandle handle) {
        synchronized (lock) {
            for (Entry e : new ArrayList<>(entries.values())) if (e.slot.handle == handle) invalidate(e);
        }
    }

    /** Clears only display observations. In-flight reads are generation-fenced. */
    public void invalidateAll() {
        synchronized (lock) {
            for (Entry e : new ArrayList<>(entries.values())) invalidate(e);
        }
    }

    private void invalidate(Entry e) {
        e.generation++; e.invalidated = true; invalidations++;
        // In-flight reservations stay charged until their request actually finishes.
        if (e.loading == null) remove(e);
    }

    void closeHandle(CacheHandle handle) {
        synchronized (lock) { handles.remove(handle); invalidateAll(handle); }
    }

    private void remove(Entry e) {
        if (entries.remove(e.slot, e)) {
            bytes -= e.bytes; e.slot.handle.bytes -= e.bytes; e.slot.handle.entryCount--;
        }
    }

    public CacheMetrics metrics() {
        synchronized (lock) {
            return new CacheMetrics(handles.size(), entries.size(), bytes,
                    (int) entries.values().stream().filter(e -> e.loading != null).count(),
                    hits, misses, loads, coalesced, invalidations, discarded, evictions, failures, rejected);
        }
    }

    @Override public void close() {
        List<CacheHandle> closing;
        synchronized (lock) { if (closed) return; closed = true; closing = List.copyOf(handles); }
        closing.forEach(CacheHandle::close);
    }

    private static <T> Address address(CacheHandle handle, VarStore.Data data, KeyDefinition<T> definition) {
        Objects.requireNonNull(definition); Objects.requireNonNull(data);
        if (definition.cachePolicy() != CachePolicy.DISPLAY_ONLY)
            throw new VarStoreException(ErrorCode.ACCESS_DENIED, "Key does not permit display caching");
        Address address = data.address(definition.key());
        if (!address.namespace().equals(handle.namespace)) throw new VarStoreException(ErrorCode.ACCESS_DENIED, "Cache namespace mismatch");
        if (handle.networkId != null && !address.networkId().equals(handle.networkId))
            throw new VarStoreException(ErrorCode.ACCESS_DENIED, "Cache network mismatch");
        return address;
    }

    private static long maxAge(Duration age) {
        Objects.requireNonNull(age);
        if (age.isNegative() || age.isZero() || age.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Cache maxAge must be positive and at most one day");
        return age.toNanos();
    }

    private <T> CachedValue<T> snapshot(Entry e, long maxAge) {
        long age = e.hasSnapshot ? Math.max(0, clock.getAsLong() - e.snapshotStarted) : 0;
        CacheState state = e.error != null ? CacheState.UNAVAILABLE
                : e.invalidated ? CacheState.STALE : !e.hasSnapshot ? CacheState.MISS
                : age >= maxAge ? CacheState.STALE : e.value == null ? CacheState.ABSENT : CacheState.VALUE;
        @SuppressWarnings("unchecked") T value = (T) e.value;
        return new CachedValue<>(state, Optional.ofNullable(value), Optional.ofNullable(e.version),
                Duration.ofNanos(age), Optional.ofNullable(e.error));
    }

    private static <T> CachedValue<T> empty(CacheState state) {
        return new CachedValue<>(state, Optional.empty(), Optional.empty(), Duration.ZERO, Optional.empty());
    }
    static <T> CachedValue<T> unavailable(ErrorCode error) {
        return new CachedValue<>(CacheState.UNAVAILABLE, Optional.empty(), Optional.empty(), Duration.ZERO, Optional.of(error));
    }
    static ErrorCode errorCode(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException) error = error.getCause();
        return error instanceof VarStoreException failure ? failure.code() : ErrorCode.STORAGE_UNAVAILABLE;
    }
    private static int maxValueBytes(ValueType type) { return switch (type) { case STRING -> 16_384; case LONG -> 8; case UUID -> 16; case BOOLEAN -> 1; }; }
    private static long valueBytes(ValueType type, Object value) { return value == null ? 0 : type.encodedBytes(value); }

    private record Slot(CacheHandle handle, Address address) {}
    private static final class Entry {
        final Slot slot; final ValueType type; final long baseBytes;
        long bytes, generation, readStarted, snapshotStarted;
        Object value; VersionToken version; ErrorCode error;
        boolean hasSnapshot, invalidated;
        CompletableFuture<CachedValue<Object>> loading;
        Entry(Slot slot, ValueType type) {
            this.slot = slot; this.type = type;
            this.baseBytes = 256L + Arrays.stream(slot.address.fields()).mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
        }
    }
}
