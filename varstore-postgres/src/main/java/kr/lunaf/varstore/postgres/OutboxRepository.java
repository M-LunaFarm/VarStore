package kr.lunaf.varstore.postgres;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Per-event fan-out state. Every method runs in its caller's short, epoch-fenced transaction. */
final class OutboxRepository {
 @FunctionalInterface interface Sql {PreparedStatement prepare(Connection c,String sql,long deadline)throws SQLException;}
 private final String network,server;private final Sql sql;
 OutboxRepository(String network,String server,Sql sql){this.network=network;this.server=server;this.sql=sql;}
 void enqueue(Connection c,Address address,UUID operation,VersionToken version,ChangeKind kind,long deadline)throws SQLException {
  long id;
  try(var statement=sql.prepare(c,"INSERT INTO vs_outbox(network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,operation_id,storage_epoch,generation,revision,kind,source_server) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING event_id",deadline)) {
   int p=1;for(String field:address.fields())statement.setString(p++,field);statement.setObject(p++,operation);statement.setObject(p++,version.storageEpoch());statement.setObject(p++,version.generation());statement.setLong(p++,version.revision());statement.setString(p++,kind.name());statement.setString(p,server);
   try(var result=statement.executeQuery()){result.next();id=result.getLong(1);}
  }
  // Registration holds the network row exclusively; writers hold it shared. Thus
  // a registration cannot slip between a pre-registration event and its commit.
  try(var statement=sql.prepare(c,"INSERT INTO vs_outbox_delivery(network_id,subscriber_id,event_id) SELECT network_id,subscriber_id,? FROM vs_subscriptions WHERE network_id=? AND namespace=? AND storage_epoch=? AND (mode='DURABLE' OR (active AND lease_until>clock_timestamp()))",deadline)) {
   statement.setLong(1,id);statement.setString(2,network);statement.setString(3,address.namespace());statement.setObject(4,version.storageEpoch());statement.executeUpdate();
  }
 }
 SubscriptionState register(Connection c,SubscriptionSpec spec,UUID epoch,long deadline)throws SQLException {
  collectExpiredEphemeral(c,spec.subscriberId(),deadline);
  boolean exists=false,resync=false;
  try(var statement=sql.prepare(c,"SELECT namespace,mode,storage_epoch,resync_required,lease_until<=clock_timestamp(),active FROM vs_subscriptions WHERE network_id=? AND subscriber_id=? FOR UPDATE",deadline)) {
   statement.setString(1,network);statement.setString(2,spec.subscriberId());try(var rows=statement.executeQuery()){if(rows.next()){
    exists=true;if(!spec.namespace().equals(rows.getString(1))||!spec.mode().name().equals(rows.getString(2)))throw invalid("Subscriber ID is bound to another namespace or mode");
    resync=rows.getBoolean(4)||!epoch.equals(rows.getObject(3,UUID.class))||spec.mode()==SubscriptionMode.EPHEMERAL&&(rows.getBoolean(5)||!rows.getBoolean(6));
   }}
  }
  if(exists&&retentionExpired(c,spec.subscriberId(),spec.retention().toMillis(),deadline))resync=true;
  UUID session=UUID.randomUUID();Instant until;
  try(var statement=sql.prepare(c,"INSERT INTO vs_subscriptions(network_id,subscriber_id,namespace,mode,storage_epoch,session_token,lease_until,retention_millis,resync_required) VALUES(?,?,?,?,?,?,clock_timestamp()+(? * interval '1 millisecond'),?,?) ON CONFLICT(network_id,subscriber_id) DO UPDATE SET storage_epoch=EXCLUDED.storage_epoch,session_token=EXCLUDED.session_token,lease_until=EXCLUDED.lease_until,retention_millis=EXCLUDED.retention_millis,resync_required=EXCLUDED.resync_required,active=true,updated_at=clock_timestamp() RETURNING lease_until",deadline)) {
   statement.setString(1,network);statement.setString(2,spec.subscriberId());statement.setString(3,spec.namespace());statement.setString(4,spec.mode().name());statement.setObject(5,epoch);statement.setObject(6,session);statement.setLong(7,spec.leaseDuration().toMillis());statement.setLong(8,spec.retention().toMillis());statement.setBoolean(9,resync);
   try(var rows=statement.executeQuery()){rows.next();until=rows.getTimestamp(1).toInstant();}
  }
  try(var statement=sql.prepare(c,"UPDATE vs_outbox_delivery SET state='PENDING',lease_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp() WHERE network_id=? AND subscriber_id=? AND state='LEASED'",deadline)) {statement.setString(1,network);statement.setString(2,spec.subscriberId());statement.executeUpdate();}
  return new SubscriptionState(spec.subscriberId(),session,epoch,until,resync);
 }
 SubscriptionState renew(Connection c,SubscriptionState state,Duration lease,UUID epoch,long deadline)throws SQLException {
  SubscriptionSpec.validateLease(lease);Row row=check(c,state,epoch,deadline);boolean resync=row.resync||retentionExpired(c,state.subscriberId(),row.retentionMillis,deadline);
  try(var statement=sql.prepare(c,"UPDATE vs_subscriptions SET lease_until=clock_timestamp()+(? * interval '1 millisecond'),resync_required=?,updated_at=clock_timestamp() WHERE network_id=? AND subscriber_id=? RETURNING lease_until",deadline)) {
   statement.setLong(1,lease.toMillis());statement.setBoolean(2,resync);statement.setString(3,network);statement.setString(4,state.subscriberId());try(var rows=statement.executeQuery()){rows.next();return new SubscriptionState(state.subscriberId(),state.sessionToken(),epoch,rows.getTimestamp(1).toInstant(),resync);}
  }
 }
 record Claimed(List<Delivery> deliveries,boolean resync){}
 Claimed claim(Connection c,SubscriptionState state,int limit,Duration lease,UUID epoch,long deadline)throws SQLException {
  if(limit<1||limit>200)throw invalid("Claim limit must be1..200");SubscriptionSpec.validateLease(lease);Row row=check(c,state,epoch,deadline);
  if(row.resync||retentionExpired(c,state.subscriberId(),row.retentionMillis,deadline)) {
   markResync(c,state.subscriberId(),deadline);return new Claimed(List.of(),true);
  }
  // A repeatedly crashed delivery is retained as a visible dead letter, never dropped.
  try(var statement=sql.prepare(c,"UPDATE vs_outbox_delivery SET state='DEAD',lease_token=NULL,lease_until=NULL,last_error='LEASE_EXHAUSTED' WHERE network_id=? AND subscriber_id=? AND state='LEASED' AND lease_until<=clock_timestamp() AND attempts>=100",deadline)){statement.setString(1,network);statement.setString(2,state.subscriberId());statement.executeUpdate();}
  List<Long> ids=new ArrayList<>();
  try(var statement=sql.prepare(c,"SELECT event_id FROM vs_outbox_delivery WHERE network_id=? AND subscriber_id=? AND ((state='PENDING' AND next_attempt_at<=clock_timestamp()) OR (state='LEASED' AND lease_until<=clock_timestamp())) ORDER BY next_attempt_at,event_id LIMIT ? FOR UPDATE SKIP LOCKED",deadline)) {
   statement.setString(1,network);statement.setString(2,state.subscriberId());statement.setInt(3,limit);try(var rows=statement.executeQuery()){while(rows.next())ids.add(rows.getLong(1));}
  }
  List<Delivery> result=new ArrayList<>();
  for(long id:ids) {
   UUID token=UUID.randomUUID();int attempt;Instant until;
   try(var statement=sql.prepare(c,"UPDATE vs_outbox_delivery SET state='LEASED',lease_token=?,lease_until=LEAST(clock_timestamp()+(? * interval '1 millisecond'),?),attempts=attempts+1 WHERE network_id=? AND subscriber_id=? AND event_id=? RETURNING attempts,lease_until",deadline)) {
    statement.setObject(1,token);statement.setLong(2,lease.toMillis());statement.setTimestamp(3,Timestamp.from(row.leaseUntil));statement.setString(4,network);statement.setString(5,state.subscriberId());statement.setLong(6,id);try(var rows=statement.executeQuery()){rows.next();attempt=rows.getInt(1);until=rows.getTimestamp(2).toInstant();}
   }
   try(var statement=sql.prepare(c,"SELECT operation_id,network_id,namespace,scope_kind,scope_id,owner_type,owner_id,variable_key,storage_epoch,generation,revision,kind,source_server,created_at FROM vs_outbox WHERE event_id=?",deadline)) {
    statement.setLong(1,id);try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("Claimed event disappeared");
     Address address=new Address(rows.getString(2),rows.getString(3),ScopeKind.valueOf(rows.getString(4)),rows.getString(5),new Owner(rows.getString(6),rows.getString(7)),rows.getString(8));
     ChangeEvent event=new ChangeEvent(id,rows.getObject(1,UUID.class),address,new VersionToken(rows.getObject(9,UUID.class),rows.getObject(10,UUID.class),rows.getLong(11)),ChangeKind.valueOf(rows.getString(12)),rows.getString(13),rows.getTimestamp(14).toInstant());result.add(new Delivery(event,token,attempt,until));
    }
   }
  }
  return new Claimed(List.copyOf(result),false);
 }
 boolean acknowledge(Connection c,SubscriptionState state,long event,UUID token,UUID epoch,long deadline)throws SQLException {
  check(c,state,epoch,deadline);
  try(var statement=sql.prepare(c,"UPDATE vs_outbox_delivery SET state='ACKED',lease_token=NULL,lease_until=NULL,ack_at=clock_timestamp(),last_error=NULL WHERE network_id=? AND subscriber_id=? AND event_id=? AND state='LEASED' AND lease_token=? AND lease_until>clock_timestamp()",deadline)){bindDelivery(statement,state,event,token);return statement.executeUpdate()==1;}
 }
 boolean fail(Connection c,SubscriptionState state,long event,UUID token,String error,int maxAttempts,Duration delay,UUID epoch,long deadline)throws SQLException {
  if(error==null||!error.matches("[A-Z0-9_]{1,64}")||maxAttempts<1||maxAttempts>100||delay==null||delay.toMillis()<10||delay.compareTo(Duration.ofHours(1))>0)throw invalid("Invalid bounded delivery retry");check(c,state,epoch,deadline);
  try(var statement=sql.prepare(c,"UPDATE vs_outbox_delivery SET state=CASE WHEN attempts>=? THEN 'DEAD' ELSE 'PENDING' END,lease_token=NULL,lease_until=NULL,last_error=?,next_attempt_at=clock_timestamp()+(LEAST(3600000,? * power(2,LEAST(attempts-1,20))) * interval '1 millisecond') WHERE network_id=? AND subscriber_id=? AND event_id=? AND state='LEASED' AND lease_token=? AND lease_until>clock_timestamp()",deadline)) {
   statement.setInt(1,maxAttempts);statement.setString(2,error);statement.setLong(3,delay.toMillis());statement.setString(4,network);statement.setString(5,state.subscriberId());statement.setLong(6,event);statement.setObject(7,token);return statement.executeUpdate()==1;
  }
 }
 SubscriptionState reset(Connection c,SubscriptionState state,UUID epoch,long deadline)throws SQLException {
  Row row=check(c,state,epoch,deadline);
  try(var statement=sql.prepare(c,"DELETE FROM vs_outbox_delivery WHERE network_id=? AND subscriber_id=?",deadline)){statement.setString(1,network);statement.setString(2,state.subscriberId());statement.executeUpdate();}
  try(var statement=sql.prepare(c,"UPDATE vs_subscriptions SET resync_required=false,updated_at=clock_timestamp() WHERE network_id=? AND subscriber_id=?",deadline)){statement.setString(1,network);statement.setString(2,state.subscriberId());statement.executeUpdate();}
  return new SubscriptionState(state.subscriberId(),state.sessionToken(),epoch,row.leaseUntil,false);
 }
 void close(Connection c,SubscriptionState state,UUID epoch,long deadline)throws SQLException {
  Row row=check(c,state,epoch,deadline);
  if(row.mode==SubscriptionMode.EPHEMERAL){try(var statement=sql.prepare(c,"DELETE FROM vs_subscriptions WHERE network_id=? AND subscriber_id=?",deadline)){statement.setString(1,network);statement.setString(2,state.subscriberId());statement.executeUpdate();}return;}
  try(var statement=sql.prepare(c,"UPDATE vs_subscriptions SET active=false,lease_until=clock_timestamp(),updated_at=clock_timestamp() WHERE network_id=? AND subscriber_id=?",deadline)){statement.setString(1,network);statement.setString(2,state.subscriberId());statement.executeUpdate();}
 }
 int retryDead(Connection c,SubscriptionState state,int limit,UUID epoch,long deadline)throws SQLException {
  if(limit<1||limit>200)throw invalid("Retry limit must be1..200");Row row=check(c,state,epoch,deadline);if(row.resync)throw new VarStoreException(ErrorCode.RESYNC_REQUIRED,"Subscription requires resynchronization");
  try(var statement=sql.prepare(c,"WITH selected AS (SELECT event_id FROM vs_outbox_delivery WHERE network_id=? AND subscriber_id=? AND state='DEAD' ORDER BY event_id LIMIT ? FOR UPDATE SKIP LOCKED) UPDATE vs_outbox_delivery d SET state='PENDING',attempts=0,last_error=NULL,next_attempt_at=clock_timestamp() FROM selected s WHERE d.network_id=? AND d.subscriber_id=? AND d.event_id=s.event_id",deadline)) {
   statement.setString(1,network);statement.setString(2,state.subscriberId());statement.setInt(3,limit);statement.setString(4,network);statement.setString(5,state.subscriberId());return statement.executeUpdate();
  }
 }
 int prune(Connection c,Duration retention,int limit,long deadline)throws SQLException {
  if(retention==null||retention.compareTo(Duration.ofHours(1))<0||retention.compareTo(Duration.ofDays(30))>0||limit<1||limit>1000)throw invalid("Outbox retention must be1hour..30days and batch1..1000");
  collectExpiredEphemeral(c,"",deadline);
  List<Long> ids=new ArrayList<>();
  try(var statement=sql.prepare(c,"SELECT e.event_id FROM vs_outbox e WHERE e.network_id=? AND e.created_at<clock_timestamp()-(? * interval '1 millisecond') AND NOT EXISTS(SELECT 1 FROM vs_outbox_delivery d JOIN vs_subscriptions s ON s.network_id=d.network_id AND s.subscriber_id=d.subscriber_id WHERE d.event_id=e.event_id AND d.state<>'ACKED' AND s.mode='DURABLE' AND e.created_at>=clock_timestamp()-(s.retention_millis * interval '1 millisecond')) ORDER BY e.created_at,e.event_id LIMIT ? FOR UPDATE OF e SKIP LOCKED",deadline)) {
   statement.setString(1,network);statement.setLong(2,retention.toMillis());statement.setInt(3,limit);try(var rows=statement.executeQuery()){while(rows.next())ids.add(rows.getLong(1));}
  }
  if(ids.isEmpty())return 0;
  Array array=c.createArrayOf("bigint",ids.toArray(Long[]::new));
  try {
   try(var statement=sql.prepare(c,"UPDATE vs_subscriptions s SET resync_required=true,updated_at=clock_timestamp() WHERE network_id=? AND EXISTS(SELECT 1 FROM vs_outbox_delivery d WHERE d.network_id=s.network_id AND d.subscriber_id=s.subscriber_id AND d.event_id=ANY(?) AND d.state<>'ACKED')",deadline)){statement.setString(1,network);statement.setArray(2,array);statement.executeUpdate();}
   try(var statement=sql.prepare(c,"DELETE FROM vs_outbox WHERE network_id=? AND event_id=ANY(?)",deadline)){statement.setString(1,network);statement.setArray(2,array);return statement.executeUpdate();}
  }finally{array.free();}
 }
 private Row check(Connection c,SubscriptionState state,UUID epoch,long deadline)throws SQLException {
  Objects.requireNonNull(state);if(!epoch.equals(state.storageEpoch()))throw new VarStoreException(ErrorCode.STALE_EPOCH,"Subscription belongs to an old epoch");
  try(var statement=sql.prepare(c,"SELECT session_token,storage_epoch,lease_until,resync_required,retention_millis,active AND lease_until>clock_timestamp(),mode FROM vs_subscriptions WHERE network_id=? AND subscriber_id=? FOR NO KEY UPDATE",deadline)) {
   statement.setString(1,network);statement.setString(2,state.subscriberId());try(var rows=statement.executeQuery()){
    if(!rows.next()||!state.sessionToken().equals(rows.getObject(1,UUID.class))||!epoch.equals(rows.getObject(2,UUID.class))||!rows.getBoolean(6))throw new VarStoreException(ErrorCode.SUBSCRIPTION_EXPIRED,"Subscription session expired or was replaced");
    return new Row(rows.getTimestamp(3).toInstant(),rows.getBoolean(4),rows.getLong(5),SubscriptionMode.valueOf(rows.getString(7)));
   }
  }
 }
 private record Row(Instant leaseUntil,boolean resync,long retentionMillis,SubscriptionMode mode){}
 private void collectExpiredEphemeral(Connection c,String preserved,long deadline)throws SQLException {
  try(var statement=sql.prepare(c,"WITH expired AS (SELECT network_id,subscriber_id FROM vs_subscriptions WHERE network_id=? AND subscriber_id<>? AND mode='EPHEMERAL' AND lease_until<=clock_timestamp() ORDER BY lease_until LIMIT 1000 FOR UPDATE SKIP LOCKED) DELETE FROM vs_subscriptions s USING expired e WHERE s.network_id=e.network_id AND s.subscriber_id=e.subscriber_id",deadline)){statement.setString(1,network);statement.setString(2,preserved);statement.executeUpdate();}
 }
 private boolean retentionExpired(Connection c,String subscriber,long retention,long deadline)throws SQLException {
  try(var statement=sql.prepare(c,"SELECT EXISTS(SELECT 1 FROM vs_outbox_delivery d JOIN vs_outbox e ON e.event_id=d.event_id WHERE d.network_id=? AND d.subscriber_id=? AND d.state<>'ACKED' AND e.created_at<clock_timestamp()-(? * interval '1 millisecond'))",deadline)){statement.setString(1,network);statement.setString(2,subscriber);statement.setLong(3,retention);try(var rows=statement.executeQuery()){rows.next();return rows.getBoolean(1);}}
 }
 private void markResync(Connection c,String subscriber,long deadline)throws SQLException {try(var statement=sql.prepare(c,"UPDATE vs_subscriptions SET resync_required=true,updated_at=clock_timestamp() WHERE network_id=? AND subscriber_id=?",deadline)){statement.setString(1,network);statement.setString(2,subscriber);statement.executeUpdate();}}
 private void bindDelivery(PreparedStatement statement,SubscriptionState state,long event,UUID token)throws SQLException {if(event<1)throw invalid("Invalid event");Objects.requireNonNull(token);statement.setString(1,network);statement.setString(2,state.subscriberId());statement.setLong(3,event);statement.setObject(4,token);}
 private static VarStoreException invalid(String message){return new VarStoreException(ErrorCode.INVALID_ARGUMENT,message);}
}
