package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.postgres.PostgresBackend;
import kr.lunaf.varstore.postgres.WriteKind;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Bounded database admission and separate bounded callback delivery. No caller-runs I/O. */
final class AsyncVarStore implements VarStore {
    private final StoreConfig config;
    private volatile PostgresBackend backend;
    private final ThreadPoolExecutor workers;
    private final ExecutorService completions;
    private final ScheduledExecutorService monitor;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final AtomicReference<StoreState> state = new AtomicReference<>(StoreState.STARTING);
    private final AtomicBoolean readyDelivery = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicBoolean stateDelivery = new AtomicBoolean();
    private final AtomicReference<StoreState> pendingState = new AtomicReference<>();
    private volatile ErrorCode lastError;
    private volatile long tableBytes = -1, indexBytes = -1, lockWaitMicros = -1;
    private long nextDiagnostics;
    private final CopyOnWriteArrayList<Consumer<StoreState>> listeners = new CopyOnWriteArrayList<>();
    private final Set<Request<?>> inFlight = ConcurrentHashMap.newKeySet();
    private final Object admission = new Object();
    private int admitted;
    private long admittedBytes;
    private final LongAdder requests = new LongAdder(), successes = new LongAdder(), conditions = new LongAdder(), errors = new LongAdder(), replays = new LongAdder(), unknown = new LongAdder();
    private final AtomicInteger consecutiveErrors = new AtomicInteger();
    private final AtomicLongArray latencies = new AtomicLongArray(4096);
    private final AtomicLong sampleIndex = new AtomicLong(), queueWait = new AtomicLong();

