package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import kr.lunaf.varstore.cache.*;
import kr.lunaf.varstore.postgres.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static kr.lunaf.varstore.testkit.ContractSuite.await;

class ExtensionIntegrationTest {
    String network,jdbc,user,password;
    final List<VarStore> stores=new ArrayList<>();
    @BeforeEach void setup()throws Exception{
        jdbc=System.getenv("VARSTORE_TEST_JDBC_URL");Assumptions.assumeTrue(jdbc!=null);
        user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
        network="extension-"+UUID.randomUUID();try(var c=connection()){SchemaMigrator.migrate(c,network);}
    }
    Connection connection()throws SQLException{return DriverManager.getConnection(jdbc,user,password);}
    @AfterEach void close(){stores.forEach(VarStore::close);}
    VarStore open(String server){
        var settings=new PostgresSettings(jdbc,user,password,network,server,"disable",true,4,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500));
        var store=StoreFactory.open(new StoreConfig(settings,4,256,8_388_608,Duration.ofSeconds(5),Duration.ofSeconds(2),3));stores.add(store);await(store.ready());return store;
    }
    VarStore.Data data(VarStore store){return store.namespace("extension").network().system("owner");}
    SubscriptionSpec spec(String id,SubscriptionMode mode){return new SubscriptionSpec(id,"extension",mode,Duration.ofSeconds(10),Duration.ofHours(1));}
    void eventually(BooleanSupplier condition)throws InterruptedException{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);assertTrue(condition.getAsBoolean());
    }
    @Test void committedFanoutAndLocalInvalidationsWorkAcrossTwoProviders()throws Exception{
        var first=open("first");var second=open("second");var a=(VarStoreExtensions)first;var b=(VarStoreExtensions)second;
        var receivedA=new LinkedBlockingQueue<ChangeEvent>();var receivedB=new LinkedBlockingQueue<ChangeEvent>();var local=new AtomicInteger();
        try(var hook=a.onInvalidation(address->local.incrementAndGet());
            var subA=await(a.events().subscribe(spec("first",SubscriptionMode.DURABLE),event->{receivedA.add(event);return CompletableFuture.completedFuture(null);},()->{}));
            var subB=await(b.events().subscribe(spec("second",SubscriptionMode.DURABLE),event->{receivedB.add(event);return CompletableFuture.completedFuture(null);},()->{}))){
            UUID id=UUID.randomUUID();var key=VarKey.longKey("prefix/value");await(data(first).set(key,7L,id));
            assertEquals(1,local.get());ChangeEvent ea=receivedA.poll(5,TimeUnit.SECONDS),eb=receivedB.poll(5,TimeUnit.SECONDS);assertNotNull(ea);assertNotNull(eb);assertEquals(ea,eb);assertEquals(id,ea.operationId());
            await(data(first).set(key,7L,id));await(data(first).set(key,7L));Thread.sleep(600);assertTrue(receivedA.isEmpty());assertTrue(receivedB.isEmpty());
            KeyPage page=await(a.scanKeys(data(first),"prefix/",Optional.empty(),5));assertEquals(1,page.keys().size());
        }
    }
    @Test void failedConsumerRetriesAndAcknowledgesOnlyAfterCompletion()throws Exception{
        var store=open("delivery");var ext=(VarStoreExtensions)store;var calls=new AtomicInteger();var done=new CompletableFuture<Void>();var ids=new CopyOnWriteArrayList<Long>();
        try(var sub=await(ext.events().subscribe(spec("retry",SubscriptionMode.DURABLE),event->{ids.add(event.eventId());return calls.incrementAndGet()==1?CompletableFuture.failedFuture(new IllegalStateException("consumer")):done;},()->{}))){
            await(data(store).set(VarKey.longKey("retry"),1L));eventually(()->calls.get()==2);assertEquals(ids.get(0),ids.get(1));
            try(var c=connection();var s=c.prepareStatement("SELECT state FROM vs_outbox_delivery WHERE network_id=? AND subscriber_id='retry'")){s.setString(1,network);try(var rows=s.executeQuery()){assertTrue(rows.next());assertEquals("LEASED",rows.getString(1));}}
            done.complete(null);
            eventually(()->{try(var c=connection();var s=c.prepareStatement("SELECT state FROM vs_outbox_delivery WHERE network_id=? AND subscriber_id='retry'")){s.setString(1,network);try(var rows=s.executeQuery()){return rows.next()&&rows.getString(1).equals("ACKED");}}catch(SQLException e){throw new RuntimeException(e);}});
        }
    }
    @Test void remoteCacheInvalidationExpiryResyncAndEpochFence()throws Exception{
        var first=open("cache");var writer=open("writer");var definition=new KeyDefinition<>(VarKey.longKey("display"),0L,"Display",false,CachePolicy.DISPLAY_ONLY,1);
        try(var cache=new DisplayCache(new CacheLimits(100,1_000_000));var handle=cache.open(first,"extension",new CacheLimits(50,500_000))){
            await(handle.ready());await(data(writer).set(definition.key(),1L));assertEquals(1L,await(handle.getCached(data(first),definition,Duration.ofMinutes(5))).value().orElseThrow());
            await(data(writer).set(definition.key(),2L));eventually(()->handle.peekCached(data(first),definition,Duration.ofMinutes(5)).state()!=CacheState.VALUE);
            assertEquals(2L,await(handle.getCached(data(first),definition,Duration.ofMinutes(5))).value().orElseThrow());
            try(var c=connection();var s=c.prepareStatement("UPDATE vs_subscriptions SET lease_until=clock_timestamp()-interval '1 second' WHERE network_id=?")){s.setString(1,network);s.executeUpdate();}
            await(data(writer).set(definition.key(),3L));eventually(()->{var v=await(handle.getCached(data(first),definition,Duration.ofMinutes(5)));return v.state()==CacheState.VALUE&&v.value().orElseThrow()==3;});
            try(var c=connection()){SchemaMigrator.rotateEpoch(c,network);}
            try{await(data(first).get(definition.key()));}catch(RuntimeException expected){}
            eventually(()->handle.peekCached(data(first),definition,Duration.ofMinutes(5)).state()==CacheState.UNAVAILABLE);
        }
    }
}
