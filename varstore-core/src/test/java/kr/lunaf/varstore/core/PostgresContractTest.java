package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.postgres.*;
import kr.lunaf.varstore.testkit.ContractSuite;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static kr.lunaf.varstore.testkit.ContractSuite.*;

/** Real PostgreSQL provider tests; opt in using VARSTORE_TEST_JDBC_URL. */
class PostgresContractTest {
    private String network;
    private final List<VarStore> stores = new ArrayList<>();
    private String jdbc(){return System.getenv("VARSTORE_TEST_JDBC_URL");}
    private String username(){return System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");}
    private String password(){return System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");}
    private Connection connection() throws SQLException {return DriverManager.getConnection(jdbc(),username(),password());}
    @BeforeEach void setup() throws Exception {
        Assumptions.assumeTrue(jdbc()!=null,"Real PostgreSQL requires VARSTORE_TEST_JDBC_URL");
        network="test-"+UUID.randomUUID();
        try(Connection c=connection()){SchemaMigrator.migrate(c,network);}
    }
    @AfterEach void cleanup(){for(VarStore store:stores)store.close();}
    private PostgresSettings settings(String server){return new PostgresSettings(jdbc(),username(),password(),network,server,"disable",true,4,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500));}
    private VarStore open(String server){return open(new StoreConfig(settings(server),4,256,8_388_608,Duration.ofSeconds(5),Duration.ofSeconds(2),3));}
    private VarStore open(StoreConfig config){VarStore store=StoreFactory.open(config);stores.add(store);await(store.ready());return store;}
    private VarStore.Data data(VarStore store){return store.namespace("contracts").network().system("test");}
    private void eventually(BooleanSupplier condition) throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Condition not observed within five seconds");
    }
    private void lock(Connection c,String key) throws SQLException {
        c.setAutoCommit(false);
        try(var query=c.prepareStatement("SELECT revision FROM vs_variables WHERE network_id=? AND variable_key=? FOR UPDATE")){
            query.setString(1,network);query.setString(2,key);try(var rows=query.executeQuery()){assertTrue(rows.next());}
        }
    }

    @Test void completeReusableContracts() {
        ContractSuite.Report report=ContractSuite.run(open("first"),open("second"),"suite");
        assertEquals(2000,report.uniqueIncrements());assertEquals(100,report.duplicateSubmissions());
        System.out.println("VarStore real PostgreSQL contract timings (ms): "+report.testMillis());
    }

    @Test void primitiveValuesSurviveProviderRestartAndServerScopeIsSeparate() {
        VarStore first=open("first");var a=data(first);UUID uuid=UUID.randomUUID();
        await(a.set(VarKey.stringKey("text"),"한글 😀"));await(a.set(VarKey.longKey("number"),Long.MIN_VALUE));
        await(a.set(VarKey.booleanKey("flag"),false));await(a.set(VarKey.uuidKey("uuid"),uuid));
        await(first.namespace("contracts").server("first").system("test").set(VarKey.longKey("number"),77L));
        first.close();var b=data(open("restarted"));
        assertEquals("한글 😀",await(b.get(VarKey.stringKey("text"))).orElseThrow());
        assertEquals(Long.MIN_VALUE,await(b.get(VarKey.longKey("number"))).orElseThrow());
        assertEquals(false,await(b.get(VarKey.booleanKey("flag"))).orElseThrow());
        assertEquals(uuid,await(b.get(VarKey.uuidKey("uuid"))).orElseThrow());
    }

