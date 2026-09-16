package kr.lunaf.varstore.testkit;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import kr.lunaf.varstore.cache.*;
import kr.lunaf.varstore.core.*;
import kr.lunaf.varstore.postgres.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Finite real-DB cache/outbox soak. Not a Paper tick or unrestricted leak benchmark. */
public final class ExtensionLoadHarness {
 private final String base=Objects.requireNonNull(System.getenv("VARSTORE_TEST_JDBC_URL"));
 private final String user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore"),password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
 private final String schema="extensionload_"+UUID.randomUUID().toString().replace("-","");
 private final AtomicLong writes=new AtomicLong(),callbacks=new AtomicLong(),injectedFailures=new AtomicLong(),delivered=new AtomicLong();
 private final Set<Long> observed=ConcurrentHashMap.newKeySet(),completed=ConcurrentHashMap.newKeySet();
 private final AtomicReference<Throwable> fatal=new AtomicReference<>();
 private final AtomicBoolean producing=new AtomicBoolean(true);
 private final List<Map<String,Object>> samples=new ArrayList<>();
 private String url;private VarStore writer,reader;private DisplayCache cache;private CacheHandle handle;private EventSubscription observer;
 private long begun,reads,staleReads,unavailableReads,churnedHandles,peakQueued,peakPending,peakBytes,peakConnections;private int sampledLockWaiters;
 public static void main(String[] args)throws Exception {int duration=args.length>0?Integer.parseInt(args[0]):180;if(duration<180||duration>600)throw new IllegalArgumentException("Duration must be180..600s");new ExtensionLoadHarness().run(duration,Path.of(args.length>1?args[1]:"verification/extensions-load.json"));}
 private void run(int seconds,Path output)throws Exception {
  if(!base.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):25432/varstore_extensions"))throw new IllegalArgumentException("Use isolated loopback varstore_extensions database");
  url=base+"?currentSchema="+schema;Map<String,Object> report=new LinkedHashMap<>();report.put("startedAt",Instant.now().toString());report.put("requestedSoakSeconds",seconds);report.put("schema",schema);
  var work=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"extension-load-writer"));
  try(var admin=DriverManager.getConnection(base,user,password)) {
   try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
   try {
    try(var c=connection()){SchemaMigrator.migrate(c,"extensions");}
    writer=open("extensions-writer");reader=open("extensions-reader");await(writer.ready());await(reader.ready());
    var writing=writer.namespace("load").network().system("global");var reading=reader.namespace("load").network().system("global");
    var stable=VarKey.longKey("stable");var changing=VarKey.longKey("changing");await(writing.set(stable,100L));await(writing.set(changing,0L));
    var stableDefinition=new KeyDefinition<>(stable,0L,"stable display",false,CachePolicy.DISPLAY_ONLY,1);var changingDefinition=new KeyDefinition<>(changing,0L,"changing display",false,CachePolicy.DISPLAY_ONLY,1);
    cache=new DisplayCache(new CacheLimits(128,1048576));handle=cache.open(reader,"load",new CacheLimits(64,524288));await(handle.ready());
    observer=await(((VarStoreExtensions)reader).events().subscribe(new SubscriptionSpec("durable-observer","load",SubscriptionMode.DURABLE,Duration.ofMinutes(1),Duration.ofHours(1)),event->{
     callbacks.incrementAndGet();boolean first=observed.add(event.eventId());if(first&&event.eventId()%25==0){injectedFailures.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("INJECTED_CONSUMER_FAILURE"));}if(completed.add(event.eventId()))delivered.incrementAndGet();return CompletableFuture.completedFuture(null);
    },()->fatal.compareAndSet(null,new AssertionError("Unexpected retention or lease gap during healthy soak"))));
    // Identical stable-value read counts: uncached public API vs explicit display cache.
    long uncachedStart=System.nanoTime();for(int i=0;i<1000;i++)if(await(reading.get(stable)).orElseThrow()!=100L)throw new AssertionError("Direct read mismatch");long uncachedMicros=(System.nanoTime()-uncachedStart)/1000;
    long loadsBefore=cache.metrics().loads(),requestsBefore=reader.metrics().requests(),cachedStart=System.nanoTime();for(int i=0;i<1000;i++){var value=await(handle.getCached(reading,stableDefinition,Duration.ofSeconds(5)));if(value.value().orElseThrow()!=100L)throw new AssertionError("Cached read mismatch");}
    report.put("stableReadComparison",Map.of("uncachedCalls",1000,"uncachedJdbcReads",1000,"uncachedElapsedMicros",uncachedMicros,"cachedCalls",1000,"cachedJdbcLoads",cache.metrics().loads()-loadsBefore,"cachedTotalStoreRequestsIncludingPolls",reader.metrics().requests()-requestsBefore,"cachedElapsedMicros",(System.nanoTime()-cachedStart)/1000));
    if(cache.metrics().loads()-loadsBefore>=100)throw new AssertionError("Cache did not reduce stable reads");
    String walBefore=lsn(admin);long heapBefore=postGcHeap();begun=System.nanoTime();sample("start");
    work.scheduleWithFixedDelay(()->{if(!producing.get())return;try {long next=writes.get()+1;var receipt=await(writing.set(changing,next,UUID.randomUUID()));if(receipt.outcome()!=Outcome.APPLIED)throw new AssertionError("Changing write not applied");writes.incrementAndGet();}catch(Throwable failure){fatal.compareAndSet(null,failure);}},0,50,TimeUnit.MILLISECONDS);
    long end=begun+TimeUnit.SECONDS.toNanos(seconds),nextSample=begun+TimeUnit.SECONDS.toNanos(5),nextChurn=begun+TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<end){
     checkFatal();for(int i=0;i<5;i++){var value=await(handle.getCached(reading,(i&1)==0?stableDefinition:changingDefinition,Duration.ofSeconds(5)));reads++;if(value.state()==CacheState.STALE)staleReads++;else if(value.state()==CacheState.UNAVAILABLE){unavailableReads++;throw new AssertionError("Healthy load became unavailable");}}
     long now=System.nanoTime();if(now>=nextSample){sample("running");nextSample=now+TimeUnit.SECONDS.toNanos(5);System.out.println("EXTENSION_LOAD seconds="+(now-begun)/1_000_000_000+" writes="+writes.get()+" reads="+reads+" deliveries="+delivered.get());}
     if(now>=nextChurn){try(var transientHandle=cache.open(reader,"load",new CacheLimits(4,65536))){await(transientHandle.ready());await(transientHandle.getCached(reading,stableDefinition,Duration.ofSeconds(5)));}churnedHandles++;nextChurn=now+TimeUnit.SECONDS.toNanos(10);}
     Thread.sleep(20);
    }
    producing.set(false);work.shutdown();if(!work.awaitTermination(15,TimeUnit.SECONDS))throw new AssertionError("Writer did not stop");checkFatal();
    long drain=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(delivered.get()<writes.get()&&System.nanoTime()<drain){checkFatal();Thread.sleep(50);}if(delivered.get()!=writes.get())throw new AssertionError("Durable observer lost changed writes");
    boolean fresh=false;while(System.nanoTime()<drain){var value=await(handle.getCached(reading,changingDefinition,Duration.ofSeconds(5)));if(value.state()==CacheState.VALUE&&value.value().orElseThrow()==writes.get()){fresh=true;break;}Thread.sleep(25);}if(!fresh)throw new AssertionError("Remote cache failed to converge");
    if(await(reading.get(changing)).orElseThrow()!=writes.get())throw new AssertionError("Final database value mismatch");
    report.put("soakElapsedSeconds",(System.nanoTime()-begun)/1_000_000_000d);report.put("changedWrites",writes.get());report.put("cacheReadCalls",reads);report.put("staleReadResults",staleReads);report.put("unavailableReadResults",unavailableReads);report.put("listenerCallbacks",callbacks.get());report.put("uniqueEventsDelivered",delivered.get());report.put("injectedConsumerFailures",injectedFailures.get());report.put("churnedCacheHandles",churnedHandles);report.put("samples",samples);
    var metrics=cache.metrics();report.put("cache",Map.of("hits",metrics.hits(),"misses",metrics.misses(),"databaseLoads",metrics.loads(),"invalidations",metrics.invalidations(),"discardedLoads",metrics.discardedLoads(),"coalesced",metrics.coalesced(),"failures",metrics.failures()));
    try(var statement=admin.prepareStatement("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)::bigint")){statement.setString(1,walBefore);try(var result=statement.executeQuery()){result.next();report.put("databaseWideWalBytes",result.getLong(1));}}
    try(var c=connection();var statement=c.createStatement();var result=statement.executeQuery("SELECT count(*),pg_table_size('vs_operations'),pg_indexes_size('vs_operations') FROM vs_operations")){result.next();report.put("operationRows",result.getLong(1));report.put("operationTableBytes",result.getLong(2));report.put("operationIndexBytes",result.getLong(3));if(result.getLong(1)!=writes.get()+2)throw new AssertionError("Operation growth differs from unique writes");}
    try(var c=connection();var statement=c.createStatement();var result=statement.executeQuery("SELECT count(*) FROM vs_outbox")){result.next();report.put("outboxEvents",result.getLong(1));if(result.getLong(1)!=writes.get()+2)throw new AssertionError("Outbox growth differs from actual changes");}
    report.put("peakQueuedRequestsPerClient",peakQueued);report.put("peakPendingDeliveriesPerClient",peakPending);report.put("peakRetainedRequestBytesPerClient",peakBytes);report.put("peakActiveConnectionsPerClient",peakConnections);report.put("sampledLockWaitingConnectionsMaximum",sampledLockWaiters);
    report.put("limits",Map.of("clientCount",2,"dbWorkersPerClient",2,"poolPerClient",2,"queueRequestsPerClient",64,"queueBytesPerClient",1048576,"retainedBytesPerClient",1179648,"pendingDeliveriesPerClient",66,"cacheEntries",128,"cacheBytes",1048576));
    observer.close();handle.close();cache.close();Thread.sleep(300);writer.close();reader.close();long closeLimit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while((connections(admin)>0||varstoreThreads()>0||writer.metrics().pendingDeliveries()>0||reader.metrics().pendingDeliveries()>0)&&System.nanoTime()<closeLimit)Thread.sleep(25);
    var finalCache=cache.metrics();report.put("afterClose",Map.of("cacheHandles",finalCache.handles(),"cacheEntries",finalCache.entries(),"cacheRetainedBytes",finalCache.retainedBytes(),"clientConnections",connections(admin),"writerPendingDeliveries",writer.metrics().pendingDeliveries(),"readerPendingDeliveries",reader.metrics().pendingDeliveries(),"writerRetainedBytes",writer.metrics().retainedRequestBytes(),"readerRetainedBytes",reader.metrics().retainedRequestBytes(),"platformThreads",varstoreThreads()));
    if(varstoreThreads()!=0||finalCache.handles()!=0||finalCache.entries()!=0||finalCache.retainedBytes()!=0||connections(admin)!=0||writer.metrics().pendingDeliveries()!=0||reader.metrics().pendingDeliveries()!=0||writer.metrics().retainedRequestBytes()!=0||reader.metrics().retainedRequestBytes()!=0)throw new AssertionError("Owned resources did not drain");
    try(var c=connection();var statement=c.createStatement();var result=statement.executeQuery("SELECT count(*) FILTER(WHERE mode='EPHEMERAL'),count(*) FILTER(WHERE mode='DURABLE') FROM vs_subscriptions")){result.next();report.put("ephemeralSubscriptionsAfterClose",result.getLong(1));report.put("durableSubscriptionsRetained",result.getLong(2));if(result.getLong(1)!=0||result.getLong(2)!=1)throw new AssertionError("Subscription cleanup differs from declared lifecycle");}
    report.put("postGcHeapGrowthBytes",postGcHeap()-heapBefore);report.put("status","PASS");report.put("limitations",List.of("Finite single-host Java21 core/cache/outbox run; no Paper TPS claim or proof of unlimited leak freedom.","Consumer failures are deliberately injected failed futures; database connectivity remains healthy.","WAL is database-wide; unrelated concurrent activity can increase the delta.","Resource peaks are sampled every5seconds and asserted during samples; admission and cache implementations enforce hard bounds between samples.","Metadata overhead uses cache accounting, not exact JVM object sizes."));
   }catch(Throwable failure){report.put("status","FAIL");report.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());throw failure;}
   finally{producing.set(false);work.shutdownNow();if(observer!=null)observer.close();if(cache!=null)cache.close();if(writer!=null)writer.close();if(reader!=null)reader.close();try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,HarnessJson.json(report)+"\n");}
  }
 }
 private VarStore open(String server){return StoreFactory.open(new StoreConfig(new PostgresSettings(url,user,password,"extensions",server,"disable",true,2,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5)),2,64,1048576,Duration.ofSeconds(5),Duration.ofSeconds(10),3));}
 private Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);}
 private void checkFatal(){if(fatal.get()!=null)throw new AssertionError("Asynchronous load failed",fatal.get());}
 private void sample(String point)throws Exception {
  for(VarStore store:List.of(writer,reader)){var m=store.metrics();peakQueued=Math.max(peakQueued,m.queuedRequests());peakPending=Math.max(peakPending,m.pendingDeliveries());peakBytes=Math.max(peakBytes,m.retainedRequestBytes());peakConnections=Math.max(peakConnections,m.activeConnections());if(m.queuedRequests()>64||m.queuedBytes()>1048576||m.pendingDeliveries()>66||m.retainedRequestBytes()>1179648||m.activeConnections()>2)throw new AssertionError("Store bounds exceeded");}
  var c=cache.metrics();if(c.entries()>128||c.retainedBytes()>1048576||c.handles()>2)throw new AssertionError("Cache bounds exceeded");
  try(var connection=connection();var s=connection.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'VarStore/extensions-%' AND wait_event_type='Lock'")){r.next();sampledLockWaiters=Math.max(sampledLockWaiters,r.getInt(1));}
  samples.add(Map.ofEntries(Map.entry("point",point),Map.entry("seconds",(System.nanoTime()-begun)/1_000_000_000d),Map.entry("writes",writes.get()),Map.entry("reads",reads),Map.entry("cacheEntries",c.entries()),Map.entry("cacheBytes",c.retainedBytes()),Map.entry("cacheHandles",c.handles()),Map.entry("heapBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()),Map.entry("platformThreads",varstoreThreads())));
 }
 private static int connections(Connection connection)throws SQLException{try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'VarStore/extensions-%'")){result.next();return result.getInt(1);}}
 private static long varstoreThreads(){return Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().toLowerCase(Locale.ROOT).startsWith("varstore")).count();}
 private static long postGcHeap()throws InterruptedException{System.gc();Thread.sleep(100);return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
 private static String lsn(Connection connection)throws SQLException{try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_current_wal_lsn()::text")){result.next();return result.getString(1);}}
 private static <T>T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(15,TimeUnit.SECONDS);}
}
