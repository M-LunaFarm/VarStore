package kr.lunaf.varstore.testkit;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.core.*;
import kr.lunaf.varstore.postgres.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Coordinated with outage-test.py: actual container outages, one continuous core client. */
public final class OutageHarness {
    private final String jdbc=Objects.requireNonNull(System.getenv("VARSTORE_TEST_JDBC_URL"));
    private final String user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");
    private final String password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
    private final String network="soak-"+UUID.randomUUID();
    private final VarKey<Long> counter=VarKey.longKey("counter");
    private final ConcurrentMap<UUID,Boolean> unresolved=new ConcurrentHashMap<>();
    private final Set<UUID> issuedIds=ConcurrentHashMap.newKeySet();
    private final AtomicInteger outstanding=new AtomicInteger();
    private final AtomicLong readSuccesses=new AtomicLong(),readErrors=new AtomicLong(),writeSuccesses=new AtomicLong(),writeErrors=new AtomicLong(),reconciled=new AtomicLong(),replayed=new AtomicLong();
    private final ConcurrentMap<String,AtomicLong> errorCounts=new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> fatal=new AtomicReference<>();
    private final List<Map<String,Object>> samples=new ArrayList<>();
    private final ScheduledExecutorService workload=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"outage-workload"));
    private final Object production=new Object();
    private volatile boolean paused=true;
    private VarStore store;private VarStore.Data data;
    private long begun;private int recoveredCycles;private long baselineHeap;

    public static void main(String[] args)throws Exception{new OutageHarness().run(Path.of(args.length==0?".local/outage-harness.json":args[0]));}
    private void run(Path output)throws Exception{
        if(!jdbc.equals("jdbc:postgresql://127.0.0.1:25435/varstore_soak"))throw new IllegalArgumentException("Harness only targets dedicated loopback varstore_soak DB");
        Map<String,Object> report=new LinkedHashMap<>();report.put("startedAt",Instant.now().toString());report.put("network",network);
        report.put("scope","one continuous Java21 core client; actual repeated PostgreSQL18 container outages; no Paper tick-latency claim");
        report.put("limits",Map.of("dbWorkers",2,"maximumPoolConnections",2,"queueMaxRequests",32,"queueMaxBytes",262144,"requestTimeoutMillis",1500,"borrowerThreadCap",64,"observedVarStoreThreadCeiling",80,"usedHeapCeilingBytes",134217728,"postGcGrowthCeilingBytes",33554432));
        begun=System.nanoTime();
        try{
            Class.forName("org.postgresql.Driver");try(var c=connection()){SchemaMigrator.migrate(c,network);}
            var settings=new PostgresSettings(jdbc,user,password,network,"soak","disable",true,2,Duration.ofMillis(750),Duration.ofMillis(300),Duration.ofMillis(900));
            store=StoreFactory.open(new StoreConfig(settings,2,32,262144,Duration.ofMillis(1500),Duration.ofSeconds(5),3));await(store.ready());
            data=store.namespace("soak").network().system("global");await(data.set(counter,0L));
            baselineHeap=postGcHeap();sample("baseline",baselineHeap);resume();
            workload.scheduleAtFixedRate(this::produce,0,50,TimeUnit.MILLISECONDS);
            emit("OUTAGE_READY");
            try(var input=new BufferedReader(new InputStreamReader(System.in))){
                for(String line;(line=input.readLine())!=null;){
                    String[] words=line.split(" ");
                    if(words[0].equals("DOWN"))observeDown(Integer.parseInt(words[1]));
                    else if(words[0].equals("UP"))recover(Integer.parseInt(words[1]));
                    else if(words[0].equals("FINISH")){recover(-1);break;}
                    else throw new IllegalArgumentException("Unknown harness command");
                    checkFatal();
                }
            }
            checkFatal();if(recoveredCycles<10)throw new AssertionError("At least ten actual recovered cycles are required");
            long stored=await(data.get(counter)).orElseThrow();
            if(stored!=issuedIds.size()||!unresolved.isEmpty())throw new AssertionError("Independent operation IDs do not match final stored increments");
            report.put("status","PASS");report.put("storedCounter",stored);report.put("uniqueWriteIds",issuedIds.size());
        }catch(Throwable error){report.put("status","FAIL");report.put("failure",error.getClass().getSimpleName()+": "+error.getMessage());throw error;}
        finally{
            pause();workload.shutdownNow();workload.awaitTermination(5,TimeUnit.SECONDS);
            if(store!=null)store.close();
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(varstoreThreads()>0&&System.nanoTime()<limit)Thread.sleep(25);
            long finalHeap=postGcHeap();long threads=varstoreThreads();int connections=connections();
            sample("after_close",finalHeap);report.put("elapsedSeconds",(System.nanoTime()-begun)/1_000_000_000.0);report.put("cycles",recoveredCycles);
            report.put("readSuccesses",readSuccesses.get());report.put("readErrors",readErrors.get());report.put("writeFirstAttemptSuccesses",writeSuccesses.get());report.put("writeFirstAttemptErrors",writeErrors.get());report.put("stableIdReconciliations",reconciled.get());report.put("replayedReceipts",replayed.get());
            Map<String,Long> errors=new TreeMap<>();errorCounts.forEach((key,value)->errors.put(key,value.get()));report.put("errors",errors);
            report.put("resources",samples);report.put("baselinePostGcHeapBytes",baselineHeap);report.put("finalPostGcHeapBytes",finalHeap);report.put("postGcHeapGrowthBytes",finalHeap-baselineHeap);
            report.put("varstoreThreadsAfterClose",threads);report.put("clientConnectionsAfterClose",connections);report.put("outstandingAfterClose",outstanding.get());
            report.put("limitations",List.of("A finite outage rehearsal observes resource bounds during this interval; it cannot prove freedom from all long-term leaks.","Heap measurements include the harness's retained unique operation-ID set and JVM housekeeping.","Heap checkpoints explicitly request GC; normal interval samples do not.","A successful read racing with container stop may reflect a commit before the outage; each fully stopped interval separately requires failed reads and failed writes."));
            if(threads!=0||connections!=0||outstanding.get()!=0||finalHeap-baselineHeap>33554432){report.put("status","FAIL");report.put("cleanupFailure",true);}
            Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,json(report)+"\n");emit("OUTAGE_FINISHED "+report.get("status"));
            if(!"PASS".equals(report.get("status")))throw new AssertionError("Outage rehearsal failed; inspect report");
        }
    }
    private void produce(){
        synchronized(production){
            if(paused)return;
            try{
                UUID id=UUID.randomUUID();if(!issuedIds.add(id))throw new AssertionError("Duplicate generated operation ID");unresolved.put(id,true);
                outstanding.incrementAndGet();data.increment(counter,1,id).whenComplete((receipt,error)->{
                    try{if(error==null){writeSuccesses.incrementAndGet();unresolved.remove(id);if(receipt.replayed())replayed.incrementAndGet();}else{writeErrors.incrementAndGet();record(error);}}
                    catch(Throwable problem){fatal.compareAndSet(null,problem);}finally{outstanding.decrementAndGet();}
                });
                outstanding.incrementAndGet();data.getOrDefault(counter,-999L).whenComplete((value,error)->{
                    try{if(error==null){if(value<0)throw new AssertionError("DB outage became a fallback/default value");readSuccesses.incrementAndGet();}else{readErrors.incrementAndGet();record(error);}}
                    catch(Throwable problem){fatal.compareAndSet(null,problem);}finally{outstanding.decrementAndGet();}
                });
                checkMetrics();
            }catch(Throwable problem){fatal.compareAndSet(null,problem);}
        }
    }
    private void observeDown(int cycle)throws Exception{
        long reads=readErrors.get(),writes=writeErrors.get(),limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while((readErrors.get()==reads||writeErrors.get()==writes)&&System.nanoTime()<limit){checkFatal();Thread.sleep(25);}
        if(readErrors.get()==reads||writeErrors.get()==writes)throw new AssertionError("Fully stopped interval did not produce explicit read and write failures");
        sample("down_"+cycle,-1);emit("OUTAGE_DOWN_OBSERVED "+cycle);
    }
    private void recover(int cycle)throws Exception{
        pause();long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while((outstanding.get()!=0||store.state()!=StoreState.READY)&&System.nanoTime()<limit){checkFatal();Thread.sleep(25);}
        if(outstanding.get()!=0||store.state()!=StoreState.READY)throw new AssertionError("Core did not recover READY and drain requests");
        for(UUID id:new ArrayList<>(unresolved.keySet())){
            var receipt=await(data.increment(counter,1,id));if(receipt.replayed())replayed.incrementAndGet();
            var status=await(store.namespace("soak").operation(id));if(status.state()!=OperationStatus.State.COMPLETED)throw new AssertionError("Retried original operation has no committed result");
            if(unresolved.remove(id)!=null)reconciled.incrementAndGet();
        }
        long stored=await(data.get(counter)).orElseThrow();if(stored!=issuedIds.size())throw new AssertionError("Unique write-ID count differs from recovered counter");
        sample(cycle<0?"final_recovered":"recovered_"+cycle,postGcHeap());
        if(cycle>=0){recoveredCycles++;emit("OUTAGE_RECOVERED "+cycle);resume();}
    }
    private void record(Throwable error){
        while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();
        if(!(error instanceof VarStoreException exception))throw new AssertionError("Unexpected failure class",error);
        if(!Set.of(ErrorCode.STORAGE_UNAVAILABLE,ErrorCode.UNKNOWN_COMMIT_OUTCOME,ErrorCode.OVERLOADED,ErrorCode.REQUEST_TIMEOUT,ErrorCode.NOT_READY).contains(exception.code()))throw new AssertionError("Unexpected storage error: "+exception.code());
        errorCounts.computeIfAbsent(exception.code().name(),ignored->new AtomicLong()).incrementAndGet();
    }
    private void checkMetrics(){StoreMetrics m=store.metrics();if(m.queuedRequests()>32||m.queuedBytes()>262144||m.activeConnections()>2)throw new AssertionError("Configured queue or pool bound exceeded");}
    private void pause(){synchronized(production){paused=true;}}
    private void resume(){synchronized(production){paused=false;}}
    private void checkFatal(){Throwable failure=fatal.get();if(failure!=null)throw new AssertionError("Asynchronous contract failure",failure);}
    private void sample(String point,long postGc){
        long used=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();long threads=varstoreThreads();
        if(used>134217728||threads>80)throw new AssertionError("Observed resource ceiling exceeded");
        Map<String,Object> values=new LinkedHashMap<>();values.put("point",point);values.put("elapsedSeconds",(System.nanoTime()-begun)/1_000_000_000.0);values.put("heapUsedBytes",used);values.put("postGcHeapBytes",postGc);values.put("jvmLiveThreads",ManagementFactory.getThreadMXBean().getThreadCount());values.put("varstoreThreads",threads);values.put("outstanding",outstanding.get());values.put("unresolvedIds",unresolved.size());
        if(store!=null&&store.state()!=StoreState.CLOSED){checkMetrics();StoreMetrics m=store.metrics();values.put("activeConnections",m.activeConnections());values.put("queuedRequests",m.queuedRequests());values.put("queuedBytes",m.queuedBytes());values.put("state",store.state().name());}
        samples.add(values);
    }
    private long postGcHeap()throws InterruptedException{System.gc();Thread.sleep(150);return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static long varstoreThreads(){return Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().toLowerCase(Locale.ROOT).startsWith("varstore")).count();}
    private int connections(){try(var c=connection();var s=c.createStatement();var rs=s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='VarStore/soak'")){rs.next();return rs.getInt(1);}catch(SQLException failure){return -1;}}
    private Connection connection()throws SQLException{return DriverManager.getConnection(jdbc,user,password);}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static void emit(String marker){System.out.println(marker);System.out.flush();}
    private static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){List<String> items=new ArrayList<>();map.forEach((key,item)->items.add(json(key.toString())+":"+json(item)));return "{"+String.join(",",items)+"}";}
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(OutageHarness::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
    }
}