    @Test void expiredOperationNeverReappliesAndOldEpochIsFenced() throws Exception {
        VarStore store=open("first");var data=data(store);var key=VarKey.longKey("value");UUID id=UUID.randomUUID();
        await(data.set(key,2L,id));
        try(Connection c=connection();var statement=c.prepareStatement("UPDATE vs_operations SET completed_at=clock_timestamp()-interval '8 days' WHERE network_id=? AND operation_id=?")){
            statement.setString(1,network);statement.setObject(2,id);assertEquals(1,statement.executeUpdate());SchemaMigrator.pruneResults(c,Duration.ofDays(7));
        }
        expect(ErrorCode.ALREADY_PROCESSED_RESULT_EXPIRED,data.set(key,2L,id));
        assertEquals(OperationStatus.State.RESULT_EXPIRED,await(store.namespace("contracts").operation(id)).state());
        assertEquals(2L,await(data.get(key)).orElseThrow());
        long storageErrorsBefore=store.metrics().storageErrors();
        try(Connection c=connection()){SchemaMigrator.rotateEpoch(c,network);}
        expect(ErrorCode.STALE_EPOCH,data.set(key,3L,UUID.randomUUID()));
        assertEquals(StoreState.DEGRADED,store.state());
        expect(ErrorCode.STALE_EPOCH,data.get(key));
        assertEquals(storageErrorsBefore+2,store.metrics().storageErrors(),"Both the DB rejection and the fail-fast degraded request count as storage errors");
        VarStore fresh=open("fresh");assertEquals(2L,await(data(fresh).get(key)).orElseThrow());await(data(fresh).set(key,4L));
    }

    @Test void adminVersionConflictAndAuditCommitTogether() throws Exception {
        VarStore first=open("admin"),second=open("concurrent");var data=data(first);var key=VarKey.longKey("admin-key");
        await(data.set(key,1L));VersionToken before=await(data.getVersioned(key)).orElseThrow().version();await(data(second).set(key,2L));
        UUID id=UUID.randomUUID();TransactionPlan stale=TransactionPlan.builder().requireVersion(data.target(key),before).set(data.target(key),3L).build().withAudit(new AuditContext("CONSOLE","SET"));
        assertEquals(Outcome.CONDITION_FAILED,await(first.namespace("contracts").execute(stale,id)).outcome());
        assertEquals(2L,await(data.get(key)).orElseThrow());
        try(Connection c=connection();var query=c.prepareStatement("SELECT count(*) FROM vs_admin_audit WHERE operation_id=?")){
            query.setObject(1,id);try(var rows=query.executeQuery()){rows.next();assertEquals(1,rows.getInt(1));}
        }
    }

    @Test void boundedQueueRejectsWithoutBlockingAndCancellationCannotUndoWrite() throws Exception {
        VarStore store=open(new StoreConfig(settings("queue"),1,2,8_388_608,Duration.ofSeconds(5),Duration.ofSeconds(2),3));var data=data(store);var key=VarKey.longKey("blocked");await(data.set(key,0L));
        UUID queuedId=UUID.randomUUID();
        try(Connection c=connection()){
            lock(c,"blocked");
            var running=data.increment(key,1,UUID.randomUUID());
            var queued=data.increment(key,1,queuedId);
            long begin=System.nanoTime();expect(ErrorCode.OVERLOADED,data.increment(key,1,UUID.randomUUID()));
            assertTrue(System.nanoTime()-begin<TimeUnit.MILLISECONDS.toNanos(250),"Admission must not wait for database locks");
            queued.toCompletableFuture().cancel(false);
            c.commit();await(running);
            eventually(()->await(store.namespace("contracts").operation(queuedId)).state()==OperationStatus.State.COMPLETED);
            assertEquals(2L,await(data.get(key)).orElseThrow(),"Detached future cancellation does not cancel the DB operation");
        }
    }

    @Test void composedRequestsWorkWithSingleRequestAdmission() throws Exception {
        VarStore store=open(new StoreConfig(settings("single"),1,1,8_388_608,Duration.ofSeconds(5),Duration.ofSeconds(2),3));
        var data=data(store);var key=VarKey.longKey("composed");await(data.set(key,0L));
        CompletionStage<Optional<Long>> composed;
        try(Connection c=connection()) {
            lock(c,"composed");
            composed=data.set(key,1L).thenCompose(receipt->data.get(key));
            c.commit();
        }
        assertEquals(1L,await(composed).orElseThrow());
    }

