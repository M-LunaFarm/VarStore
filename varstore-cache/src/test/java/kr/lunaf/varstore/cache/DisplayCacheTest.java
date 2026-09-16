package kr.lunaf.varstore.cache;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class DisplayCacheTest {
    static final Duration AGE = Duration.ofSeconds(1);
    static final VarKey<Long> KEY = VarKey.longKey("points");
    static final KeyDefinition<Long> DEFINITION = definition(KEY);
    static final UUID EPOCH = UUID.randomUUID();
    static final UUID GENERATION = UUID.randomUUID();

    @Test void invalidationDuringLoadDiscardsOldSnapshotAndReverseEventsNeverInstallValues() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var first = handle.getCached(store.data, DEFINITION, AGE);
            store.invalidate(address(KEY));
            store.complete(1L, 1);
            assertEquals(CacheState.STALE, await(first).state());
            assertEquals(CacheState.MISS, handle.peekCached(store.data, DEFINITION, AGE).state());
            var second = handle.getCached(store.data, DEFINITION, AGE); store.complete(3L, 3);
            assertEquals(3L, await(second).value().orElseThrow());
            store.event(2, ChangeKind.SET, new VersionToken(EPOCH, GENERATION, 2));
            assertEquals(CacheState.MISS, handle.peekCached(store.data, DEFINITION, AGE).state());
            var third = handle.getCached(store.data, DEFINITION, AGE); store.complete(3L, 3);
            assertEquals(3L, await(third).value().orElseThrow());
            assertEquals(1, cache.metrics().discardedLoads());
        }
    }

    @Test void coalescesLoadingAndCallerCancellationDoesNotCancelTheSharedRead() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var first = handle.getCached(store.data, DEFINITION, AGE).toCompletableFuture();
            var second = handle.getCached(store.data, DEFINITION, AGE);
            assertEquals(1, store.reads); first.cancel(true); store.complete(5L, 1);
            assertEquals(5L, await(second).value().orElseThrow());
            assertEquals(1, cache.metrics().coalesced());
            assertEquals(CacheState.VALUE, handle.peekCached(store.data, DEFINITION, AGE).state());
        }
    }

    @Test void maxAgeRunsFromReadStartAndAppliesIndependentlyToCoalescedCallers() {
        AtomicLong clock = new AtomicLong(1);
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000), clock::get)) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var shortRead = handle.getCached(store.data, DEFINITION, Duration.ofMillis(10));
            var longRead = handle.getCached(store.data, DEFINITION, AGE);
            clock.addAndGet(Duration.ofMillis(20).toNanos()); store.complete(2L, 1);
            assertEquals(CacheState.STALE, await(shortRead).state());
            assertEquals(CacheState.VALUE, await(longRead).state());
            assertEquals(Duration.ofMillis(20), await(shortRead).age());
            clock.addAndGet(AGE.toNanos());
            assertEquals(CacheState.STALE, handle.peekCached(store.data, DEFINITION, AGE).state());
        }
    }

    @Test void deleteAndRecreationInvalidateNegativeCacheAndGenerationIsNeverComparedNumerically() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var absent = handle.getCached(store.data, DEFINITION, AGE); store.absent();
            assertEquals(CacheState.ABSENT, await(absent).state());
            store.event(1, ChangeKind.SET, new VersionToken(EPOCH, GENERATION, 8));
            var live = handle.getCached(store.data, DEFINITION, AGE); store.complete(8L, 8); assertEquals(8L, await(live).value().orElseThrow());
            store.event(2, ChangeKind.DELETE, new VersionToken(EPOCH, GENERATION, 9));
            var deleted = handle.getCached(store.data, DEFINITION, AGE); store.absent(); assertEquals(CacheState.ABSENT, await(deleted).state());
            store.event(3, ChangeKind.SET, new VersionToken(EPOCH, UUID.randomUUID(), 1));
            assertEquals(CacheState.MISS, handle.peekCached(store.data, DEFINITION, AGE).state());
        }
    }

    @Test void dbFailureIsUnavailableWithoutDefaultAndNextLoadCanRecover() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var failed = handle.getCached(store.data, DEFINITION, AGE);
            store.pending.remove().completeExceptionally(new VarStoreException(ErrorCode.STORAGE_UNAVAILABLE, "offline"));
            CachedValue<Long> result = await(failed);
            assertEquals(CacheState.UNAVAILABLE, result.state()); assertEquals(Optional.empty(), result.value());
            assertEquals(ErrorCode.STORAGE_UNAVAILABLE, result.error().orElseThrow());
            assertEquals(CacheState.UNAVAILABLE, handle.peekCached(store.data, DEFINITION, AGE).state());
            var retry = handle.getCached(store.data, DEFINITION, AGE); store.absent();
            assertEquals(CacheState.ABSENT, await(retry).state());
        }
    }

    @Test void globalAndConsumerBudgetsIncludeInflightReadsAndCannotBeBypassedByAnotherHandle() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(2, 1000))) {
            FakeStore first = new FakeStore(); CacheHandle a = cache.open(first, "test", new CacheLimits(1, 1000)); await(a.ready());
            FakeStore second = new FakeStore(); CacheHandle b = cache.open(second, "test", new CacheLimits(2, 1000)); await(b.ready());
            var one = a.getCached(first.data, DEFINITION, AGE);
            var two = b.getCached(second.data, DEFINITION, AGE);
            var other = definition(VarKey.longKey("other"));
            assertEquals(ErrorCode.OVERLOADED, await(a.getCached(first.data, other, AGE)).error().orElseThrow());
            assertEquals(ErrorCode.OVERLOADED, await(b.getCached(second.data, other, AGE)).error().orElseThrow());
            assertEquals(2, cache.metrics().loading()); assertTrue(cache.metrics().retainedBytes() <= 1000);
            first.complete(1L, 1); second.complete(2L, 1); await(one); await(two);
            var replacement = a.getCached(first.data, other, AGE); first.complete(4L, 1); await(replacement);
            assertEquals(CacheState.MISS, a.peekCached(first.data, DEFINITION, AGE).state());
            assertTrue(cache.metrics().evictions() >= 1);
            assertThrows(VarStoreException.class, () -> cache.open(new FakeStore(), "test", new CacheLimits(1, 1000)));
        }
    }

    @Test void encodedStringReservationRespectsByteBudgetBeforeSubmittingRead() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 10_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var stringKey = definition(VarKey.stringKey("large"));
            assertEquals(ErrorCode.OVERLOADED, await(handle.getCached(store.data, stringKey, AGE)).error().orElseThrow());
            assertEquals(0, store.reads); assertEquals(0, cache.metrics().retainedBytes());
        }
    }

    @Test void subscriptionActivationPrecedesLoadAndCloseReleasesListenersWithoutRepopulating() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); store.subscriptionReady = new CompletableFuture<>();
            CacheHandle handle = cache.open(store, "test", new CacheLimits(10, 100_000));
            assertEquals(ErrorCode.NOT_READY, await(handle.getCached(store.data, DEFINITION, AGE)).error().orElseThrow());
            assertEquals(0, store.reads); store.subscriptionReady.complete(store.subscription); await(handle.ready());
            var inflight = handle.getCached(store.data, DEFINITION, AGE); handle.close();
            assertEquals(0, store.localListeners.size()); assertEquals(0, store.resyncListeners.size()); assertTrue(store.subscription.closed);
            store.complete(1L, 1); assertEquals(CacheState.UNAVAILABLE, await(inflight).state());
            assertEquals(0, cache.metrics().entries()); assertEquals(0, cache.metrics().retainedBytes());
        }
    }

    @Test void epochChangePermanentlyClosesHandleAndStoreDisconnectInvalidatesOldLoads() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var read = handle.getCached(store.data, DEFINITION, AGE);
            store.state = StoreState.DEGRADED; store.stateListeners.forEach(c -> c.accept(StoreState.DEGRADED));
            store.complete(1L, 1); assertEquals(CacheState.STALE, await(read).state());
            assertEquals(CacheState.UNAVAILABLE, handle.peekCached(store.data, DEFINITION, AGE).state());
            store.state = StoreState.READY; store.stateListeners.forEach(c -> c.accept(StoreState.READY));
            assertEquals(CacheState.MISS, handle.peekCached(store.data, DEFINITION, AGE).state());
            store.event(1, ChangeKind.SET, new VersionToken(UUID.randomUUID(), GENERATION, 1));
            assertEquals(ErrorCode.SHUTTING_DOWN, handle.peekCached(store.data, DEFINITION, AGE).error().orElseThrow());
            assertEquals(0, cache.metrics().handles());
        }
    }

    @Test void clearAllFencesEveryInflightLoadAndCachePermissionIsExplicit() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            KeyDefinition<Long> disabled = new KeyDefinition<>(KEY, 0L, "private", true, CachePolicy.DISABLED, 1);
            assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(VarStoreException.class, () -> handle.getCached(store.data, disabled, AGE)).code());
            assertThrows(IllegalArgumentException.class, () -> handle.peekCached(store.data, DEFINITION, Duration.ZERO));
            var pending = handle.getCached(store.data, DEFINITION, AGE); cache.invalidateAll(); store.complete(4L, 1);
            assertEquals(CacheState.STALE, await(pending).state()); assertEquals(0, cache.metrics().entries());
        }
    }

    @Test void resyncBarrierClearsSnapshotAndBlocksPrimaryLoadsUntilResetCommits() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            var old = handle.getCached(store.data, DEFINITION, AGE); store.complete(1L, 1); await(old);
            store.subscription.reset = new CompletableFuture<>(); store.subscription.gap = true;
            store.resyncListeners.forEach(Runnable::run);
            assertEquals(CacheState.UNAVAILABLE, handle.peekCached(store.data, DEFINITION, AGE).state());
            assertEquals(ErrorCode.NOT_READY, await(handle.getCached(store.data, DEFINITION, AGE)).error().orElseThrow());
            assertEquals(1, store.reads);
            store.subscription.gap = false; store.subscription.reset.complete(null);
            assertEquals(CacheState.MISS, handle.peekCached(store.data, DEFINITION, AGE).state());
            var fresh = handle.getCached(store.data, DEFINITION, AGE); store.complete(2L, 2);
            assertEquals(2L, await(fresh).value().orElseThrow());
        }
    }

    @Test void crossNetworkDataCannotPopulateAHandleSubscribedToAnotherNetwork() {
        try (DisplayCache cache = new DisplayCache(new CacheLimits(10, 100_000))) {
            FakeStore store = new FakeStore(); CacheHandle handle = open(cache, store);
            VarStore.Data another = (VarStore.Data) Proxy.newProxyInstance(VarStore.Data.class.getClassLoader(), new Class<?>[]{VarStore.Data.class},
                    (proxy, method, args) -> new Address("other", "test", ScopeKind.NETWORK, "_", Owner.system("unit"), KEY.name()));
            assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(VarStoreException.class, () -> handle.getCached(another, DEFINITION, AGE)).code());
            assertEquals(0, store.reads);
        }
    }

    static CacheHandle open(DisplayCache cache, FakeStore store) {
        CacheHandle handle = cache.open(store, "test", new CacheLimits(10, 100_000)); await(handle.ready()); return handle;
    }
    static <T> KeyDefinition<T> definition(VarKey<T> key) {
        @SuppressWarnings("unchecked") T fallback = (T) switch (key.type()) {
            case LONG -> 0L; case STRING -> "default"; case BOOLEAN -> false; case UUID -> new UUID(0, 0);
        };
        return new KeyDefinition<>(key, fallback, "display", false, CachePolicy.DISPLAY_ONLY, 1);
    }
    static Address address(VarKey<?> key) { return new Address("network", "test", ScopeKind.NETWORK, "_", Owner.system("unit"), key.name()); }
    static <T> T await(CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().get(2, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    static final class FakeStore implements VarStore, VarStoreExtensions {
        final Queue<CompletableFuture<Optional<VersionedValue<Object>>>> pending = new ArrayDeque<>();
        final List<Consumer<Address>> localListeners = new ArrayList<>();
        final List<Runnable> resyncListeners = new ArrayList<>();
        final List<Consumer<StoreState>> stateListeners = new ArrayList<>();
        final FakeSubscription subscription = new FakeSubscription();
        CompletableFuture<EventSubscription> subscriptionReady = CompletableFuture.completedFuture(subscription);
        Function<ChangeEvent, CompletionStage<Void>> eventListener;
        StoreState state = StoreState.READY; int reads;
        final Data data = (Data) Proxy.newProxyInstance(Data.class.getClassLoader(), new Class<?>[]{Data.class}, (proxy, method, args) -> {
            if (method.getName().equals("address")) return address((VarKey<?>) args[0]);
            if (method.getName().equals("getVersioned")) { reads++; var result = new CompletableFuture<Optional<VersionedValue<Object>>>(); pending.add(result); return result; }
            throw new UnsupportedOperationException(method.getName());
        });
        void complete(Object value, long revision) { pending.remove().complete(Optional.of(new VersionedValue<>(value, new VersionToken(EPOCH, GENERATION, revision)))); }
        void absent() { pending.remove().complete(Optional.empty()); }
        void invalidate(Address address) { List.copyOf(localListeners).forEach(listener -> listener.accept(address)); }
        void event(long id, ChangeKind kind, VersionToken version) {
            await(eventListener.apply(new ChangeEvent(id, UUID.randomUUID(), address(KEY), version, kind, "unit", Instant.now())));
        }
        public EventService events() { return (spec, listener, reset) -> { eventListener = listener; return subscriptionReady; }; }
        public AutoCloseable onInvalidation(Consumer<Address> listener) { localListeners.add(listener); return () -> localListeners.remove(listener); }
        public AutoCloseable onResync(Runnable listener) { resyncListeners.add(listener); return () -> resyncListeners.remove(listener); }
        public AutoCloseable onStateChange(Consumer<StoreState> listener) { stateListeners.add(listener); return () -> stateListeners.remove(listener); }
        public void close() {}
        public Namespace namespace(String ns) {
            return new Namespace() {
                public Scope network() { return owner -> data; }
                public Scope server(String id) { return owner -> data; }
                public CompletionStage<TransactionReceipt> execute(TransactionPlan plan, UUID id) { throw new UnsupportedOperationException(); }
                public CompletionStage<OperationStatus> operation(UUID id) { throw new UnsupportedOperationException(); }
            };
        }
        public CompletionStage<Void> ready() { return CompletableFuture.completedFuture(null); }
        public StoreState state() { return state; } public StoreMetrics metrics() { throw new UnsupportedOperationException(); }
        public KeyRegistry definitions() { throw new UnsupportedOperationException(); }
        public PendingWrites pendingWrites() { throw new UnsupportedOperationException(); }
        public CompletionStage<KeyPage> scanKeys(Data data, String prefix, Optional<String> cursor, int limit) { throw new UnsupportedOperationException(); }
        public CompletionStage<Map<String, Long>> capacity() { throw new UnsupportedOperationException(); }
    }
    static final class FakeSubscription implements EventSubscription {
        boolean closed, gap;
        CompletableFuture<Void> reset = CompletableFuture.completedFuture(null);
        public SubscriptionState state() { return new SubscriptionState("test", UUID.randomUUID(), EPOCH, Instant.now().plusSeconds(60), gap); }
        public CompletionStage<Void> reset() { return reset; }
        public CompletionStage<Integer> retryDeadLetters(int limit) { return CompletableFuture.completedFuture(0); }
        public void close() { closed = true; }
    }
}
