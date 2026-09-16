package kr.lunaf.varstore.postgres;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL transactions, including committed reverse sequence allocation. */
@EnabledIfEnvironmentVariable(named="VARSTORE_TEST_JDBC_URL",matches=".+")
class ExtensionContractTest {
 String base,url,user,password,schema;PostgresBackend backend;
 @BeforeEach void prepare()throws Exception {
  base=System.getenv("VARSTORE_TEST_JDBC_URL");user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");schema="extensions_"+UUID.randomUUID().toString().replace("-","");
  try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
  url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
 }
 @AfterEach void clean()throws Exception {if(backend!=null)backend.close();try(var c=DriverManager.getConnection(base,user,password);var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}}
 Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);}
 PostgresBackend start()throws Exception {try(var c=connection()){SchemaMigrator.migrate(c,"test");}backend=newBackend();backend.initialize();return backend;}
 PostgresBackend newBackend(){return new PostgresBackend(new PostgresSettings(url,user,password,"test","origin","disable",true,4,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5)));}
 static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(20);}
 Target<Long> target(String key){return new Target<>(new Address("test","events",ScopeKind.NETWORK,"_",Owner.system("owner"),key),ValueType.LONG);}
 WriteReceipt<Long> set(String key,long value){return backend.write(target(key),WriteKind.SET,value,0,null,UUID.randomUUID(),deadline());}
 SubscriptionSpec spec(String id,SubscriptionMode mode){return new SubscriptionSpec(id,"events",mode,Duration.ofMinutes(5),Duration.ofDays(1));}
 SubscriptionState subscribe(String id){return backend.registerSubscription(spec(id,SubscriptionMode.DURABLE),deadline());}
 List<Delivery> claim(SubscriptionState s){return backend.claimEvents(s,50,Duration.ofSeconds(10),deadline());}
 void sql(String command)throws SQLException{try(var c=connection();var s=c.createStatement()){s.execute(command);}}
 long count(String table)throws SQLException{try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}}
 void error(ErrorCode code,Runnable action){assertEquals(code,assertThrows(VarStoreException.class,action::run).code());}

 @Test void migrationPreservesV1DataReceiptAndImmutableHistory()throws Exception {
  UUID epoch,generation=UUID.randomUUID(),operation=UUID.randomUUID();byte[] encoded;String history;
  Target<Long> target=target("legacy");
  try(var c=connection()){
   SchemaMigrator.migrateTo(c,"test",1);
   assertThrows(SQLException.class,()->SchemaMigrator.validate(c));
   try(var s=c.createStatement();var r=s.executeQuery("SELECT storage_epoch FROM vs_networks")){r.next();epoch=r.getObject(1,UUID.class);}
   var version=new VersionToken(epoch,generation,7);
   var receipt=new TransactionReceipt(operation,Outcome.APPLIED,Map.of(target.address(),new WriteReceipt<>(operation,Outcome.APPLIED,Optional.of(version),Optional.of(42L),false)),false);
   encoded=ProtocolCodec.receipt(receipt);
   try(var s=c.prepareStatement("INSERT INTO vs_variables(network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,value_type,long_value,generation,revision,deleted,last_writer) VALUES('test','events','NETWORK','_','SYSTEM','owner','legacy','LONG',42,?,7,false,'v1')")){s.setObject(1,generation);s.executeUpdate();}
   var plan=new TransactionPlan(List.of(),List.of(new Mutation(target,Mutation.Kind.SET,42L,0)));
   try(var s=c.prepareStatement("INSERT INTO vs_operations(network_id,namespace,operation_id,fingerprint,outcome,result_payload,completed_at) VALUES('test','events',?,?,'APPLIED',?,clock_timestamp())")){s.setObject(1,operation);s.setBytes(2,ProtocolCodec.fingerprint("SET",plan));s.setBytes(3,encoded);s.executeUpdate();}
   try(var s=c.createStatement();var r=s.executeQuery("SELECT row_to_json(h)::text FROM vs_schema_history h WHERE version=1")){r.next();history=r.getString(1);}
   SchemaMigrator.migrate(c,"test");SchemaMigrator.migrate(c,"test");SchemaMigrator.validate(c);
   try(var s=c.createStatement();var r=s.executeQuery("SELECT row_to_json(h)::text FROM vs_schema_history h WHERE version=1")){r.next();assertEquals(history,r.getString(1));}
   try(var s=c.createStatement();var r=s.executeQuery("SELECT result_payload FROM vs_operations")){r.next();assertArrayEquals(encoded,r.getBytes(1));}
  }
  backend=newBackend();backend.initialize();var replay=backend.<Long>write(target,WriteKind.SET,42L,0,null,operation,deadline());
  assertTrue(replay.replayed());assertEquals(7,replay.version().orElseThrow().revision());assertEquals(42L,backend.get(target,deadline()).orElseThrow().value());assertEquals(0,count("vs_outbox"));assertEquals(2,count("vs_schema_history"));
 }
 @Test void failedUpgradeRollsBackAndHistoryTamperingFailsClosed()throws Exception {
  try(var c=connection()){
   SchemaMigrator.migrateTo(c,"test",1);
   try(var s=c.createStatement()){s.execute("CREATE TABLE vs_outbox(dummy integer)");}
   assertThrows(SQLException.class,()->SchemaMigrator.migrate(c,"test"));assertEquals(1,count("vs_schema_history"));
   try(var s=c.createStatement();var r=s.executeQuery("SELECT to_regclass('vs_subscriptions') IS NULL")){r.next();assertTrue(r.getBoolean(1));}
   try(var s=c.createStatement()){s.execute("DROP TABLE vs_outbox");}SchemaMigrator.migrate(c,"test");
   try(var s=c.createStatement()){s.execute("UPDATE vs_schema_history SET checksum='tampered' WHERE version=1");}
   assertThrows(SQLException.class,()->SchemaMigrator.validate(c));
  }
 }
 @Test void sequenceMetadataIsValidatedButAdvancingSequenceValuesRemainValid()throws Exception {
  start();set("sequence",1);set("sequence",2);
  try(var c=connection()) {
   SchemaMigrator.validate(c);
   try(var statement=c.createStatement()){statement.execute("ALTER SEQUENCE vs_outbox_event_id_seq INCREMENT BY 2");}
   assertThrows(SQLException.class,()->SchemaMigrator.validate(c));
   try(var statement=c.createStatement()){statement.execute("ALTER SEQUENCE vs_outbox_event_id_seq INCREMENT BY 1");}
   SchemaMigrator.validate(c);
   try(var statement=c.createStatement()){statement.execute("ALTER SEQUENCE vs_admin_audit_audit_id_seq CYCLE");}
   assertThrows(SQLException.class,()->SchemaMigrator.validate(c));
  }
 }
 @Test void actualChangesFanOutWithoutReplayNoChangeOrConditionFailureEvents()throws Exception {
  start();var one=subscribe("one");var two=subscribe("two");UUID operation=UUID.randomUUID();
  var first=backend.<Long>write(target("balance"),WriteKind.SET,3L,0,null,operation,deadline());
  assertTrue(backend.write(target("balance"),WriteKind.SET,3L,0,null,operation,deadline()).replayed());assertEquals(Outcome.NO_CHANGE,set("balance",3).outcome());
  assertEquals(Outcome.CONDITION_FAILED,backend.write(target("balance"),WriteKind.SET_IF_ABSENT,4L,0,null,UUID.randomUUID(),deadline()).outcome());
  assertEquals(1,count("vs_outbox"));var a=claim(one).getFirst();var b=claim(two).getFirst();assertEquals(a.event(),b.event());assertEquals(first.version().orElseThrow(),a.event().version());assertEquals("origin",a.event().sourceServer());
  assertTrue(backend.acknowledge(one,a.event().eventId(),a.leaseToken(),deadline()));assertTrue(claim(one).isEmpty());assertTrue(claim(two).isEmpty());
  backend.write(target("balance"),WriteKind.DELETE,null,0,null,UUID.randomUUID(),deadline());assertEquals(ChangeKind.DELETE,claim(one).getFirst().event().kind());assertEquals(2,count("vs_outbox"));
 }
 @Test void leasesFenceOldAcksAndDeadLettersCanBeRetried()throws Exception {
  start();var state=subscribe("worker");set("lease",1);var first=claim(state).getFirst();assertTrue(claim(state).isEmpty());
  sql("UPDATE vs_outbox_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE state='LEASED'");
  var second=claim(state).getFirst();assertEquals(2,second.attempt());assertNotEquals(first.leaseToken(),second.leaseToken());assertFalse(backend.acknowledge(state,first.event().eventId(),first.leaseToken(),deadline()));
  assertTrue(backend.failDelivery(state,second.event().eventId(),second.leaseToken(),"CALLBACK_FAILED",2,Duration.ofMillis(10),deadline()));assertTrue(claim(state).isEmpty());assertEquals(1,backend.retryDeadLetters(state,50,deadline()));
  var third=claim(state).getFirst();assertEquals(1,third.attempt());assertTrue(backend.failDelivery(state,third.event().eventId(),third.leaseToken(),"RETRY",3,Duration.ofMinutes(1),deadline()));assertTrue(claim(state).isEmpty());
  sql("UPDATE vs_outbox_delivery SET next_attempt_at=clock_timestamp()-interval '1 second'");assertEquals(2,claim(state).getFirst().attempt());
  var replacement=subscribe("worker");error(ErrorCode.SUBSCRIPTION_EXPIRED,()->claim(state));assertEquals(3,claim(replacement).getFirst().attempt());
 }
 @Test void restartingAtAttemptLimitCannotBypassDeadLetterCeiling()throws Exception {
  start();var original=subscribe("restarting");set("restart-limit",1);claim(original);
  sql("UPDATE vs_outbox_delivery SET attempts=99,lease_until=clock_timestamp()-interval '1 second'");
  var lastAttempt=claim(original).getFirst();assertEquals(100,lastAttempt.attempt());
  assertTrue(claim(original).isEmpty());
  try(var c=connection();var statement=c.createStatement();var result=statement.executeQuery("SELECT state FROM vs_outbox_delivery")){assertTrue(result.next());assertEquals("LEASED",result.getString(1));}
  // A real registration simulates a restarted process taking over an unacknowledged lease.
  var restarted=subscribe("restarting");
  try(var c=connection();var statement=c.createStatement();var result=statement.executeQuery("SELECT state,attempts FROM vs_outbox_delivery")){assertTrue(result.next());assertEquals("PENDING",result.getString(1));assertEquals(100,result.getInt(2));}
  assertTrue(claim(restarted).isEmpty());
  try(var c=connection();var statement=c.createStatement();var result=statement.executeQuery("SELECT state,attempts,last_error,lease_token,lease_until FROM vs_outbox_delivery")){assertTrue(result.next());assertEquals("DEAD",result.getString(1));assertEquals(100,result.getInt(2));assertEquals("LEASE_EXHAUSTED",result.getString(3));assertNull(result.getObject(4));assertNull(result.getObject(5));}
  var restartedAgain=subscribe("restarting");assertTrue(claim(restartedAgain).isEmpty());
  assertEquals(1,backend.retryDeadLetters(restartedAgain,1,deadline()));assertEquals(1,claim(restartedAgain).getFirst().attempt());
 }
 @Test void durableOfflineDeliveryAndEphemeralCollectionAreExplicit()throws Exception {
  start();var durable=subscribe("persistent");backend.closeSubscription(durable,deadline());set("offline",1);var resumed=subscribe("persistent");assertFalse(resumed.resyncRequired());assertEquals(1,claim(resumed).size());
  var spec=spec("cache",SubscriptionMode.EPHEMERAL);var cache=backend.registerSubscription(spec,deadline());set("online",2);backend.closeSubscription(cache,deadline());assertEquals(1,count("vs_subscriptions"));
  cache=backend.registerSubscription(spec,deadline());sql("UPDATE vs_subscriptions SET lease_until=clock_timestamp()-interval '1 second' WHERE subscriber_id='cache'");set("missed",3);
  var expired=backend.registerSubscription(spec,deadline());assertTrue(expired.resyncRequired());error(ErrorCode.RESYNC_REQUIRED,()->claim(expired));
  var reset=backend.resetSubscription(expired,deadline());assertFalse(reset.resyncRequired());set("fresh",4);assertEquals(1,claim(reset).size());
  sql("UPDATE vs_subscriptions SET lease_until=clock_timestamp()-interval '1 second' WHERE subscriber_id='cache'");subscribe("cleanup");assertEquals(2,count("vs_subscriptions"));
 }
 @Test void closingEphemeralSubscriberCannotRaceInFlightFanoutForeignKey()throws Exception {
  start();var subscription=backend.registerSubscription(spec("closing",SubscriptionMode.EPHEMERAL),deadline());
  long barrier=UUID.randomUUID().getMostSignificantBits();
  // Install a test-only BEFORE INSERT barrier after runtime schema validation.
  sql("CREATE FUNCTION pause_fanout() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock("+barrier+"); RETURN NEW; END $$");
  sql("CREATE TRIGGER pause_fanout BEFORE INSERT ON vs_outbox_delivery FOR EACH ROW EXECUTE FUNCTION pause_fanout()");
  try(var blocker=connection();var monitor=connection();var workers=Executors.newFixedThreadPool(2)) {
   try(var statement=blocker.prepareStatement("SELECT pg_advisory_lock(?)")){statement.setLong(1,barrier);statement.execute();}
   try {
    Future<WriteReceipt<Long>> writing=workers.submit(()->set("close-race",1));
    assertTrue(awaitDatabaseWait(monitor,"advisory","INSERT INTO vs_outbox_delivery%"),"Writer must reach the fan-out barrier");
    Future<?> closing=workers.submit(()->backend.closeSubscription(subscription,deadline()));
    assertTrue(awaitDatabaseWait(monitor,"any","%FROM vs_networks%FOR UPDATE%"),"Close must wait at the network boundary while fan-out is in flight");assertFalse(closing.isDone());
    try(var statement=blocker.prepareStatement("SELECT pg_advisory_unlock(?)")){statement.setLong(1,barrier);statement.execute();}
    assertEquals(Outcome.APPLIED,writing.get(10,TimeUnit.SECONDS).outcome());closing.get(10,TimeUnit.SECONDS);
   }finally{try(var statement=blocker.prepareStatement("SELECT pg_advisory_unlock(?)")){statement.setLong(1,barrier);statement.execute();}}
  }
  assertEquals(1L,backend.get(target("close-race"),deadline()).orElseThrow().value());assertEquals(1,count("vs_outbox"));assertEquals(0,count("vs_subscriptions"));assertEquals(0,count("vs_outbox_delivery"));
 }
 boolean awaitDatabaseWait(Connection monitor,String event,String query)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
  while(System.nanoTime()<end){try(var statement=monitor.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='VarStore/origin' AND wait_event_type='Lock' AND (?='any' OR wait_event=?) AND query LIKE ?)")){statement.setString(1,event);statement.setString(2,event);statement.setString(3,query);try(var result=statement.executeQuery()){result.next();if(result.getBoolean(1))return true;}}Thread.sleep(10);}return false;
 }
 @Test void retentionPreservesDurableWindowThenMarksResyncBeforePruning()throws Exception {
  start();var state=backend.registerSubscription(new SubscriptionSpec("long-retention","events",SubscriptionMode.DURABLE,Duration.ofMinutes(5),Duration.ofDays(3)),deadline());set("old",1);
  sql("UPDATE vs_outbox SET created_at=clock_timestamp()-interval '2 days'");assertEquals(0,backend.pruneOutbox(Duration.ofHours(1),100,deadline()));
  sql("UPDATE vs_outbox SET created_at=clock_timestamp()-interval '4 days'");assertEquals(1,backend.pruneOutbox(Duration.ofHours(1),100,deadline()));error(ErrorCode.RESYNC_REQUIRED,()->claim(state));assertTrue(backend.renewSubscription(state,Duration.ofMinutes(5),deadline()).resyncRequired());
  var reset=backend.resetSubscription(state,deadline());set("new",2);assertEquals(1,claim(reset).size());
 }
 @Test void committedHigherIdNeverSkipsLaterCommittedLowerId()throws Exception {
  start();var state=subscribe("reverse");UUID epoch=backend.initialize();
  try(var low=connection();var high=connection()){
   low.setAutoCommit(false);high.setAutoCommit(false);long lower=enqueueFixture(low,"low",epoch);long higher=enqueueFixture(high,"high",epoch);assertTrue(lower<higher);high.commit();
   var delivered=claim(state).getFirst();assertEquals(higher,delivered.event().eventId());assertTrue(backend.acknowledge(state,higher,delivered.leaseToken(),deadline()));low.commit();assertEquals(lower,claim(state).getFirst().event().eventId());
  }
 }
 long enqueueFixture(Connection c,String key,UUID epoch)throws Exception {
  try(var s=c.createStatement();var r=s.executeQuery("SELECT storage_epoch FROM vs_networks WHERE network_id='test' FOR SHARE")){assertTrue(r.next());}
  UUID op=UUID.randomUUID();var version=new VersionToken(epoch,UUID.randomUUID(),1);var target=target(key);var receipt=new TransactionReceipt(op,Outcome.APPLIED,Map.of(target.address(),new WriteReceipt<>(op,Outcome.APPLIED,Optional.of(version),Optional.of(1L),false)),false);
  try(var s=c.prepareStatement("INSERT INTO vs_operations(network_id,namespace,operation_id,fingerprint,outcome,result_payload,completed_at) VALUES('test','events',?,?,'APPLIED',?,clock_timestamp())")){s.setObject(1,op);s.setBytes(2,new byte[32]);s.setBytes(3,ProtocolCodec.receipt(receipt));s.executeUpdate();}
  new OutboxRepository("test","fixture",(connection,sql,deadline)->connection.prepareStatement(sql)).enqueue(c,target.address(),op,version,ChangeKind.SET,deadline());
  try(var s=c.prepareStatement("SELECT event_id FROM vs_outbox WHERE operation_id=?")){s.setObject(1,op);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}
 }
 @Test void rollbackRemovesVariableMutationAndOutboxTogether()throws Exception {
  start();set("a",1);set("z",Long.MAX_VALUE);long before=count("vs_outbox");
  var plan=TransactionPlan.builder().set(target("a"),2L).increment(target("z"),1).build();UUID operation=UUID.randomUUID();
  error(ErrorCode.NUMERIC_OVERFLOW,()->backend.execute(plan,operation,deadline()));assertEquals(1L,backend.get(target("a"),deadline()).orElseThrow().value());assertEquals(before,count("vs_outbox"));assertEquals(OperationStatus.State.NOT_OBSERVED_YET,backend.operation("events",operation,deadline()).state());
 }
 @Test void registeringSubscriptionWaitsForExistingWriterBoundary()throws Exception {
  start();UUID epoch=backend.initialize();
  try(var writer=connection();var executor=Executors.newSingleThreadExecutor()) {
   writer.setAutoCommit(false);enqueueFixture(writer,"before-registration",epoch);
   Future<SubscriptionState> registration=executor.submit(()->subscribe("boundary"));
   // Observe the real backend wait, rather than assuming thread scheduling from a sleep.
   boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<until){try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%vs_networks%FOR UPDATE%')")){r.next();if(r.getBoolean(1)){waiting=true;break;}}Thread.sleep(10);}
   assertTrue(waiting,"Registration must wait for the producer's network SHARE lock");assertFalse(registration.isDone());writer.commit();var state=registration.get(10,TimeUnit.SECONDS);assertTrue(claim(state).isEmpty());set("after-registration",1);assertEquals(1,claim(state).size());
  }
 }
 @Test void scanUsesLiteralPrefixStableKeysetAndBoundCursor()throws Exception {
  start();for(String key:List.of("a_a","a_b","a_c","axa","a_deleted"))set(key,1);backend.write(target("a_deleted"),WriteKind.DELETE,null,0,null,UUID.randomUUID(),deadline());
  var first=backend.scanKeys(target("anchor").address(),"a_",Optional.empty(),2,deadline());assertEquals(List.of("a_a","a_b"),first.keys().stream().map(KeyMetadata::key).toList());assertEquals(ValueType.LONG,first.keys().getFirst().type());assertTrue(first.nextCursor().isPresent());
  var second=backend.scanKeys(target("anchor").address(),"a_",first.nextCursor(),2,deadline());assertEquals(List.of("a_c"),second.keys().stream().map(KeyMetadata::key).toList());assertTrue(second.nextCursor().isEmpty());
  error(ErrorCode.INVALID_ARGUMENT,()->backend.scanKeys(target("anchor").address(),"a",first.nextCursor(),2,deadline()));error(ErrorCode.INVALID_ARGUMENT,()->backend.scanKeys(target("anchor").address(),"",Optional.empty(),201,deadline()));
  var other=new Address("test","events",ScopeKind.NETWORK,"_",Owner.system("other"),"anchor");error(ErrorCode.INVALID_ARGUMENT,()->backend.scanKeys(other,"a_",first.nextCursor(),2,deadline()));
  var capacity=backend.capacity(deadline());assertEquals(1,capacity.get("rowCountsAreEstimates"));assertEquals(-1,capacity.get("diskFreeBytes"));
  try(var c=connection()){SchemaMigrator.rotateEpoch(c,"test");}backend.close();backend=newBackend();backend.initialize();error(ErrorCode.STALE_EPOCH,()->backend.scanKeys(target("anchor").address(),"a_",first.nextCursor(),2,deadline()));
 }
}
