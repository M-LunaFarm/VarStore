package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class PendingWriteManagerTest {
    static final PendingWrites.Policy POLICY = new PendingWrites.Policy(8, Duration.ofSeconds(2), Duration.ofMillis(10));
    static final TransactionPlan PLAN = TransactionPlan.builder().set(Target.of(
            new Address("network", "rpg", ScopeKind.NETWORK, "_", Owner.system("rewards"), "points"), VarKey.longKey("points")), 5L).build();

    @Test void unknownThenNotObservedResendsExactlySamePlanAndOperationId() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID(); AtomicInteger writes = new AtomicInteger();
        store.write = (plan, operation) -> {
            assertSame(PLAN, plan); assertEquals(id, operation);
            if (writes.incrementAndGet() == 1) return CompletableFuture.failedFuture(new VarStoreException(ErrorCode.UNKNOWN_COMMIT_OUTCOME, "lost response", id));
            return CompletableFuture.completedFuture(receipt(id, Outcome.APPLIED));
        };
        store.query = operation -> {
            assertEquals(id, operation); return CompletableFuture.completedFuture(status(id, OperationStatus.State.NOT_OBSERVED_YET));
        };
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            var result = await(manager.execute("rpg", PLAN, id, POLICY));
            assertEquals(PendingWrites.State.CONFIRMED, result.state()); assertEquals(3, result.attempts());
            assertEquals(2, writes.get()); assertEquals(id, result.receipt().orElseThrow().operationId());
            assertTrue(manager.tracked().isEmpty());
        }
    }

    @Test void confirmedQueryReturnsOriginalBusinessOutcomeWithoutAnotherWrite() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID(); AtomicInteger writes = new AtomicInteger();
        TransactionReceipt original = receipt(id, Outcome.CONDITION_FAILED);
        store.write = (plan, operation) -> { writes.incrementAndGet(); return CompletableFuture.failedFuture(new VarStoreException(ErrorCode.UNKNOWN_COMMIT_OUTCOME, "lost")); };
        store.query = operation -> CompletableFuture.completedFuture(new OperationStatus(id, OperationStatus.State.COMPLETED, Optional.of(original)));
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            var result = await(manager.execute("rpg", PLAN, id, POLICY));
            assertEquals(PendingWrites.State.CONFIRMED, result.state()); assertSame(original, result.receipt().orElseThrow()); assertEquals(1, writes.get());
        }
    }

    @Test void synchronousUnknownAndSynchronousQueryFailureUseSameBoundedRecoveryPath() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID(); AtomicInteger queries = new AtomicInteger();
        store.write = (plan, operation) -> { throw new VarStoreException(ErrorCode.UNKNOWN_COMMIT_OUTCOME, "sync unknown"); };
        store.query = operation -> {
            if (queries.incrementAndGet() == 1) throw new VarStoreException(ErrorCode.STORAGE_UNAVAILABLE, "sync offline");
            return CompletableFuture.completedFuture(new OperationStatus(id, OperationStatus.State.COMPLETED, Optional.of(receipt(id, Outcome.NO_CHANGE))));
        };
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            var result = await(manager.execute("rpg", PLAN, id, POLICY));
            assertEquals(PendingWrites.State.CONFIRMED, result.state()); assertEquals(3, result.attempts());
            assertEquals(2, queries.get());
        }
    }

    @Test void idReuseStopsImmediatelyAndPreservesIdForSynchronousValidationFailure() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID();
        store.write = (plan, operation) -> { throw new VarStoreException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "different request"); };
        store.query = operation -> { fail("Must not retry conflicting ID"); return null; };
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            ExecutionException result = assertThrows(ExecutionException.class, () -> await(manager.execute("rpg", PLAN, id, POLICY)));
            VarStoreException error = assertInstanceOf(VarStoreException.class, result.getCause());
            assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, error.code()); assertEquals(id, error.operationId());
            assertTrue(manager.tracked().isEmpty());
        }
    }

    @Test void notObservedAtAttemptLimitRequiresConfirmationAndLeavesForgettableReminder() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID(); AtomicInteger writes = new AtomicInteger();
        store.write = (plan, operation) -> { writes.incrementAndGet(); return CompletableFuture.failedFuture(new VarStoreException(ErrorCode.UNKNOWN_COMMIT_OUTCOME, "lost")); };
        store.query = operation -> CompletableFuture.completedFuture(status(id, OperationStatus.State.NOT_OBSERVED_YET));
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            var result = await(manager.execute("rpg", PLAN, id, new PendingWrites.Policy(2, Duration.ofSeconds(1), Duration.ofMillis(10))));
            assertEquals(PendingWrites.State.REQUIRES_CONFIRMATION, result.state()); assertTrue(result.receipt().isEmpty());
            assertEquals(2, result.attempts()); assertEquals(1, writes.get());
            assertEquals(1, manager.tracked().size()); assertFalse(manager.tracked().getFirst().resolving());
            assertTrue(manager.forget(id)); assertFalse(manager.forget(id));
        }
    }

    @Test void deadlineDoesNotTurnNeverCompletedWriteIntoFailureOrAllowLateCompletionToChangeResult() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID(); CompletableFuture<TransactionReceipt> write = new CompletableFuture<>();
        store.write = (plan, operation) -> write;
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            var result = await(manager.execute("rpg", PLAN, id, new PendingWrites.Policy(8, Duration.ofMillis(40), Duration.ofMillis(10))));
            assertEquals(PendingWrites.State.REQUIRES_CONFIRMATION, result.state());
            write.complete(receipt(id, Outcome.APPLIED));
            assertEquals(PendingWrites.State.REQUIRES_CONFIRMATION, result.state());
            assertEquals(1, manager.tracked().size());
        }
    }

    @Test void expiredResultIsExplicitAndNeverReplayedWithANewOperationId() throws Exception {
        FakeStore store = new FakeStore(); UUID id = UUID.randomUUID(); AtomicInteger queries = new AtomicInteger();
        store.write = (plan, operation) -> CompletableFuture.failedFuture(new VarStoreException(ErrorCode.UNKNOWN_COMMIT_OUTCOME, "lost"));
        store.query = operation -> { queries.incrementAndGet(); return CompletableFuture.completedFuture(status(id, OperationStatus.State.RESULT_EXPIRED)); };
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            assertEquals(PendingWrites.State.RESULT_EXPIRED, await(manager.execute("rpg", PLAN, id, POLICY)).state());
            assertEquals(1, queries.get()); assertEquals(id, manager.tracked().getFirst().operationId());
        }
    }

    @Test void boundedUnresolvedPlansRejectDuplicateAndOverflowAndCloseCompletesAcceptedRequests() throws Exception {
        FakeStore store = new FakeStore(); store.write = (plan, operation) -> new CompletableFuture<>();
        PendingWriteManager manager = new PendingWriteManager(store);
        try {
            List<CompletionStage<PendingWrites.Result>> requests = new ArrayList<>(); UUID first = UUID.randomUUID();
            var policy = new PendingWrites.Policy(8, Duration.ofSeconds(30), Duration.ofMillis(10));
            requests.add(manager.execute("rpg", PLAN, first, policy));
            assertFalse(manager.forget(first));
            assertCode(ErrorCode.IDEMPOTENCY_KEY_REUSED, manager.execute("rpg", PLAN, first, policy));
            for (int i = 1; i < 128; i++) requests.add(manager.execute("rpg", PLAN, UUID.randomUUID(), policy));
            assertEquals(128, manager.tracked().size());
            assertCode(ErrorCode.OVERLOADED, manager.execute("rpg", PLAN, UUID.randomUUID(), policy));
            manager.close();
            for (var request : requests) assertEquals(PendingWrites.State.REQUIRES_CONFIRMATION, await(request).state());
            assertTrue(manager.tracked().isEmpty());
            assertCode(ErrorCode.SHUTTING_DOWN, manager.execute("rpg", PLAN, UUID.randomUUID(), policy));
        } finally { manager.close(); }
    }

    @Test void slowCompletionCallbackDoesNotDelayIndependentReconciliationOrItsDeadline() throws Exception {
        FakeStore store = new FakeStore(); CompletableFuture<TransactionReceipt> firstWrite = new CompletableFuture<>();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        store.write = (plan, operation) -> operation.equals(first) ? firstWrite : CompletableFuture.completedFuture(receipt(operation, Outcome.APPLIED));
        CountDownLatch callbackStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        try (PendingWriteManager manager = new PendingWriteManager(store)) {
            var one = manager.execute("rpg", PLAN, first, POLICY);
            one.thenRun(() -> { callbackStarted.countDown(); try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            firstWrite.complete(receipt(first, Outcome.APPLIED)); assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            assertEquals(PendingWrites.State.CONFIRMED, await(manager.execute("rpg", PLAN, second, POLICY)).state());
        } finally { release.countDown(); }
    }

    private static TransactionReceipt receipt(UUID id, Outcome outcome) { return new TransactionReceipt(id, outcome, Map.of(), false); }
    private static OperationStatus status(UUID id, OperationStatus.State state) { return new OperationStatus(id, state, Optional.empty()); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(3, TimeUnit.SECONDS); }
    private static void assertCode(ErrorCode code, CompletionStage<?> stage) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(stage));
        assertEquals(code, assertInstanceOf(VarStoreException.class, failure.getCause()).code());
    }
    static final class FakeStore implements VarStore {
        volatile BiFunction<TransactionPlan, UUID, CompletionStage<TransactionReceipt>> write;
        volatile Function<UUID, CompletionStage<OperationStatus>> query;
        public Namespace namespace(String name) {
            assertEquals("rpg", name);
            return new Namespace() {
                public Scope network() { throw new UnsupportedOperationException(); } public Scope server(String id) { throw new UnsupportedOperationException(); }
                public CompletionStage<TransactionReceipt> execute(TransactionPlan plan, UUID id) { return write.apply(plan, id); }
                public CompletionStage<OperationStatus> operation(UUID id) { return query.apply(id); }
            };
        }
        public void close() {} public CompletionStage<Void> ready() { return CompletableFuture.completedFuture(null); }
        public StoreState state() { return StoreState.READY; } public StoreMetrics metrics() { throw new UnsupportedOperationException(); }
        public AutoCloseable onStateChange(Consumer<StoreState> listener) { return () -> {}; }
    }
}
