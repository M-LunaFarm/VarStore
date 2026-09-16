package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Process-local bounded reminders and same-plan recovery. No business events are persisted here. */
final class PendingWriteManager implements PendingWrites, AutoCloseable {
    private final VarStore store;
    private final ConcurrentMap<UUID, Attempt> entries = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(128);
    private final ScheduledThreadPoolExecutor timer = timer();
    private final ExecutorService delivery = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("varstore-reconcile-delivery-",0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    PendingWriteManager(VarStore store) { this.store = store; }
    private static ScheduledThreadPoolExecutor timer() {
        var timer=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"varstore-reconcile-timer");t.setDaemon(true);return t;});
        timer.setRemoveOnCancelPolicy(true);return timer;
    }
    @Override public synchronized CompletionStage<Result> execute(String namespace, TransactionPlan plan, UUID id, Policy policy) {
        try {
            Names.identifier(namespace,"namespace"); Objects.requireNonNull(plan); Objects.requireNonNull(id); Objects.requireNonNull(policy);
            for (Mutation mutation : plan.mutations()) if (!mutation.target().address().namespace().equals(namespace))
                throw new VarStoreException(ErrorCode.INVALID_ARGUMENT,"Recovery plan namespace mismatch",id);
            if (closed.get()) throw new VarStoreException(ErrorCode.SHUTTING_DOWN,"Recovery helper is closed",id);
            if (entries.containsKey(id)) throw new VarStoreException(ErrorCode.IDEMPOTENCY_KEY_REUSED,"Operation is already tracked; inspect it before resubmission",id);
            if (entries.size() >= 128 || !slots.tryAcquire()) throw new VarStoreException(ErrorCode.OVERLOADED,"Recovery capacity reached",id);
            Attempt attempt = new Attempt(namespace,plan,id,policy); entries.put(id,attempt);
            attempt.expiry = timer.schedule(() -> attempt.finish(State.REQUIRES_CONFIRMATION,null,null),policy.timeout().toNanos(),TimeUnit.NANOSECONDS);
            timer.execute(attempt::write);
            return attempt.result.minimalCompletionStage();
        } catch (RuntimeException failure) { return CompletableFuture.failedStage(failure); }
    }
    @Override public List<Tracked> tracked() {
        return entries.values().stream().map(a -> new Tracked(a.id,a.namespace,a.started,a.count.get(),Optional.ofNullable(a.lastError),!a.done.get()))
                .sorted(Comparator.comparing(Tracked::startedAt)).toList();
    }
    @Override public boolean forget(UUID id) { Attempt a=entries.get(id); return a!=null && a.done.get() && entries.remove(id,a); }
    private final class Attempt {
        final String namespace; volatile TransactionPlan plan; final UUID id; final Policy policy;
        final Instant started=Instant.now(); final long deadline;
        final AtomicInteger count=new AtomicInteger(); final AtomicBoolean done=new AtomicBoolean();
        final CompletableFuture<Result> result=new CompletableFuture<>();
        volatile ErrorCode lastError; volatile ScheduledFuture<?> expiry;
        Attempt(String ns,TransactionPlan plan,UUID id,Policy policy) {
            this.namespace=ns;this.plan=plan;this.id=id;this.policy=policy;deadline=System.nanoTime()+policy.timeout().toNanos();
        }
        boolean allowed() {
            if(done.get())return false;
            if(closed.get()||System.nanoTime()>=deadline||count.get()>=policy.maxAttempts()) {
                finish(State.REQUIRES_CONFIRMATION,null,null);return false;
            }
            count.incrementAndGet();return true;
        }
        void write() {
            if(!allowed())return;
            TransactionPlan request=plan;if(request==null)return;
            try {
                store.namespace(namespace).execute(request,id).whenComplete((receipt,error)->{
                    if(error==null)finish(State.CONFIRMED,receipt,null);else failure(error);
                });
            } catch (RuntimeException error) { failure(error); }
        }
        void query() {
            if(!allowed())return;
            try {
                store.namespace(namespace).operation(id).whenComplete((status,error)->{
                    if(done.get())return;
                    if(error!=null){failure(error);return;}
                    switch(status.state()) {
                        case COMPLETED -> finish(State.CONFIRMED,status.receipt().orElseThrow(),null);
                        case RESULT_EXPIRED -> finish(State.RESULT_EXPIRED,null,null);
                        case NOT_OBSERVED_YET -> later(this::write);
                        default -> later(this::query);
                    }
                });
            } catch (RuntimeException error) { failure(error); }
        }
        void failure(Throwable error) {
            while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();
            if(error instanceof VarStoreException failure) {
                lastError=failure.code();
                switch(failure.code()) {
                    case UNKNOWN_COMMIT_OUTCOME,STORAGE_UNAVAILABLE,REQUEST_TIMEOUT,OVERLOADED,NOT_READY -> later(this::query);
                    case ALREADY_PROCESSED_RESULT_EXPIRED -> finish(State.RESULT_EXPIRED,null,null);
                    default -> finish(null,null,failure.withOperationId(id));
                }
            }else finish(null,null,error);
        }
        void later(Runnable next) {
            if(done.get())return;
            long base=policy.retryDelay().toNanos();
            long delay=Math.min(base*(1L<<Math.min(4,count.get()-1)),TimeUnit.SECONDS.toNanos(5));
            delay+=ThreadLocalRandom.current().nextLong(Math.max(1,base/2));
            if(closed.get()||delay>=deadline-System.nanoTime()){finish(State.REQUIRES_CONFIRMATION,null,null);return;}
            try{timer.schedule(next,delay,TimeUnit.NANOSECONDS);}catch(RejectedExecutionException ignored){finish(State.REQUIRES_CONFIRMATION,null,null);}
        }
        void finish(State state,TransactionReceipt receipt,Throwable error) {
            if(!done.compareAndSet(false,true))return;
            plan=null;if(expiry!=null)expiry.cancel(false);
            if(error!=null||state==State.CONFIRMED)entries.remove(id,this);
            Runnable complete=()->{try{
                if(error==null)result.complete(new Result(id,state,Optional.ofNullable(receipt),count.get(),Optional.ofNullable(lastError)));
                else result.completeExceptionally(error);
            }finally{slots.release();}};
            try{delivery.execute(complete);}catch(RejectedExecutionException ignored){Thread.ofVirtual().name("varstore-reconcile-final").start(complete);}
        }
    }
    @Override public synchronized void close() {
        if(!closed.compareAndSet(false,true))return;
        for(Attempt attempt:entries.values())attempt.finish(State.REQUIRES_CONFIRMATION,null,null);
        timer.shutdownNow();delivery.shutdown();entries.clear();
    }
}