    AsyncVarStore(StoreConfig config) {
        this.config = config;
        workers = new ThreadPoolExecutor(config.dbWorkers(), config.dbWorkers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueMaxRequests()), threads("db"), new ThreadPoolExecutor.AbortPolicy());
        // Total admitted requests bound this queue even if a consumer blocks its callback.
        completions = Executors.newFixedThreadPool(2, threads("completion"));
        monitor = Executors.newSingleThreadScheduledExecutor(threads("health"));
    }
    void start() {
        monitor.execute(this::probe);
        monitor.scheduleWithFixedDelay(this::probe, 1, 1, TimeUnit.SECONDS);
    }
    private static ThreadFactory threads(String role) {
        AtomicInteger ids = new AtomicInteger();
        return r -> { Thread t = new Thread(r, "varstore-" + role + "-" + ids.incrementAndGet()); t.setDaemon(true); return t; };
    }
    private void probe() {
        if (state.get() == StoreState.DRAINING || state.get() == StoreState.CLOSED) return;
        try {
            synchronized (admission) {
                if (closed.get()) return;
                if (backend == null) backend = new PostgresBackend(config.storage(), config.maxAttempts());
            }
            if (state.get() == StoreState.STARTING) backend.initialize();
            else backend.probe(System.nanoTime() + config.requestTimeout().toNanos());
            consecutiveErrors.set(0);
            lastError = null;
            transition(StoreState.READY);
            if (readyDelivery.compareAndSet(false, true)) completions.execute(() -> ready.complete(null));
            if (System.nanoTime() >= nextDiagnostics) {
                var gauges = backend.diagnostics(System.nanoTime() + config.requestTimeout().toNanos());
                tableBytes = gauges.getOrDefault("tableBytes", -1L);
                indexBytes = gauges.getOrDefault("indexBytes", -1L);
                lockWaitMicros = gauges.getOrDefault("lockWaitMicros", -1L);
                nextDiagnostics = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            }
        } catch (Exception e) {
            if (e instanceof VarStoreException v) lastError = v.code();
            if (state.get() != StoreState.STARTING) transition(StoreState.DEGRADED);
        }
    }
    private void transition(StoreState next) {
        StoreState old;
        do {
            old = state.get();
            if (old == next || old == StoreState.CLOSED || (old == StoreState.DRAINING && next != StoreState.CLOSED)) return;
        } while (!state.compareAndSet(old, next));
        pendingState.set(next);
        dispatchState();
    }
    private void dispatchState() {
        if (!stateDelivery.compareAndSet(false, true)) return;
        try {
            completions.execute(() -> {
                try {
                    StoreState next = pendingState.getAndSet(null);
                    if (next != null) for (var listener : listeners) {
                        try { listener.accept(next); } catch (RuntimeException ignored) { /* consumer isolation */ }
                    }
                } finally {
                    stateDelivery.set(false);
                    if (pendingState.get() != null) dispatchState();
                }
            });
        } catch (RejectedExecutionException ignored) { stateDelivery.set(false); }
    }
    @Override public StoreState state() { return state.get(); }
    @Override public Optional<ErrorCode> lastError() { return Optional.ofNullable(lastError); }
    @Override public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }
    @Override public AutoCloseable onStateChange(Consumer<StoreState> listener) { listeners.add(Objects.requireNonNull(listener)); return () -> listeners.remove(listener); }
    @Override public StoreMetrics metrics() {
        int n = (int) Math.min(sampleIndex.get(), latencies.length());
        long[] samples = new long[n]; for (int i = 0; i < n; i++) samples[i] = latencies.get(i);
        Arrays.sort(samples);
        synchronized (admission) {
            return new StoreMetrics(requests.sum(), successes.sum(), conditions.sum(), errors.sum(), replays.sum(), unknown.sum(),
                    admitted, admittedBytes, backend == null ? 0 : backend.activeConnections(), percentile(samples,.5), percentile(samples,.95), percentile(samples,.99), queueWait.get(), lockWaitMicros, tableBytes, indexBytes);
        }
    }
    private static long percentile(long[] a,double p) { return a.length == 0 ? 0 : a[Math.min(a.length-1,(int)Math.ceil(a.length*p)-1)]; }
    @Override public Namespace namespace(String name) {
        // Reuse authoritative address validation, without a database call.
        new Address(config.storage().networkId(), name, ScopeKind.NETWORK, "_", Owner.system("global"), "validation");
        return new NamespaceHandle(name);
    }
    private VarStoreException failure(ErrorCode code,UUID id) { return new VarStoreException(code, "VarStore request failed: " + code, id); }
    private <T> CompletionStage<T> checked(UUID id,Supplier<CompletionStage<T>> action) {
        try { return action.get(); }
        catch (VarStoreException e) { return CompletableFuture.failedStage(e.withOperationId(id)); }
        catch (RuntimeException e) { return CompletableFuture.failedStage(failure(ErrorCode.INVALID_ARGUMENT,id)); }
    }
    @FunctionalInterface private interface Work<T> { T run(long deadline) throws Exception; }
    private <T> CompletionStage<T> submit(long bytes, UUID id, boolean write, Work<T> work) {
        requests.increment();
        Request<T> r;
        synchronized (admission) {
            StoreState s = state.get();
            if (s != StoreState.READY) return CompletableFuture.failedStage(failure(s == StoreState.DRAINING || s == StoreState.CLOSED ? ErrorCode.SHUTTING_DOWN : lastError == ErrorCode.STALE_EPOCH ? ErrorCode.STALE_EPOCH : s == StoreState.DEGRADED ? ErrorCode.STORAGE_UNAVAILABLE : ErrorCode.NOT_READY,id));
            if (admitted >= config.queueMaxRequests() || bytes > config.queueMaxBytes() - admittedBytes)
                return CompletableFuture.failedStage(failure(ErrorCode.OVERLOADED,id));
            admitted++; admittedBytes += bytes;
            r = new Request<>(bytes,id,write,work); inFlight.add(r);
            try { workers.execute(r); }
            catch (RejectedExecutionException e) { r.finish(null,failure(ErrorCode.OVERLOADED,id)); }
        }
        // Return a detached view: consumer cancellation cannot claim DB cancellation.
        return r.future.minimalCompletionStage();
    }
    private final class Request<T> implements Runnable {
        final long bytes, submitted = System.nanoTime(), deadline = submitted + config.requestTimeout().toNanos();
        final UUID id; final boolean write; final Work<T> work;
        final CompletableFuture<T> future = new CompletableFuture<>();
        final AtomicInteger phase = new AtomicInteger(); // 0 queued, 1 running, 2 returned
        Request(long bytes,UUID id,boolean write,Work<T> work) { this.bytes=bytes;this.id=id;this.write=write;this.work=work; }
        @Override public void run() {
            if (!phase.compareAndSet(0,1)) return;
            queueWait.set(TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-submitted));
            T result = null; Throwable failed = null;
            try {
                if (System.nanoTime() >= deadline) throw failure(ErrorCode.REQUEST_TIMEOUT,id);
                // Backend retries only confirmed rollbacks, retaining the same immutable request and ID.
                result = work.run(deadline);
                successes.increment(); consecutiveErrors.set(0);
                if (result instanceof WriteReceipt<?> receipt) { if (receipt.replayed()) replays.increment(); if (receipt.outcome()==Outcome.CONDITION_FAILED) conditions.increment(); }
                if (result instanceof TransactionReceipt receipt) { if (receipt.replayed()) replays.increment(); if (receipt.outcome()==Outcome.CONDITION_FAILED) conditions.increment(); }
            } catch (Throwable e) {
                failed = e;
                if (e instanceof VarStoreException v && (v.code()==ErrorCode.STORAGE_UNAVAILABLE || v.code()==ErrorCode.UNKNOWN_COMMIT_OUTCOME || v.code()==ErrorCode.STALE_EPOCH)) {
                    errors.increment(); if (v.code()==ErrorCode.UNKNOWN_COMMIT_OUTCOME) unknown.increment();
                    lastError = v.code();
                    if (v.code()==ErrorCode.STALE_EPOCH || consecutiveErrors.incrementAndGet() >= 3) transition(StoreState.DEGRADED);
                }
            } finally { finish(result,failed); }
        }
        void finish(T result,Throwable failed) {
            if (phase.getAndSet(2)==2) return;
            latencies.set((int)(sampleIndex.getAndIncrement()%latencies.length()),TimeUnit.NANOSECONDS.toMicros(System.nanoTime()-submitted));
            Runnable deliver = () -> {
                // Release admission before invoking consumer code so thenCompose can
                // submit its next request even with a one-request queue. At most two
                // delivering callbacks remain outside the bounded waiting queue.
                inFlight.remove(this);
                synchronized(admission) { admitted--;admittedBytes-=bytes;admission.notifyAll(); }
                if (failed==null) future.complete(result); else future.completeExceptionally(failed);
            };
            try { completions.execute(deliver); }
            catch (RejectedExecutionException e) { Thread.ofVirtual().name("varstore-final-completion").start(deliver); }
        }
    }
    private final class NamespaceHandle implements Namespace {
        final String name;
        NamespaceHandle(String name) { this.name=name; }
        @Override public Scope network() { return new ScopeHandle(name,ScopeKind.NETWORK,"_"); }
        @Override public Scope server(String server) { new Address(config.storage().networkId(),name,ScopeKind.SERVER,server,Owner.system("global"),"validation"); return new ScopeHandle(name,ScopeKind.SERVER,server); }
        @Override public CompletionStage<TransactionReceipt> execute(TransactionPlan plan,UUID id) {
            return checked(id, () -> {
                Objects.requireNonNull(plan);Objects.requireNonNull(id);
                for (Mutation m:plan.mutations()) checkNamespace(m.target().address());
                for (Condition c:plan.conditions()) checkNamespace(c.target().address());
                return submit(plan.estimatedBytes(),id,true,d -> backend.execute(plan,id,d));
            });
        }
        private void checkNamespace(Address address) { if (!address.networkId().equals(config.storage().networkId()) || !address.namespace().equals(name)) throw failure(ErrorCode.INVALID_ARGUMENT,null); }
        @Override public CompletionStage<OperationStatus> operation(UUID id) { return checked(id, () -> { Objects.requireNonNull(id); return submit(256,id,false,d -> backend.operation(name,id,d)); }); }
    }
    private final class ScopeHandle implements Scope {
        final String namespace;final ScopeKind scope; final String scopeId;
        ScopeHandle(String namespace,ScopeKind scope,String scopeId) { this.namespace=namespace;this.scope=scope;this.scopeId=scopeId; }
        @Override public Data player(UUID id) { return owner(Owner.player(id)); }
        @Override public Data system(String id) { return owner(Owner.system(id)); }
        @Override public Data owner(Owner owner) { return new DataHandle(namespace,scope,scopeId,Objects.requireNonNull(owner)); }
    }
    private final class DataHandle implements Data {
        final String namespace;final ScopeKind scope;final String scopeId;final Owner owner;
        DataHandle(String namespace,ScopeKind scope,String scopeId,Owner owner) { this.namespace=namespace;this.scope=scope;this.scopeId=scopeId;this.owner=owner; }
        @Override public Address address(VarKey<?> key) { return target(key).address(); }
        @Override public <T> Target<T> target(VarKey<T> key) { return new Target<>(new Address(config.storage().networkId(),namespace,scope,scopeId,owner,key.name()),key.type()); }
        @Override public <T> CompletionStage<Optional<VersionedValue<T>>> getVersioned(VarKey<T> key) { return checked(null, () -> { Target<T> t=target(key);return submit(512,null,false,d -> backend.get(t,d)); }); }
        @Override public <T> CompletionStage<Optional<T>> get(VarKey<T> key) { return getVersioned(key).thenApply(v -> v.map(VersionedValue::value)); }
        @Override public <T> CompletionStage<T> getOrDefault(VarKey<T> key,T fallback) { return get(key).thenApply(v -> v.orElse(fallback)); }
        @Override public CompletionStage<BatchRead> getAll(Collection<VarKey<?>> keys) {
            return checked(null, () -> {
                List<VarKey<?>> copy=List.copyOf(keys);if(copy.size()>64)throw failure(ErrorCode.INVALID_ARGUMENT,null);
                List<Target<?>> targets=new ArrayList<>();for(VarKey<?> k:copy)targets.add(target(k));
                return submit(256+512L*targets.size(),null,false,d -> {
                    var loaded=backend.getAll(targets,d);Map<VarKey<?>,Optional<VersionedValue<?>>> values=new LinkedHashMap<>();
                    for(int i=0;i<copy.size();i++)values.put(copy.get(i),loaded.get(targets.get(i).address()));
                    return new BatchRead(values);
                });
            });
        }
        private <T> CompletionStage<WriteReceipt<T>> write(VarKey<?> key,WriteKind kind,Object value,long delta,VersionToken version,UUID id) {
            return checked(id, () -> {
                Objects.requireNonNull(id); Target<?> t=target(key);
                if (kind==WriteKind.SET || kind==WriteKind.SET_IF_ABSENT || kind==WriteKind.COMPARE_AND_SET) key.type().validate(value);
                long bytes=1024+(value instanceof String s ? s.getBytes(StandardCharsets.UTF_8).length:32);
                return submit(bytes,id,true,d -> backend.write(t,kind,value,delta,version,id,d));
            });
        }
        @Override public <T> CompletionStage<WriteReceipt<T>> set(VarKey<T> key,T value,UUID id) { return write(key,WriteKind.SET,value,0,null,id); }
        @Override public <T> CompletionStage<WriteReceipt<T>> set(VarKey<T> key,T value) { return set(key,value,UUID.randomUUID()); }
        @Override public CompletionStage<WriteReceipt<Void>> delete(VarKey<?> key,UUID id) { return write(key,WriteKind.DELETE,null,0,null,id); }
        @Override public CompletionStage<WriteReceipt<Void>> delete(VarKey<?> key) { return delete(key,UUID.randomUUID()); }
        @Override public <T> CompletionStage<WriteReceipt<T>> setIfAbsent(VarKey<T> key,T value,UUID id) { return write(key,WriteKind.SET_IF_ABSENT,value,0,null,id); }
        @Override public CompletionStage<WriteReceipt<Long>> increment(VarKey<Long> key,long delta,UUID id) { return write(key,WriteKind.INCREMENT,null,delta,null,id); }
        @Override public <T> CompletionStage<WriteReceipt<T>> compareAndSet(VarKey<T> key,VersionToken version,T value,UUID id) { return checked(id,() -> {Objects.requireNonNull(version);return write(key,WriteKind.COMPARE_AND_SET,value,0,version,id);}); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized(admission) { transition(StoreState.DRAINING);workers.shutdown(); }
        monitor.shutdownNow();
        long until=System.nanoTime()+config.shutdownDrainTimeout().toNanos();
        try { workers.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        for(Runnable queued:workers.shutdownNow()) {
            if(queued instanceof AsyncVarStore.Request<?> r) r.finish(null,failure(ErrorCode.SHUTTING_DOWN,r.id));
        }
        if(backend!=null)backend.close();
        for(Request<?> r:inFlight) {
            if(r.phase.get()==1) r.finish(null,failure(r.write?ErrorCode.UNKNOWN_COMMIT_OUTCOME:ErrorCode.SHUTTING_DOWN,r.id));
        }
        if(!ready.isDone())completions.execute(() -> ready.completeExceptionally(failure(ErrorCode.SHUTTING_DOWN,null)));
        transition(StoreState.CLOSED);completions.shutdown();
        try { completions.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
