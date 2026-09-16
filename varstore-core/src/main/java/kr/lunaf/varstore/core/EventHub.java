package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** Bounded polling and consumer delivery. Database work uses the store's normal admission. */
final class EventHub implements EventService, AutoCloseable {
    private final AsyncVarStore store;
    private final ConcurrentMap<String,Handle> handles = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r,"varstore-events"); t.setDaemon(true); return t;
    });
    private final ExecutorService callbacks = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("varstore-event-callback-",0).factory());
    private final Semaphore slots = new Semaphore(128);
    private boolean closed;
    EventHub(AsyncVarStore store) {
        this.store=store; timer.setRemoveOnCancelPolicy(true);
        timer.scheduleWithFixedDelay(() -> handles.values().forEach(h->{h.heartbeat();h.poll();}),250,250,TimeUnit.MILLISECONDS);
    }
    @Override public synchronized CompletionStage<EventSubscription> subscribe(SubscriptionSpec spec,
            Function<ChangeEvent,CompletionStage<Void>> listener,Runnable onResync) {
        Objects.requireNonNull(spec);Objects.requireNonNull(listener);Objects.requireNonNull(onResync);
        if(closed)return CompletableFuture.failedStage(error(ErrorCode.SHUTTING_DOWN));
        if(handles.size()>=64)return CompletableFuture.failedStage(error(ErrorCode.OVERLOADED));
        Handle h=new Handle(spec,listener,onResync);
        if(handles.putIfAbsent(spec.subscriberId(),h)!=null)return CompletableFuture.failedStage(error(ErrorCode.INVALID_ARGUMENT));
        return store.extension(1024,(b,d)->b.registerSubscription(spec,d)).thenApply(s -> {
            h.current=s;
            if(h.stopped.get()) { store.extension(256,(b,d)->{b.closeSubscription(s,d);return null;});throw error(ErrorCode.SHUTTING_DOWN); }
            return (EventSubscription)h;
        }).whenComplete((v,e)->{if(e!=null)handles.remove(spec.subscriberId(),h);});
    }
    private static VarStoreException error(ErrorCode code){return new VarStoreException(code,"Event subscription: "+code);}
    private static ErrorCode code(Throwable error){while(error instanceof CompletionException||error instanceof ExecutionException)error=error.getCause();return error instanceof VarStoreException v?v.code():ErrorCode.STORAGE_UNAVAILABLE;}
    private final class Handle implements EventSubscription {
        final SubscriptionSpec spec;final Function<ChangeEvent,CompletionStage<Void>> listener;final Runnable onResync;
        final AtomicBoolean stopped=new AtomicBoolean(),busy=new AtomicBoolean(),resetting=new AtomicBoolean(),renewing=new AtomicBoolean(),resyncDelivery=new AtomicBoolean();
        final AtomicLong generation=new AtomicLong();
        volatile SubscriptionState current;volatile boolean gap;volatile long nextResync;
        Handle(SubscriptionSpec spec,Function<ChangeEvent,CompletionStage<Void>> listener,Runnable onResync){this.spec=spec;this.listener=listener;this.onResync=onResync;}
        @Override public SubscriptionState state(){SubscriptionState s=current;return gap&&!s.resyncRequired()?new SubscriptionState(s.subscriberId(),s.sessionToken(),s.storageEpoch(),s.leaseUntil(),true):s;}
        void heartbeat(){
            SubscriptionState captured=current;long capturedGeneration=generation.get();
            if(stopped.get()||captured==null||resetting.get()||gap||captured.resyncRequired()
                    ||captured.leaseUntil().isAfter(Instant.now().plusMillis(spec.leaseDuration().toMillis()*2/3))
                    ||!renewing.compareAndSet(false,true))return;
            store.extension(1024,(b,d)->b.renewSubscription(captured,spec.leaseDuration(),d)).whenComplete((s,e)->{
                synchronized(this){
                    if(e==null && current==captured && generation.get()==capturedGeneration)current=s;
                    if(e!=null&&generation.get()==capturedGeneration&&current==captured&&(code(e)==ErrorCode.SUBSCRIPTION_EXPIRED||code(e)==ErrorCode.RESYNC_REQUIRED||code(e)==ErrorCode.STALE_EPOCH))gap=true;
                }
                renewing.set(false);
            });
        }
        void poll(){
            SubscriptionState captured;long capturedGeneration;
            synchronized(this){
                if(stopped.get()||current==null||resetting.get()||!busy.compareAndSet(false,true))return;
                if(gap||current.resyncRequired()){gap=true;busy.set(false);notifyResync();return;}
                captured=current;capturedGeneration=generation.get();
            }
            store.extension(16384,(b,d)->b.claimEvents(captured,8,Duration.ofSeconds(30),d)).thenCompose(batch->{
                if(resetting.get()||gap||generation.get()!=capturedGeneration)return CompletableFuture.completedStage(null);
                CompletableFuture<?>[] pending=batch.stream().map(delivery->deliver(delivery,captured,capturedGeneration).toCompletableFuture()).toArray(CompletableFuture[]::new);
                return CompletableFuture.allOf(pending);
            }).whenComplete((v,e)->{
                synchronized(this){if(e!=null&&generation.get()==capturedGeneration&&!stopped.get()&&(code(e)==ErrorCode.SUBSCRIPTION_EXPIRED||code(e)==ErrorCode.RESYNC_REQUIRED||code(e)==ErrorCode.STALE_EPOCH))gap=true;}
                busy.set(false);if(gap)notifyResync();
            });
        }
        void notifyResync(){
            long now=System.nanoTime();
            if(stopped.get()||now<nextResync||!resyncDelivery.compareAndSet(false,true))return;
            nextResync=now+TimeUnit.SECONDS.toNanos(1);
            if(!slots.tryAcquire()){resyncDelivery.set(false);return;}
            try{callbacks.execute(()->{try{onResync.run();}catch(RuntimeException ignored){}finally{slots.release();resyncDelivery.set(false);}});}
            catch(RejectedExecutionException e){slots.release();resyncDelivery.set(false);}
        }
        CompletionStage<Void> deliver(Delivery delivery,SubscriptionState session,long capturedGeneration){
            CompletableFuture<Void> completed=new CompletableFuture<>();
            if(stopped.get()||!slots.tryAcquire())completed.completeExceptionally(error(ErrorCode.OVERLOADED));
            else {
                try{callbacks.execute(()->{
                    try{
                        if(stopped.get()||generation.get()!=capturedGeneration)throw error(ErrorCode.SHUTTING_DOWN);
                        Objects.requireNonNull(listener.apply(delivery.event())).whenComplete((v,e)->{
                            try{if(e==null)completed.complete(null);else completed.completeExceptionally(e);}finally{slots.release();}
                        });
                    }catch(Throwable e){slots.release();completed.completeExceptionally(e);}
                });}catch(RejectedExecutionException e){slots.release();completed.completeExceptionally(e);}
            }
            ScheduledFuture<?> timeout;
            try{timeout=timer.schedule(()->completed.completeExceptionally(error(ErrorCode.REQUEST_TIMEOUT)),Math.max(1,Math.min(20000,Duration.between(Instant.now(),delivery.leaseUntil()).toMillis()-250)),TimeUnit.MILLISECONDS);}
            catch(RejectedExecutionException e){completed.completeExceptionally(error(ErrorCode.SHUTTING_DOWN));timeout=null;}
            ScheduledFuture<?> deadline=timeout;
            return completed.handle((v,e)->{
                if(deadline!=null)deadline.cancel(false);
                if(generation.get()!=capturedGeneration)return CompletableFuture.completedStage(false);
                if(e==null)return store.extension(256,(b,d)->b.acknowledge(session,delivery.event().eventId(),delivery.leaseToken(),d));
                return store.extension(256,(b,d)->b.failDelivery(session,delivery.event().eventId(),delivery.leaseToken(),code(e).name(),8,Duration.ofSeconds(1),d));
            }).thenCompose(Function.identity()).thenApply(v->null);
        }
        @Override public synchronized CompletionStage<Void> reset(){
            if(stopped.get())return CompletableFuture.failedStage(error(ErrorCode.SHUTTING_DOWN));
            if(!resetting.compareAndSet(false,true))return CompletableFuture.failedStage(error(ErrorCode.OVERLOADED));
            gap=true;generation.incrementAndGet();
            return store.extension(512,(b,d)->b.resetSubscription(current,d)).exceptionallyCompose(e->{
                if(stopped.get())return CompletableFuture.failedStage(error(ErrorCode.SHUTTING_DOWN));
                if(code(e)!=ErrorCode.SUBSCRIPTION_EXPIRED)return CompletableFuture.failedStage(e);
                return store.extension(1024,(b,d)->b.registerSubscription(spec,d)).thenCompose(s->{
                    if(stopped.get())return store.<SubscriptionState>extension(256,(b,d)->{b.closeSubscription(s,d);return null;}).handle((v,f)->{throw error(ErrorCode.SHUTTING_DOWN);});
                    return store.extension(512,(b,d)->b.resetSubscription(s,d));
                });
            }).thenCompose(s->{synchronized(this){
                if(stopped.get())return store.<Void>extension(256,(b,d)->{b.closeSubscription(s,d);return null;}).handle((v,f)->{throw error(ErrorCode.SHUTTING_DOWN);});
                current=s;gap=false;return CompletableFuture.<Void>completedStage(null);
            }}).whenComplete((v,e)->resetting.set(false));
        }
        @Override public CompletionStage<Integer> retryDeadLetters(int limit){return store.extension(512,(b,d)->b.retryDeadLetters(current,limit,d));}
        @Override public synchronized void close(){if(stopped.compareAndSet(false,true)){generation.incrementAndGet();handles.remove(spec.subscriberId(),this);SubscriptionState s=current;if(s!=null)store.extension(256,(b,d)->{b.closeSubscription(s,d);return null;});}}
    }
    @Override public synchronized void close(){if(closed)return;closed=true;handles.values().forEach(Handle::close);timer.shutdownNow();callbacks.shutdown();}
}
