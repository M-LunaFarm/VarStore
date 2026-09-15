package kr.lunaf.varstore.postgres;

import kr.lunaf.varstore.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs against a real PostgreSQL instance; each run owns and removes one isolated schema. */
@EnabledIfEnvironmentVariable(named="VARSTORE_TEST_JDBC_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresContractTest {
    String baseUrl,url,user,password,schema;PostgresBackend a,b;
    @BeforeAll void prepare()throws Exception{
        baseUrl=System.getenv("VARSTORE_TEST_JDBC_URL");user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
        schema="test_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(baseUrl,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        url=baseUrl+(baseUrl.contains("?")?"&":"?")+"currentSchema="+schema;
        try(var c=connection()){SchemaMigrator.migrate(c,"test");}
        a=backend("a");b=backend("b");a.initialize();b.initialize();
    }
    @AfterAll void clean()throws Exception{
        if(a!=null)a.close();if(b!=null)b.close();
        if(schema!=null)try(var c=DriverManager.getConnection(baseUrl,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);}
    PostgresBackend backend(String server){return new PostgresBackend(new PostgresSettings(url,user,password,"test",server,"disable",true,4,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5)));}
    long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(30);}
    <T>Target<T> target(ValueType type){return new Target<>(new Address("test","contract",ScopeKind.NETWORK,"_",new Owner("SYSTEM","global"),"k"+UUID.randomUUID().toString().replace("-","")),type);}
    <T>WriteReceipt<T> set(PostgresBackend backend,Target<T> target,T value){return backend.write(target,WriteKind.SET,value,0,null,UUID.randomUUID(),deadline());}
    long value(Target<Long> target){return a.get(target,deadline()).orElseThrow().value();}
    void error(ErrorCode code,Runnable call){assertEquals(code,assertThrows(VarStoreException.class,call::run).code());}

    @Test void primitivesSurvivePoolRestartAndDeletionPreservesType(){
        Target<String> text=target(ValueType.STRING);Target<Long> number=target(ValueType.LONG);Target<Boolean> flag=target(ValueType.BOOLEAN);Target<UUID> uuid=target(ValueType.UUID);UUID id=UUID.randomUUID();
        set(a,text,"한글 😀");set(a,number,Long.MIN_VALUE);set(a,flag,false);set(a,uuid,id);
        try(var restarted=backend("restart")){restarted.initialize();assertEquals("한글 😀",restarted.get(text,deadline()).orElseThrow().value());assertEquals(Long.MIN_VALUE,restarted.get(number,deadline()).orElseThrow().value());assertFalse(restarted.get(flag,deadline()).orElseThrow().value());assertEquals(id,restarted.get(uuid,deadline()).orElseThrow().value());}
        a.write(text,WriteKind.DELETE,null,0,null,UUID.randomUUID(),deadline());assertTrue(b.get(text,deadline()).isEmpty());
        error(ErrorCode.TYPE_MISMATCH,()->set(a,new Target<Long>(text.address(),ValueType.LONG),1L));
    }
    @Test void twoIndependentServersPreserveTwoThousandIncrements()throws Exception{
        Target<Long> target=target(ValueType.LONG);set(a,target,0L);
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<?>> tasks=new ArrayList<>();for(PostgresBackend backend:List.of(a,b))tasks.add(executor.submit(()->{for(int i=0;i<1000;i++)backend.write(target,WriteKind.INCREMENT,null,1,null,UUID.randomUUID(),deadline());}));
            for(var task:tasks)task.get(120,TimeUnit.SECONDS);
        }assertEquals(2000L,value(target));
    }
    @Test void hundredConcurrentDuplicatesMutateOnceAndConflictIsRejected()throws Exception{
        Target<Long> target=target(ValueType.LONG);set(a,target,10L);UUID operation=UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(12)){
            List<Callable<WriteReceipt<Long>>> calls=new ArrayList<>();for(int i=0;i<100;i++){PostgresBackend backend=i%2==0?a:b;calls.add(()->backend.write(target,WriteKind.INCREMENT,null,7,null,operation,deadline()));}
            int original=0;for(var task:executor.invokeAll(calls)){var result=task.get();assertEquals(17L,result.value().orElseThrow());if(!result.replayed())original++;}assertEquals(1,original);
        }assertEquals(17L,value(target));error(ErrorCode.IDEMPOTENCY_KEY_REUSED,()->a.write(target,WriteKind.INCREMENT,null,8,null,operation,deadline()));assertEquals(17L,value(target));
    }
    @Test void casNoChangeAndDeleteRecreateProtectVersions(){
        Target<Long> target=target(ValueType.LONG);var first=set(a,target,3L);var same=set(b,target,3L);assertEquals(Outcome.NO_CHANGE,same.outcome());assertEquals(first.version(),same.version());
        set(b,target,4L);assertEquals(Outcome.CONDITION_FAILED,a.write(target,WriteKind.COMPARE_AND_SET,5L,0,first.version().orElseThrow(),UUID.randomUUID(),deadline()).outcome());
        a.write(target,WriteKind.DELETE,null,0,null,UUID.randomUUID(),deadline());set(b,target,3L);
        assertEquals(Outcome.CONDITION_FAILED,a.write(target,WriteKind.COMPARE_AND_SET,5L,0,first.version().orElseThrow(),UUID.randomUUID(),deadline()).outcome());assertEquals(3L,value(target));
    }
    @Test void failedTransactionRollsBackPlaceholdersAndDurablyReplaysFailure()throws Exception{
        Target<Long> existing=target(ValueType.LONG);Target<String> missing=target(ValueType.STRING);set(a,existing,8L);UUID id=UUID.randomUUID();
        var plan=TransactionPlan.builder().requireAbsent(existing).set(existing,9L).set(missing,"unexpected").build();
        assertEquals(Outcome.CONDITION_FAILED,a.execute(plan,id,deadline()).outcome());assertEquals(8L,value(existing));assertTrue(a.get(missing,deadline()).isEmpty());
        try(var c=connection();var s=c.prepareStatement("SELECT count(*) FROM vs_variables WHERE variable_key=?")){s.setString(1,missing.address().key());try(var rs=s.executeQuery()){rs.next();assertEquals(0,rs.getLong(1));}}
        set(b,existing,10L);assertTrue(a.execute(plan,id,deadline()).replayed());assertEquals(Outcome.CONDITION_FAILED,a.operation("contract",id,deadline()).receipt().orElseThrow().outcome());
    }
    @Test void overflowMissingAndMismatchedTypesNeverBecomeAbsence(){
        Target<Long> target=target(ValueType.LONG);error(ErrorCode.MISSING_VALUE,()->a.write(target,WriteKind.INCREMENT,null,1,null,UUID.randomUUID(),deadline()));
        set(a,target,Long.MAX_VALUE);error(ErrorCode.NUMERIC_OVERFLOW,()->a.write(target,WriteKind.INCREMENT,null,1,null,UUID.randomUUID(),deadline()));assertEquals(Long.MAX_VALUE,value(target));
        error(ErrorCode.TYPE_MISMATCH,()->a.get(new Target<String>(target.address(),ValueType.STRING),deadline()));
    }
    @Test void batchHasSingleSnapshotDuringAtomicUpdates()throws Exception{
        Target<Long> x=target(ValueType.LONG),y=target(ValueType.LONG);set(a,x,0L);set(a,y,0L);
        try(var executor=Executors.newSingleThreadExecutor()){
            Future<?> writing=executor.submit(()->{for(long i=1;i<=100;i++)b.execute(TransactionPlan.builder().set(x,i).set(y,i).build(),UUID.randomUUID(),deadline());});
            for(int i=0;i<100;i++){var read=a.getAll(List.of(x,y),deadline());assertEquals(read.get(x.address()).orElseThrow().value(),read.get(y.address()).orElseThrow().value());}writing.get();
        }
    }
    @Test void resultRetentionNeverDeletesDedupMarker()throws Exception{
        Target<Long> target=target(ValueType.LONG);set(a,target,1L);UUID id=UUID.randomUUID();a.write(target,WriteKind.INCREMENT,null,9,null,id,deadline());
        try(var c=connection();var s=c.prepareStatement("UPDATE vs_operations SET completed_at=clock_timestamp()-interval '8 days' WHERE operation_id=?")){s.setObject(1,id);s.executeUpdate();SchemaMigrator.pruneResults(c,Duration.ofDays(7));}
        assertEquals(OperationStatus.State.RESULT_EXPIRED,a.operation("contract",id,deadline()).state());error(ErrorCode.ALREADY_PROCESSED_RESULT_EXPIRED,()->a.write(target,WriteKind.INCREMENT,null,9,null,id,deadline()));assertEquals(10L,value(target));
    }
    @Test void auditIsAtomicAndDeduplicated(){
        Target<Long> target=target(ValueType.LONG);var initial=set(a,target,3L);UUID id=UUID.randomUUID();
        var plan=TransactionPlan.builder().requireVersion(target,initial.version().orElseThrow()).set(target,4L).build().withAudit(new AuditContext("CONSOLE","SET"));
        assertEquals(Outcome.APPLIED,a.execute(plan,id,deadline()).outcome());a.execute(plan,id,deadline());
        try(var c=connection();var s=c.prepareStatement("SELECT count(*) FROM vs_admin_audit WHERE operation_id=?")){s.setObject(1,id);try(var rs=s.executeQuery()){rs.next();assertEquals(1,rs.getInt(1));}}catch(SQLException error){throw new AssertionError(error);}
        UUID stale=UUID.randomUUID();assertEquals(Outcome.CONDITION_FAILED,a.execute(plan,stale,deadline()).outcome());assertEquals(4L,value(target));
    }
    @Test void concurrentMigrationIsSerializedAndSchemaTamperingRejected()throws Exception{
        String fresh=schema+"_migration";
        try(var c=connection();var statement=c.createStatement()){statement.execute("CREATE SCHEMA "+fresh);}
        try{
            try(var executor=Executors.newFixedThreadPool(2)){
                Callable<Void> migrate=()->{try(var c=connection()){c.setSchema(fresh);SchemaMigrator.migrate(c,"test");}return null;};
                var tasks=executor.invokeAll(List.of(migrate,migrate));for(var task:tasks)task.get();
            }
            try(var c=connection()){c.setSchema(fresh);SchemaMigrator.validate(c);}
        }finally{try(var c=connection();var statement=c.createStatement()){statement.execute("DROP SCHEMA "+fresh+" CASCADE");}}
        try(var c=connection();var s=c.createStatement()){s.execute("ALTER TABLE vs_variables ADD COLUMN unexpected integer");assertThrows(SQLException.class,()->SchemaMigrator.validate(c));s.execute("ALTER TABLE vs_variables DROP COLUMN unexpected");SchemaMigrator.validate(c);}
    }
    @Test void separateNetworkEpochFencesOldSessions()throws Exception{
        try(var c=connection()){SchemaMigrator.migrate(c,"epochtest");}
        var settings=new PostgresSettings(url,user,password,"epochtest","a","disable",true,2,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5));
        try(var old=new PostgresBackend(settings)){
            old.initialize();Target<Long> target=new Target<>(new Address("epochtest","contract",ScopeKind.NETWORK,"_",new Owner("SYSTEM","global"),"epoch"),ValueType.LONG);set(old,target,7L);
            try(var c=connection()){SchemaMigrator.rotateEpoch(c,"epochtest");}
            error(ErrorCode.STALE_EPOCH,()->old.get(target,deadline()));error(ErrorCode.STALE_EPOCH,()->set(old,target,9L));error(ErrorCode.STALE_EPOCH,old::initialize);
            try(var fresh=new PostgresBackend(settings)){fresh.initialize();assertEquals(7L,fresh.get(target,deadline()).orElseThrow().value());}
        }
    }
    @Test void expiredRequestDoesNotAcquireOrMutate(){Target<Long> target=target(ValueType.LONG);error(ErrorCode.REQUEST_TIMEOUT,()->a.write(target,WriteKind.SET,1L,0,null,UUID.randomUUID(),System.nanoTime()-1));assertTrue(a.get(target,deadline()).isEmpty());}
    @Test void realRowLockTimesOutWithoutMutationAndDiagnosticsObserveWait()throws Exception{
        Target<Long> target=target(ValueType.LONG);set(a,target,10L);UUID id=UUID.randomUUID();
        try(var c=connection();var lock=c.prepareStatement("SELECT revision FROM vs_variables WHERE variable_key=? FOR UPDATE");var executor=Executors.newSingleThreadExecutor()){
            c.setAutoCommit(false);lock.setString(1,target.address().key());try(var rs=lock.executeQuery()){assertTrue(rs.next());}
            Future<WriteReceipt<Long>> writing=executor.submit(()->b.write(target,WriteKind.INCREMENT,null,1,null,id,deadline()));
            long waiting=0;for(int i=0;i<50;i++){waiting=a.diagnostics(deadline()).get("lockWaitMicros");if(waiting>0)break;Thread.sleep(20);}
            assertTrue(waiting>0,"Real PostgreSQL lock wait must be observable");
            var failure=assertThrows(ExecutionException.class,()->writing.get(10,TimeUnit.SECONDS));assertEquals(ErrorCode.STORAGE_UNAVAILABLE,((VarStoreException)failure.getCause()).code());
            c.rollback();
        }
        assertEquals(10L,value(target));assertEquals(11L,b.<Long>write(target,WriteKind.INCREMENT,null,1,null,id,deadline()).value().orElseThrow());
        var metrics=a.diagnostics(deadline());assertTrue(metrics.get("tableBytes")>0);assertTrue(metrics.get("indexBytes")>0);
    }

    @Test void saturatedPoolHonorsShortAcquisitionDeadlineAndReturnsAbandonedLease()throws Exception{
        Target<Long> target=target(ValueType.LONG);set(a,target,10L);
        PostgresSettings settings=new PostgresSettings(url,user,password,"test","borrowtest","disable",true,1,Duration.ofSeconds(3),Duration.ofSeconds(5),Duration.ofSeconds(5));
        try(var constrained=new PostgresBackend(settings);var c=connection();var executor=Executors.newSingleThreadExecutor()){
            constrained.initialize();c.setAutoCommit(false);
            try(var lock=c.prepareStatement("SELECT revision FROM vs_variables WHERE variable_key=? FOR UPDATE")){
                lock.setString(1,target.address().key());try(var rs=lock.executeQuery()){assertTrue(rs.next());}
            }
            Future<WriteReceipt<Long>> holding=executor.submit(()->constrained.write(target,WriteKind.INCREMENT,null,1,null,UUID.randomUUID(),deadline()));
            try{
                long started=System.nanoTime();
                while(constrained.activeConnections()!=1 && System.nanoTime()-started<TimeUnit.SECONDS.toNanos(2))Thread.sleep(5);
                assertEquals(1,constrained.activeConnections(),"An actual transaction must occupy the sole connection");
                long requestStart=System.nanoTime();
                error(ErrorCode.REQUEST_TIMEOUT,()->constrained.get(target,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(60)));
                assertTrue(System.nanoTime()-requestStart<TimeUnit.MILLISECONDS.toNanos(600),"Caller deadline must beat Hikari's 3000ms timeout");
                // More waiting callers than physical connections must remain valid admissions.
                error(ErrorCode.REQUEST_TIMEOUT,()->constrained.get(target,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(60)));
            }finally{c.rollback();}
            assertEquals(11L,holding.get(10,TimeUnit.SECONDS).value().orElseThrow());
            long cleanupStart=System.nanoTime();
            while(constrained.activeConnections()!=0 && System.nanoTime()-cleanupStart<TimeUnit.SECONDS.toNanos(2))Thread.sleep(5);
            assertEquals(0,constrained.activeConnections(),"A late lease must be returned after its caller timed out");
            // Wait until the bounded borrower exits its cleanup, then prove the same pool remains usable.
            for(int attempt=0;;attempt++){
                try{assertEquals(11L,constrained.get(target,deadline()).orElseThrow().value());break;}
                catch(VarStoreException busy){if(busy.code()!=ErrorCode.OVERLOADED||attempt>=100)throw busy;Thread.sleep(5);}
            }
        }
    }

    @Test void schemaDigestSurvivesConstraintDeparseAndReparse()throws Exception{
        // pg_dump emits pg_get_constraintdef text. Reparse that exact text as pg_restore does;
        // nested BETWEEN+AND parse trees used to change their digest after a logical restore.
        try(var c=connection()){
            c.setAutoCommit(false);
            try{
                String definition;
                try(var statement=c.prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='vs_variables'::regclass AND conname='vs_variables_owner_id_check'");var rs=statement.executeQuery()){
                    assertTrue(rs.next());definition=rs.getString(1);
                }
                try(var statement=c.createStatement()){
                    statement.execute("ALTER TABLE vs_variables DROP CONSTRAINT vs_variables_owner_id_check");
                    statement.execute("ALTER TABLE vs_variables ADD CONSTRAINT vs_variables_owner_id_check "+definition);
                }
                SchemaMigrator.validate(c);
            }finally{c.rollback();}
        }
    }

}