    @Test void callbacksReleaseConnectionAndUseSeparateExecutor() throws Exception {
        PostgresSettings one=new PostgresSettings(jdbc(),username(),password(),network,"callback","disable",true,1,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500));
        VarStore store=open(new StoreConfig(one,1,16,8_388_608,Duration.ofSeconds(5),Duration.ofSeconds(2),3));var data=data(store);var key=VarKey.longKey("callback");
        await(data.set(key,0L));
        CountDownLatch callbackStarted=new CountDownLatch(1),release=new CountDownLatch(1);List<String> names=new CopyOnWriteArrayList<>();
        CompletableFuture<Void> callback;
        try(Connection c=connection()) {
            lock(c,"callback");
            callback=data.set(key,1L).thenAccept(receipt->{names.add(Thread.currentThread().getName());callbackStarted.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}).toCompletableFuture();
            c.commit();
        }
        try{
            assertTrue(callbackStarted.await(5,TimeUnit.SECONDS));
            assertEquals(1L,data.get(key).toCompletableFuture().get(2,TimeUnit.SECONDS).orElseThrow());
            assertTrue(names.getFirst().startsWith("varstore-completion"),"Callback uses isolated completion executor");
        }finally{release.countDown();}
        callback.get(5,TimeUnit.SECONDS);
    }

    @Test void blockedCallbacksDoNotStarveOtherFuturesAndDeliveryCapacityStaysBounded() throws Exception {
        VarStore store=open(new StoreConfig(settings("delivery"),1,2,8_388_608,Duration.ofSeconds(5),Duration.ofSeconds(2),3));
        var data=data(store);var key=VarKey.longKey("delivery");await(data.set(key,0L));
        CountDownLatch started=new CountDownLatch(4),release=new CountDownLatch(1);
        List<CompletableFuture<Void>> blocked=new ArrayList<>();
        try {
            // Attach before SQL can finish, so callbacks exercise provider delivery
            // rather than the attaching test thread's completed-stage behavior.
            for(int index=0;index<2;index++) blocked.add(blockedDelivery(data,key,index+1L,started,release));
            eventually(()->started.getCount()==2);
            assertEquals(Outcome.APPLIED,data.set(key,10L).toCompletableFuture().get(2,TimeUnit.SECONDS).outcome(),"Two blocked consumer callbacks must not starve another write receipt");
            assertEquals(10L,data.get(key).toCompletableFuture().get(2,TimeUnit.SECONDS).orElseThrow(),"A separate read future must also complete");
            // queueMaxRequests + 2 is the independent delivery budget. Database
            // admission has already been released, but callbacks still own it.
            for(int index=0;index<2;index++) blocked.add(blockedDelivery(data,key,index+20L,started,release));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertEquals(0,store.metrics().queuedRequests(),"Completed DB requests do not occupy DB admission");
            assertEquals(4,store.metrics().pendingDeliveries(),"Blocked virtual callbacks retain the bounded delivery reservations");
            assertTrue(store.metrics().retainedRequestBytes()>0,"Callback ownership retains its accounted request bytes");
            UUID refused=UUID.randomUUID();
            assertEquals(refused,expect(ErrorCode.OVERLOADED,data.set(key,99L,refused)).operationId());
        } finally { release.countDown(); }
        CompletableFuture.allOf(blocked.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
        eventually(()->store.metrics().pendingDeliveries()==0&&store.metrics().retainedRequestBytes()==0);
        assertEquals(Outcome.APPLIED,data.set(key,100L).toCompletableFuture().get(2,TimeUnit.SECONDS).outcome(),"Releasing callbacks restores bounded delivery capacity");
        assertEquals(100L,await(data.get(key)).orElseThrow());
    }
    private CompletableFuture<Void> blockedDelivery(VarStore.Data data,VarKey<Long> key,long value,CountDownLatch started,CountDownLatch release) throws Exception {
        try(Connection c=connection()) {
            lock(c,key.name());
            CompletableFuture<Void> future=data.set(key,value).thenAccept(receipt->{
                started.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Blocked callback was not released"); }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new CompletionException(error);}
            }).toCompletableFuture();
            c.commit();return future;
        }
    }

    @Test void shutdownDistinguishesQueuedFromUnconfirmedWrite() throws Exception {
        PostgresSettings closing=new PostgresSettings(jdbc(),username(),password(),network,"closing","disable",true,4,Duration.ofSeconds(1),Duration.ofSeconds(4),Duration.ofSeconds(4));
        VarStore store=open(new StoreConfig(closing,1,4,8_388_608,Duration.ofSeconds(5),Duration.ofMillis(50),3));var data=data(store);var key=VarKey.longKey("closing");await(data.set(key,0L));
        try(Connection c=connection();Connection observer=connection()){
            lock(c,"closing");int blocker;
            try(var query=c.createStatement();var row=query.executeQuery("SELECT pg_backend_pid()")){row.next();blocker=row.getInt(1);}
            var running=data.increment(key,1,UUID.randomUUID());
            // A lease held by a pool borrower is not evidence the request started SQL.
            // Observe the actual writer waiting on this transaction's exact row lock.
            eventually(()->{
                try(var query=observer.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='VarStore/closing' AND ?=ANY(pg_blocking_pids(pid)))")){
                    query.setInt(1,blocker);try(var row=query.executeQuery()){row.next();return row.getBoolean(1);}
                }catch(SQLException error){throw new CompletionException(error);}
            });
            assertFalse(running.toCompletableFuture().isDone(),"Observed SQL remains blocked before drain");
            var queued=data.increment(key,1,UUID.randomUUID());store.close();
            expect(ErrorCode.UNKNOWN_COMMIT_OUTCOME,running);expect(ErrorCode.SHUTTING_DOWN,queued);
            expect(ErrorCode.SHUTTING_DOWN,data.get(key));assertEquals(StoreState.CLOSED,store.state());c.rollback();
        }
    }

    @Test void concurrentMigrationIsSerializedAndIdempotent() throws Exception {
        String provisioned="migrate-"+UUID.randomUUID();
        CountDownLatch start=new CountDownLatch(1);
        Runnable migrate=()->{
            try(Connection c=connection()) { start.await();SchemaMigrator.migrate(c,provisioned); }
            catch(Exception error) { throw new CompletionException(error); }
        };
        CompletableFuture<Void> first=CompletableFuture.runAsync(migrate),second=CompletableFuture.runAsync(migrate);
        start.countDown();await(CompletableFuture.allOf(first,second));
        try(Connection c=connection();var query=c.prepareStatement("SELECT count(*) FROM vs_networks WHERE network_id=?")) {
            query.setString(1,provisioned);try(var rows=query.executeQuery()){rows.next();assertEquals(1,rows.getInt(1));}
            SchemaMigrator.validate(c);
        }
    }

    @Test void byteAdmissionAndNamespaceAreCheckedBeforeStorage() {
        VarStore store=open(new StoreConfig(settings("bytes"),1,16,1024,Duration.ofSeconds(5),Duration.ofSeconds(2),3));
        var data=data(store);UUID id=UUID.randomUUID();
        assertEquals(id,expect(ErrorCode.OVERLOADED,data.set(VarKey.stringKey("payload"),"x".repeat(512),id)).operationId());
        assertEquals(OperationStatus.State.NOT_OBSERVED_YET,await(store.namespace("contracts").operation(id)).state());
        VarStore regular=open("namespace-check");
        var target=regular.namespace("elsewhere").network().system("test").target(VarKey.longKey("key"));
        UUID foreignId=UUID.randomUUID();
        assertEquals(foreignId,expect(ErrorCode.INVALID_ARGUMENT,regular.namespace("contracts").execute(TransactionPlan.builder().set(target,1L).build(),foreignId)).operationId());
    }

    @Test void invalidWritesReportOperationIdAndBoundsBeforeIo() {
        var data=data(open("validation"));UUID id=UUID.randomUUID();
        VarStoreException failure=expect(ErrorCode.VALUE_TOO_LARGE,data.set(VarKey.stringKey("large"),"😀".repeat(4097),id));assertEquals(id,failure.operationId());
        List<VarKey<?>> keys=new ArrayList<>();for(int i=0;i<65;i++)keys.add(VarKey.longKey("key"+i));expect(ErrorCode.INVALID_ARGUMENT,data.getAll(keys));
        assertThrows(VarStoreException.class,()->VarKey.stringKey("sql';drop/table"));
    }
}
