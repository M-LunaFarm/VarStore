package kr.lunaf.varstore.cache;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Namespace-local allowance within a shared DisplayCache. Close this handle when
 * its consumer plugin stops. It never owns or closes the underlying VarStore. */
public final class CacheHandle implements AutoCloseable {
    final DisplayCache cache;
    final VarStore store;
    final VarStoreExtensions extensions;
    final String namespace;
    final CacheLimits limits;
    int entryCount;
    long bytes;
    volatile boolean closed;
    private volatile boolean active;
    private volatile EventSubscription subscription;
    private volatile UUID epoch;
    volatile String networkId;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final List<AutoCloseable> registrations = new ArrayList<>();
    private boolean resetting;

    CacheHandle(DisplayCache cache, VarStore store, VarStoreExtensions extensions, String namespace, CacheLimits limits) {
        this.cache = cache; this.store = store; this.extensions = extensions; this.namespace = namespace; this.limits = limits;
    }

    void start() {
        register(extensions.onInvalidation(this::invalidate));
        register(extensions.onResync(this::resync));
        register(store.onStateChange(state -> {
            if (store.lastError().orElse(null) == ErrorCode.STALE_EPOCH || state == StoreState.CLOSED || state == StoreState.DRAINING) close();
            else if (state != StoreState.READY) { active = false; invalidateAll(); }
            else resync();
        }));
        var spec = new SubscriptionSpec("cache-" + UUID.randomUUID(), namespace, SubscriptionMode.EPHEMERAL,
                Duration.ofMinutes(1), Duration.ofHours(1));
        extensions.events().subscribe(spec, event -> {
            UUID expected = epoch;
            if (expected != null && !expected.equals(event.version().storageEpoch())) close();
            else invalidate(event.address());
            return CompletableFuture.completedFuture(null);
        }, this::resync).whenComplete((sub, failure) -> {
            if (failure != null) {
                ready.completeExceptionally(failure); close(); return;
            }
            synchronized (this) {
                if (closed) { sub.close(); return; }
                subscription = sub; epoch = sub.state().storageEpoch();
                registrations.add(sub);
            }
            try {
                networkId = store.namespace(namespace).network().system("cache").address(VarKey.stringKey("identity")).networkId();
            } catch (RuntimeException error) { ready.completeExceptionally(error); close(); return; }
            active = !closed && !sub.state().resyncRequired();
            if (active) ready.complete(null); else resync();
        });
    }

    /** Completes only after server-specific event fan-out is active. */
    public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }

    public <T> CompletionStage<CachedValue<T>> getCached(VarStore.Data data, KeyDefinition<T> definition, Duration maxAge) {
        return cache.get(this, data, definition, maxAge);
    }

    /** Pure in-memory, immediate access: never submits a load or waits for I/O. */
    public <T> CachedValue<T> peekCached(VarStore.Data data, KeyDefinition<T> definition, Duration maxAge) {
        return cache.peek(this, data, definition, maxAge);
    }

    public void invalidate(Address address) { cache.invalidate(this, Objects.requireNonNull(address)); }
    public void invalidateAll() { cache.invalidateAll(this); }

    private void resync() {
        active = false;
        invalidateAll();
        EventSubscription sub;
        synchronized (this) {
            sub = subscription;
            if (closed || sub == null || resetting || store.state() != StoreState.READY) return;
            if (epoch != null && !epoch.equals(sub.state().storageEpoch())) { close(); return; }
            resetting = true;
        }
        sub.reset().whenComplete((ignored, error) -> {
            synchronized (this) {
                resetting = false;
                if (closed) return;
                if (error != null) { active = false; return; }
                if (epoch != null && !epoch.equals(sub.state().storageEpoch())) { close(); return; }
                active = !sub.state().resyncRequired() && store.state() == StoreState.READY;
            }
            if (active) ready.complete(null);
        });
    }

    ErrorCode unavailable() {
        if (closed) return ErrorCode.SHUTTING_DOWN;
        if (store.lastError().orElse(null) == ErrorCode.STALE_EPOCH) return ErrorCode.STALE_EPOCH;
        if (store.state() != StoreState.READY) return store.state() == StoreState.STARTING ? ErrorCode.NOT_READY : ErrorCode.STORAGE_UNAVAILABLE;
        return active ? null : ErrorCode.NOT_READY;
    }

    private void register(AutoCloseable registration) {
        synchronized (this) {
            if (!closed) { registrations.add(registration); return; }
        }
        quietlyClose(registration);
    }

    @Override public void close() {
        List<AutoCloseable> closing;
        synchronized (this) {
            if (closed) return;
            closed = true; active = false;
            closing = List.copyOf(registrations); registrations.clear();
        }
        cache.closeHandle(this);
        closing.forEach(CacheHandle::quietlyClose);
        ready.completeExceptionally(new VarStoreException(ErrorCode.SHUTTING_DOWN, "Cache handle is closed"));
    }

    private static void quietlyClose(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { /* Other registrations must still be released. */ }
    }
}
