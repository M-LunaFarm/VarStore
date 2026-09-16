package kr.lunaf.varstore.testkit;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.core.*;
import kr.lunaf.varstore.postgres.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Destructive only to the explicitly selected isolated test database/network. */
public final class FaultHarness {
    private FaultHarness() {}
    public static void main(String[] args) throws Exception {
        String mode=args.length==0?"commit-response":args[0];
        String url=System.getenv().getOrDefault("VARSTORE_TEST_JDBC_URL","jdbc:postgresql://127.0.0.1:25432/varstore");
        String user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");
        String password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
        String network="faulttest";
        try(var c=DriverManager.getConnection(url,user,password)) {SchemaMigrator.migrate(c,network);}
        if(mode.equals("commit-response")||mode.equals("pre-commit")) {
            boolean beforeCommit=mode.equals("pre-commit");
            String proxy=System.getenv().getOrDefault("VARSTORE_FAULT_JDBC_URL","jdbc:postgresql://127.0.0.1:25433/varstore");
            try(VarStore store=open(proxy,user,password,network)) {
                store.ready().toCompletableFuture().get(20,TimeUnit.SECONDS);
                var data=store.namespace("fault").network().system("global");var key=VarKey.longKey("count/"+UUID.randomUUID());
                await(data.set(key,0L));UUID id=UUID.randomUUID();
                HttpClient client=HttpClient.newHttpClient();
                client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:25434/"+(beforeCommit?"arm-before-commit":"arm"))).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                boolean unknown=false;
                try {await(data.increment(key,1,id));}
                catch(ExecutionException e) { System.out.println("FAULT_OBSERVED="+e.getCause()); unknown=e.getCause() instanceof VarStoreException v && v.code()==ErrorCode.UNKNOWN_COMMIT_OUTCOME;}
                if(!unknown)throw new AssertionError("Expected UNKNOWN_COMMIT_OUTCOME from dropped COMMIT response");
                // Independent direct DB observation establishes actual commit before replay.
                try(var c=DriverManager.getConnection(url,user,password);var s=c.prepareStatement("SELECT long_value FROM vs_variables WHERE network_id=? AND namespace='fault' AND variable_key=?")) {
                    s.setString(1,network);s.setString(2,key.name());try(var r=s.executeQuery()){if(!r.next()||r.getLong(1)!=(beforeCommit?0:1))throw new AssertionError("Independent database value does not match injected commit boundary");}
                }
                assertOutboxCount(url,user,password,network,id,beforeCommit?0:1);
                awaitHealthy(store);
                if(beforeCommit&&await(store.namespace("fault").operation(id)).state()!=OperationStatus.State.NOT_OBSERVED_YET)throw new AssertionError("Aborted operation unexpectedly persisted");
                var receipt=await(data.increment(key,1,id));
                if(receipt.replayed()==beforeCommit||await(data.get(key)).orElseThrow()!=1L)throw new AssertionError("Replay changed value");
                assertOutboxCount(url,user,password,network,id,1);
                System.out.println("{\"test\":\""+(beforeCommit?"T07_PRE_COMMIT":"T07")+"\",\"passed\":true,\"fault\":\""+(beforeCommit?"actual backend connection closed before COMMIT forwarding":"actual COMMIT response bytes dropped")+"\",\"value\":1,\"outbox_events_before_retry\":"+(beforeCommit?0:1)+",\"outbox_events_after_retry\":1}");
            }
        } else if (mode.equals("recovery")) {
            try (VarStore old = open(url,user,password,network)) {
                await(old.ready());var d=old.namespace("restore").network().system("global");var key=VarKey.longKey("point");
                UUID before=UUID.randomUUID(), after=UUID.randomUUID();
                await(d.set(key,10L,before));
                System.out.println("RECOVERY_BACKUP_READY");
                new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
                await(d.set(key,20L,after));System.out.println("RECOVERY_RESTORE_READY");
                new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
                boolean stale=false;
                try {await(d.set(key,30L,UUID.randomUUID()));}
                catch(ExecutionException e) {stale=e.getCause() instanceof VarStoreException v && v.code()==ErrorCode.STALE_EPOCH;}
                if(!stale)throw new AssertionError("Old handle not fenced after restore");
                try(VarStore fresh=open(url,user,password,network)) {
                    await(fresh.ready());var ns=fresh.namespace("restore");var restored=ns.network().system("global");
                    if(await(restored.get(key)).orElseThrow()!=10L)throw new AssertionError("Backup value not restored");
                    if(await(ns.operation(before)).state()!=OperationStatus.State.COMPLETED)throw new AssertionError("Backup operation lost");
                    if(await(ns.operation(after)).state()!=OperationStatus.State.NOT_OBSERVED_YET)throw new AssertionError("Post-backup operation survived restore");
                    await(restored.set(key,40L));
                }
                System.out.println("{\"test\":\"T18\",\"passed\":true,\"restored_value\":10,\"post_backup_loss\":\"explicitly verified\",\"old_writer\":\"STALE_EPOCH\"}");
            }
        } else if(mode.equals("seed")||mode.equals("verify")) {
            try(VarStore store=open(url,user,password,network)) {
                await(store.ready());var d=store.namespace("crash").network().system("global");var key=VarKey.stringKey("confirmed");
                if(mode.equals("seed"))await(d.set(key,"durable-commit"));
                else if(!await(d.get(key)).orElseThrow().equals("durable-commit"))throw new AssertionError("Committed value lost");
                System.out.println("{\"mode\":\""+mode+"\",\"passed\":true}");
            }
        } else throw new IllegalArgumentException("Unknown mode");
    }
    private static void assertOutboxCount(String url,String user,String password,String network,UUID operation,int expected)throws SQLException {
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.prepareStatement("SELECT count(*) FROM vs_outbox WHERE network_id=? AND namespace='fault' AND operation_id=?")) {
            statement.setString(1,network);statement.setObject(2,operation);try(var result=statement.executeQuery()){result.next();if(result.getLong(1)!=expected)throw new AssertionError("Unexpected outbox event count at actual commit boundary");}
        }
    }
    private static VarStore open(String url,String user,String password,String network) {
        return StoreFactory.open(StoreConfig.defaults(new PostgresSettings(url,user,password,network,"fault-runner","disable",true,4,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofMillis(1500))));
    }
    private static <T>T await(CompletionStage<T> stage)throws Exception {return stage.toCompletableFuture().get(20,TimeUnit.SECONDS);}
    private static void awaitHealthy(VarStore store)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(store.state()!=StoreState.READY&&System.nanoTime()<deadline)Thread.sleep(20);
        if(store.state()!=StoreState.READY)throw new AssertionError("Store failed to recover");
    }
}
