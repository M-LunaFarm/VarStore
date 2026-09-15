package kr.lunaf.varstore.testkit;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.core.*;
import kr.lunaf.varstore.postgres.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/**
 * Actual primary-DB load and capacity measurements through three independent core
 * clients. This standalone process never represents its submissions as Paper game
 * thread measurements. Uses only a newly provisioned disposable benchmark network.
 */
public final class LoadHarness {
    private static final int KEY_COUNT=100_000, CLIENTS=3;
    private static final String NAMESPACE="benchmark";
    private final String jdbc=required("VARSTORE_TEST_JDBC_URL");
    private final String username=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");
    private final String password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
    private final String network="load-"+UUID.randomUUID();
    private final List<VarStore> stores=new ArrayList<>();
    private final List<VarStore.Data> handles=new ArrayList<>();
    private final List<Sample> samples=Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String,Object>> resources=new ArrayList<>();
    private final List<Map<String,Object>> phases=new ArrayList<>();
    private final AtomicInteger outstanding=new AtomicInteger();
    private final int rate;
    private int transactionPayloadBytes;
    private long transactionEstimatedBytes;
    private LoadHarness(int rate){this.rate=rate;}

    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("seed-paper")){seedPaper(args[1]);return;}
        Path output=Path.of(args.length>0?args[0]:"verification");
        int baseline=args.length>1?Integer.parseInt(args[1]):60;
        int variant=args.length>2?Integer.parseInt(args[2]):20;
        int rate=args.length>3?Integer.parseInt(args[3]):100;
        if(baseline<1||variant<1||rate<1||rate>10_000)throw new IllegalArgumentException("Positive durations and rate 1..10000 required");
        Files.createDirectories(output);
        new LoadHarness(rate).run(output,baseline,variant);
    }
    /** Prepare the explicitly named disposable Paper benchmark network without timing seeding as API load. */
    private static void seedPaper(String network)throws Exception {
        Names.identifier(network,"benchmark network");
        String jdbc=required("VARSTORE_TEST_JDBC_URL"),username=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore"),password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
        int inserted=0;long begin=System.nanoTime();
        try(Connection c=DriverManager.getConnection(jdbc,username,password)){
            SchemaMigrator.migrate(c,network);
            c.setAutoCommit(false);
            try(var s=c.prepareStatement("INSERT INTO vs_variables(network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,value_type,string_value,generation,revision,deleted,last_writer) SELECT ?,'varstorebench','NETWORK','_','SYSTEM','load','key/'||g,'STRING',repeat('x',1024),gen_random_uuid(),1,false,'bench-seed' FROM generate_series(0,99999) g ON CONFLICT DO NOTHING")){
                s.setString(1,network);inserted+=s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO vs_variables(network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,value_type,string_value,generation,revision,deleted,last_writer) SELECT ?,'varstorebench','NETWORK','_','SYSTEM','load',name,'STRING',payload,gen_random_uuid(),1,false,'bench-seed' FROM (SELECT 'large' AS name,repeat('x',16384) AS payload UNION ALL SELECT 'txn/'||node||'/'||g,repeat('x',1024) FROM generate_series(0,2) node CROSS JOIN generate_series(0,15) g) seed ON CONFLICT DO NOTHING")){
                s.setString(1,network);inserted+=s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO vs_variables(network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,value_type,long_value,generation,revision,deleted,last_writer) VALUES(?,'varstorebench','NETWORK','_','SYSTEM','load','hot','LONG',0,gen_random_uuid(),1,false,'bench-seed') ON CONFLICT DO NOTHING")){
                s.setString(1,network);inserted+=s.executeUpdate();
            }
            c.commit();
        }
        System.out.println(json(Map.of("event","paper-benchmark-seeded","network",network,"insertedRows",inserted,"baselineKeys",KEY_COUNT,"elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin),"method","SQL dataset preparation; timed benchmark uses the public API from Paper main threads")));
    }
    private void run(Path output,int baseline,int variant)throws Exception {
        Instant started=Instant.now();Map<String,Object> report=new LinkedHashMap<>();
        report.put("startedAt",started.toString());report.put("network",network);
        report.put("topology","three independent VarStore core clients in one standalone JVM, direct PostgreSQL primary");
        report.put("paperSubmissionMeasurement",false);
        report.put("threadMeasurementScope","ThreadMXBean/getAllStackTraces enumerate platform threads; virtual callback delivery threads are not included in thread-count gauges");
        report.put("paperDatabaseRelationship",System.getenv().getOrDefault("VARSTORE_LOAD_PAPER_TOPOLOGY","not established; inspect Paper evidence separately"));
        report.put("paperConcurrencyEvidence",System.getenv().getOrDefault("VARSTORE_LOAD_PAPER_EVIDENCE","not supplied; no simultaneous Paper gameplay claim"));
        report.put("requestedProfile",Map.of("keys",KEY_COUNT,"clients",CLIENTS,"requestsPerSecond",rate,"readPercent",70,"writePercent",30,"baselineValueBytes",1024));
        report.put("referenceHardware",Map.of("databaseVcpu",4,"databaseMemoryGiB",8,"storage","SSD","roundTripMsMaximum",2,"matchedByThisRun",false));
        report.put("runtime",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"arch",System.getProperty("os.arch"),"visibleProcessors",Runtime.getRuntime().availableProcessors(),"jvmMaxHeapBytes",Runtime.getRuntime().maxMemory()));
        report.put("limitations",List.of("Standalone API submission latency is not Paper main-thread submission latency.","A two-minute run can identify growth during that interval, not prove absence of long-term leaks.","Table and index byte deltas are relation-wide and may include simultaneous external workloads.","Seed data are generated through SQL; timed requests all use the public API.","Timed strings use deterministic pseudorandom ASCII; all 16 transaction keys share each request payload.","Hardware is observed separately by the wrapper and is not assumed to match the reference 4-vCPU/8-GiB host."));
        try {
            long seedStart=System.nanoTime();seed();report.put("seedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-seedStart));
            for(int i=0;i<CLIENTS;i++){
                var settings=new PostgresSettings(jdbc,username,password,network,"load-"+i,"disable",true,4,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500));
                VarStore store=StoreFactory.open(StoreConfig.defaults(settings));stores.add(store);await(store.ready());handles.add(store.namespace(NAMESPACE).network().system("global"));
            }
            await(handles.getFirst().set(VarKey.longKey("hot"),0L));
            await(handles.getFirst().set(VarKey.stringKey("large"),"x".repeat(16_384)));
            TransactionPlan empty=transaction(handles.getFirst(),"");
            transactionPayloadBytes=(int)((65_536-empty.estimatedBytes())/16);
            TransactionPlan maximum=transaction(handles.getFirst(),"x".repeat(transactionPayloadBytes));
            transactionEstimatedBytes=maximum.estimatedBytes();
            await(stores.getFirst().namespace(NAMESPACE).execute(maximum,UUID.randomUUID()));
            Map<String,Object> before=capacity();report.put("capacityBefore",before);
            resources.add(resource("before"));
            phase("baseline",baseline);phase("large_16k",variant);phase("maximum_transaction",variant);phase("hot_key",variant);
            resources.add(resource("after_work"));
            Map<String,Object> after=capacity();report.put("capacityAfter",after);
            report.put("committedHotIncrements",await(handles.getFirst().get(VarKey.longKey("hot"))).orElseThrow());
            long expectedHot=samples.stream().filter(s->s.phase.equals("hot_key")&&s.operation.equals("write")&&s.error.isEmpty()).count();
            report.put("expectedHotIncrements",expectedHot);
            if(!Objects.equals(report.get("committedHotIncrements"),expectedHot))throw new AssertionError("Independent hot-key write count differs from stored value");
            report.put("transactionKeys",16);report.put("transactionPayloadBytesPerKey",transactionPayloadBytes);report.put("transactionEstimatedBytes",transactionEstimatedBytes);
            report.put("phases",phases);report.put("resources",resources);
            report.put("loadErrors",samples.stream().filter(s->!s.error.isEmpty()).count());
            report.put("readSla",Map.of("p95Millis",25,"p99Millis",100));report.put("writeSla",Map.of("p95Millis",50,"p99Millis",150));report.put("submissionSlaP99Micros",1000);
            report.put("status","measured");
        } catch(Throwable error){report.put("status","failed");report.put("failureClass",error.getClass().getSimpleName());throw error;}
        finally {
            for(VarStore store:stores)store.close();
            long settle=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(countStoreThreads()>0&&System.nanoTime()<settle)Thread.sleep(25);
            resources.add(resource("after_close"));
            report.put("resources",resources);report.put("phases",phases);report.put("finishedAt",Instant.now().toString());
            report.put("varstoreThreadsAfterClose",countStoreThreads());
            report.put("apiClientConnectionsAfterClose",activeClientConnections());
            Files.writeString(output.resolve("load-report.json"),json(report)+"\n",StandardCharsets.UTF_8);
            writeSamples(output.resolve("load-requests.csv"));writeResources(output.resolve("load-resources.csv"));
            System.out.println(json(Map.of("report",output.resolve("load-report.json").toString(),"status",report.get("status"),"requests",samples.size())));
        }
    }
    private void phase(String name,int seconds)throws Exception {
        int count=Math.multiplyExact(rate,seconds);long beginning=System.nanoTime();int offset=samples.size();
        SplittableRandom random=new SplittableRandom(42L+name.hashCode());
        for(int i=0;i<count;i++){
            long scheduled=beginning+(long)i*1_000_000_000L/rate;
            while(System.nanoTime()<scheduled)LockSupport.parkNanos(Math.min(scheduled-System.nanoTime(),1_000_000));
            if(i%(rate*5)==0)resources.add(resource(name+"_"+i/rate+"s"));
            int client=i%CLIENTS;boolean write=i%10>=7;
            int index=random.nextInt(KEY_COUNT);String value=write?value(name,i):null;
            long start=System.nanoTime();CompletionStage<?> result;
            Sample sample=new Sample(name,i,client,write?"write":"read",Math.max(0,start-scheduled),start);
            outstanding.incrementAndGet();
            try {result=request(name,client,write,index,value);}
            catch(Throwable error){result=CompletableFuture.failedStage(error);}
            sample.submissionNanos=System.nanoTime()-start;
            samples.add(sample);
            result.whenComplete((receipt,error)->{
                sample.latencyNanos=System.nanoTime()-start;
                Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                sample.error=cause==null?"":cause instanceof VarStoreException v?v.code().name():cause.getClass().getSimpleName();
                outstanding.decrementAndGet();
            });
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(outstanding.get()>0&&System.nanoTime()<deadline)Thread.sleep(10);
        if(outstanding.get()>0)throw new AssertionError("Outstanding requests did not drain");
        List<Sample> slice=new ArrayList<>(samples.subList(offset,samples.size()));
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("name",name);summary.put("scheduledRequests",count);summary.put("scheduledSeconds",seconds);
        summary.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-beginning));
        summary.put("reads",summary(slice.stream().filter(s->s.operation.equals("read")).toList()));
        summary.put("writes",summary(slice.stream().filter(s->s.operation.equals("write")).toList()));
        summary.put("submissionP99Micros",percentile(slice.stream().mapToLong(s->s.submissionNanos).toArray(),.99)/1000.0);
        summary.put("scheduleLagP99Micros",percentile(slice.stream().mapToLong(s->s.scheduleLagNanos).toArray(),.99)/1000.0);
        summary.put("drained",true);phases.add(summary);System.out.println(json(summary));
    }
    private CompletionStage<?> request(String phase,int client,boolean write,int index,String value){
        VarStore.Data data=handles.get(client);
        return switch(phase){
            case "baseline"->{var key=VarKey.stringKey("key/"+index);yield write?data.set(key,value,UUID.randomUUID()):data.get(key);}
            case "large_16k"->{var key=VarKey.stringKey("large");yield write?data.set(key,value,UUID.randomUUID()):data.get(key);}
            case "hot_key"->{var key=VarKey.longKey("hot");yield write?data.increment(key,1,UUID.randomUUID()):data.get(key);}
            case "maximum_transaction"->{
                if(write)yield stores.get(client).namespace(NAMESPACE).execute(transaction(data,value),UUID.randomUUID());
                List<VarKey<?>> keys=new ArrayList<>();for(int i=0;i<16;i++)keys.add(VarKey.stringKey("txn/"+i));yield data.getAll(keys);
            }
            default->throw new IllegalArgumentException("Unknown phase");
        };
    }
    private String value(String phase,int index){
        int size=phase.equals("large_16k")?16_384:phase.equals("maximum_transaction")?transactionPayloadBytes:1024;
        SplittableRandom random=new SplittableRandom(((long)phase.hashCode()<<32)^index);
        char[] text=new char[size];String alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for(int i=0;i<size;i++)text[i]=alphabet.charAt(random.nextInt(alphabet.length()));return new String(text);
    }
    private TransactionPlan transaction(VarStore.Data data,String value){var builder=TransactionPlan.builder();for(int i=0;i<16;i++)builder.set(data.target(VarKey.stringKey("txn/"+i)),value);return builder.build();}
    private Map<String,Object> summary(List<Sample> subset){
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("count",subset.size());summary.put("errors",subset.stream().filter(s->!s.error.isEmpty()).count());
        long[] latencies=subset.stream().filter(s->s.error.isEmpty()).mapToLong(s->s.latencyNanos).toArray();
        summary.put("successP50Millis",percentile(latencies,.5)/1e6);summary.put("successP95Millis",percentile(latencies,.95)/1e6);summary.put("successP99Millis",percentile(latencies,.99)/1e6);
        Map<String,Long> failures=new TreeMap<>();for(Sample s:subset)if(!s.error.isEmpty())failures.merge(s.error,1L,Long::sum);summary.put("errorCodes",failures);return summary;
    }
    private void seed()throws SQLException {
        try(Connection c=connection()){
            SchemaMigrator.migrate(c,network);
            try(var s=c.prepareStatement("INSERT INTO vs_variables(network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,value_type,string_value,generation,revision,deleted,last_writer) SELECT ?,?,'NETWORK','_','SYSTEM','global','key/'||g,'STRING',repeat('x',1024),gen_random_uuid(),1,false,'load-seed' FROM generate_series(0,99999) g")){
                s.setString(1,network);s.setString(2,NAMESPACE);if(s.executeUpdate()!=KEY_COUNT)throw new AssertionError("Seed did not create 100000 keys");
            }
        }
    }
    private Map<String,Object> capacity()throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();
        try(Connection c=connection()){
            long[] roundTrips=new long[30];
            try(var statement=c.createStatement()){
                for(int i=0;i<roundTrips.length;i++){long begin=System.nanoTime();try(var rows=statement.executeQuery("SELECT 1")){rows.next();}roundTrips[i]=System.nanoTime()-begin;}
            }
            result.put("selectOneRoundTripP50Micros",percentile(roundTrips,.5)/1000.0);
            result.put("selectOneRoundTripP95Micros",percentile(roundTrips,.95)/1000.0);
            result.put("roundTripMeasurement","warm JDBC SELECT 1, including driver and database processing");
            try(var s=c.prepareStatement("SELECT count(*),avg(pg_column_size(v)) FROM vs_variables v WHERE network_id=?")){s.setString(1,network);try(var r=s.executeQuery()){r.next();result.put("networkVariableRows",r.getLong(1));result.put("averageVariableRowBytes",r.getDouble(2));}}
            try(var s=c.prepareStatement("SELECT count(*),coalesce(avg(pg_column_size(o)),0) FROM vs_operations o WHERE network_id=?")){s.setString(1,network);try(var r=s.executeQuery()){r.next();result.put("networkOperationRows",r.getLong(1));result.put("averageOperationRowBytes",r.getDouble(2));}}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT pg_relation_size('vs_variables'),pg_indexes_size('vs_variables'),pg_relation_size('vs_operations'),pg_indexes_size('vs_operations'),pg_database_size(current_database()),current_setting('server_version'),current_setting('fsync'),current_setting('full_page_writes'),current_setting('synchronous_commit')")){
                r.next();String[] names={"variablesTableBytes","variablesIndexBytes","operationsTableBytes","operationsIndexBytes","databaseBytes"};for(int i=0;i<names.length;i++)result.put(names[i],r.getLong(i+1));result.put("postgresVersion",r.getString(6));result.put("fsync",r.getString(7));result.put("fullPageWrites",r.getString(8));result.put("synchronousCommit",r.getString(9));
            }
        }return result;
    }
    private Map<String,Object> resource(String point){
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();Map<String,Object> result=new LinkedHashMap<>();
        result.put("point",point);result.put("at",Instant.now().toString());result.put("heapUsedBytes",memory.getUsed());result.put("heapCommittedBytes",memory.getCommitted());result.put("threadCount",ManagementFactory.getThreadMXBean().getThreadCount());result.put("varstoreThreads",countStoreThreads());result.put("outstanding",outstanding.get());
        long connections=0,queued=0,bytes=0,pending=0,retained=0;for(VarStore store:stores){if(store.state()!=StoreState.CLOSED){StoreMetrics m=store.metrics();connections+=m.activeConnections();queued+=m.queuedRequests();bytes+=m.queuedBytes();pending+=m.pendingDeliveries();retained+=m.retainedRequestBytes();}}
        result.put("activeConnections",connections);result.put("queuedRequests",queued);result.put("queuedBytes",bytes);result.put("pendingDeliveries",pending);result.put("retainedRequestBytes",retained);return result;
    }
    private int activeClientConnections(){try(Connection c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'VarStore/load-%'")){r.next();return r.getInt(1);}catch(SQLException e){return -1;}}
    private static long countStoreThreads(){return Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&(t.getName().startsWith("varstore-")||t.getName().startsWith("VarStore-load-")||t.getName().startsWith("VarStore-borrow-load-"))).count();}
    private void writeSamples(Path path)throws IOException {
        try(var out=Files.newBufferedWriter(path)){out.write("phase,index,client,operation,submission_us,latency_us,schedule_lag_us,error\n");for(Sample s:samples)out.write(s.phase+","+s.index+","+s.client+","+s.operation+","+s.submissionNanos/1000.0+","+s.latencyNanos/1000.0+","+s.scheduleLagNanos/1000.0+","+s.error+"\n");}
    }
    private void writeResources(Path path)throws IOException {try(var out=Files.newBufferedWriter(path)){out.write("point,at,heap_used_bytes,heap_committed_bytes,thread_count,varstore_threads,outstanding,active_connections,queued_requests,queued_bytes,pending_deliveries,retained_request_bytes\n");for(var r:resources)out.write(r.get("point")+","+r.get("at")+","+r.get("heapUsedBytes")+","+r.get("heapCommittedBytes")+","+r.get("threadCount")+","+r.get("varstoreThreads")+","+r.get("outstanding")+","+r.get("activeConnections")+","+r.get("queuedRequests")+","+r.get("queuedBytes")+","+r.get("pendingDeliveries")+","+r.get("retainedRequestBytes")+"\n");}}
    private static long percentile(long[] values,double percentile){if(values.length==0)return 0;Arrays.sort(values);return values[Math.max(0,(int)Math.ceil(values.length*percentile)-1)];}
    private Connection connection()throws SQLException{return DriverManager.getConnection(jdbc,username,password);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException(name+" must select a disposable test database");return value;}
    private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static final class Sample {
        final String phase,operation;final int index,client;final long scheduleLagNanos,start;volatile long submissionNanos,latencyNanos;volatile String error="";
        Sample(String phase,int index,int client,String operation,long lag,long start){this.phase=phase;this.index=index;this.client=client;this.operation=operation;this.scheduleLagNanos=lag;this.start=start;}
    }
    private static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){List<String> items=new ArrayList<>();map.forEach((k,v)->items.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",items)+"}";}
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(LoadHarness::json).toList())+"]";
        String text=value.toString();StringBuilder out=new StringBuilder("\"");for(char c:text.toCharArray()){switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.append('"').toString();
    }
}
