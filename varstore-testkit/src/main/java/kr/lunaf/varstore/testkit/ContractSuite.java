package kr.lunaf.varstore.testkit;

import kr.lunaf.varstore.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Reusable real-provider contract runner. Invoke from a dedicated test thread, never
 * a Minecraft game thread. Both stores must address the same disposable network.
 * Throws AssertionError on contract violations; database failures remain failures.
 */
public final class ContractSuite {
    private ContractSuite() {}
    public record Report(Map<String, Long> testMillis, int uniqueIncrements, int duplicateSubmissions) {
        public Report { testMillis = Map.copyOf(testMillis); }
    }
    public static Report run(VarStore first, VarStore second, String namespace) {
        await(first.ready()); await(second.ready());
        var a = first.namespace(namespace).network().system("contract");
        var b = second.namespace(namespace).network().system("contract");
        var key = VarKey.longKey("count");
        Map<String,Long> timings = new LinkedHashMap<>();
        timed(timings, "T12_typed_absence", () -> {
            equal(Optional.empty(), await(a.get(key)), "new key absent");
            await(a.set(key,0L));
            equal(0L, await(b.get(key)).orElseThrow(), "second server reads committed zero");
            expect(ErrorCode.TYPE_MISMATCH, a.get(VarKey.stringKey(key.name())));
            await(a.delete(key));
            equal(Optional.empty(),await(a.get(key)),"tombstone hidden");
            expect(ErrorCode.TYPE_MISMATCH,a.get(VarKey.stringKey(key.name())));
            expect(ErrorCode.MISSING_VALUE,a.increment(key,1,UUID.randomUUID()));
            await(a.setIfAbsent(key,0L,UUID.randomUUID()));
        });
        timed(timings,"T04_two_servers_2000_increments", () -> {
            CompletableFuture<?> left = CompletableFuture.runAsync(() -> { for(int i=0;i<1000;i++) await(a.increment(key,1,UUID.randomUUID())); });
            CompletableFuture<?> right = CompletableFuture.runAsync(() -> { for(int i=0;i<1000;i++) await(b.increment(key,1,UUID.randomUUID())); });
            await(CompletableFuture.allOf(left,right));
            equal(2000L,await(a.get(key)).orElseThrow(),"all independently counted increments preserved");
        });
        timed(timings,"T05_same_id_100_concurrent", () -> {
            UUID operation = UUID.randomUUID();
            List<CompletionStage<WriteReceipt<Long>>> requests = new ArrayList<>();
            for(int i=0;i<100;i++) requests.add((i%2==0?a:b).increment(key,7,operation));
            int originals=0;
            for(var request:requests){ var result=await(request); if(!result.replayed())originals++; equal(2007L,result.value().orElseThrow(),"original result replayed"); }
            equal(1,originals,"one original operation"); equal(2007L,await(b.get(key)).orElseThrow(),"duplicate applied once");
            expect(ErrorCode.IDEMPOTENCY_KEY_REUSED,a.increment(key,8,operation));
            equal(2007L,await(a.get(key)).orElseThrow(),"reused ID does not mutate");
        });
        timed(timings,"T08_T09_CAS_tombstones", () -> {
            var original=await(a.getVersioned(key)).orElseThrow();
            await(b.increment(key,1,UUID.randomUUID()));
            equal(Outcome.CONDITION_FAILED,await(a.compareAndSet(key,original.version(),99L,UUID.randomUUID())).outcome(),"stale CAS rejected");
            var beforeDelete=await(a.getVersioned(key)).orElseThrow();
            await(a.delete(key)); await(b.setIfAbsent(key,11L,UUID.randomUUID()));
            equal(Outcome.CONDITION_FAILED,await(a.compareAndSet(key,beforeDelete.version(),88L,UUID.randomUUID())).outcome(),"old generation cannot revive after delete");
            equal(11L,await(a.get(key)).orElseThrow(),"fresh value survives stale CAS");
            var current=await(a.getVersioned(key)).orElseThrow();
            var same=await(a.compareAndSet(key,current.version(),11L,UUID.randomUUID()));
            equal(Outcome.NO_CHANGE,same.outcome(),"same value no change");
            equal(current.version(),same.version().orElseThrow(),"no-change retains revision");
            var zero=await(a.increment(key,0,UUID.randomUUID()));
            equal(Outcome.NO_CHANGE,zero.outcome(),"zero increment no change");
        });
        timed(timings,"T10_transaction_failure_replay", () -> {
            var other=VarKey.longKey("other"); await(a.set(other,25L));
            var missing=VarKey.longKey("temporary");
            TransactionPlan plan=TransactionPlan.builder().set(a.target(missing),1L).requireLongRange(a.target(other),0,10).increment(a.target(key),2).build();
            UUID id=UUID.randomUUID();
            equal(Outcome.CONDITION_FAILED,await(first.namespace(namespace).execute(plan,id)).outcome(),"failed whole transaction");
            equal(Optional.empty(),await(a.get(missing)),"temporary row rolled back");
            equal(11L,await(b.get(key)).orElseThrow(),"no partial increments");
            await(a.set(other,5L));
            var replay=await(second.namespace(namespace).execute(plan,id));
            equal(Outcome.CONDITION_FAILED,replay.outcome(),"failed condition preserved even after state changes");
            check(replay.replayed(),"failed transaction replay marker");
            equal(OperationStatus.State.COMPLETED,await(first.namespace(namespace).operation(id)).state(),"failed condition recorded");
            equal(Outcome.APPLIED,await(first.namespace(namespace).execute(plan,UUID.randomUUID())).outcome(),"new attempt succeeds");
            equal(13L,await(b.get(key)).orElseThrow(),"new transaction increment");
            equal(1L,await(b.get(missing)).orElseThrow(),"new transaction set");
        });
        timed(timings,"T20_numeric_overflow_batch", () -> {
            var limit=VarKey.longKey("limit");await(a.set(limit,Long.MAX_VALUE));
            expect(ErrorCode.NUMERIC_OVERFLOW,a.increment(limit,1,UUID.randomUUID()));
            equal(Long.MAX_VALUE,await(a.get(limit)).orElseThrow(),"overflow unchanged");
            var absent=VarKey.booleanKey("absent");
            BatchRead batch=await(a.getAll(List.of(key,limit,absent)));
            equal(13L,batch.get(key).orElseThrow(),"batch value");equal(Optional.empty(),batch.get(absent),"batch explicit missing");
            equal(3,batch.keys().size(),"batch carries all requested keys");
            equal(OperationStatus.State.NOT_OBSERVED_YET,await(first.namespace(namespace).operation(UUID.randomUUID())).state(),"unknown ID cannot imply failed commit");
        });
        return new Report(timings,2000,100);
    }
    public static <T> T await(CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().get(60,TimeUnit.SECONDS); }
        catch(ExecutionException e){ Throwable cause=e.getCause(); if(cause instanceof RuntimeException runtime)throw runtime; if(cause instanceof Error error)throw error;throw new CompletionException(cause); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new CompletionException(e);}
        catch(TimeoutException e){throw new AssertionError("Contract operation exceeded 60 seconds",e);}
    }
    public static VarStoreException expect(ErrorCode code,CompletionStage<?> stage) {
        try { await(stage);throw new AssertionError("Expected "+code); }
        catch(VarStoreException e){equal(code,e.code(),"failure code");return e;}
    }
    private static void timed(Map<String,Long> timings,String name,Runnable test){long start=System.nanoTime();test.run();timings.put(name,Duration.ofNanos(System.nanoTime()-start).toMillis());}
    private static void equal(Object expected,Object actual,String context){if(!Objects.equals(expected,actual))throw new AssertionError(context+": expected "+expected+", observed "+actual);}
    private static void check(boolean success,String context){if(!success)throw new AssertionError(context);}
}
