package kr.lunaf.varstore.testkit;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.postgres.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** The same bytecode runs with original 1.0.0 or current backend jars; no new API dependencies. */
public final class WriteCostHarness {
 private WriteCostHarness(){}
 public static void main(String[] args)throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("label output-path required");
  String base=Objects.requireNonNull(System.getenv("VARSTORE_TEST_JDBC_URL")),user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore"),password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
  if(!base.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):25432/varstore_extensions"))throw new IllegalArgumentException("Use isolated loopback varstore_extensions database");
  String schema="writecost_"+UUID.randomUUID().toString().replace("-","");String url=base+"?currentSchema="+schema;int count=2000;
  Map<String,Object> report=new LinkedHashMap<>();report.put("label",args[0]);report.put("startedAt",Instant.now().toString());report.put("schema",schema);report.put("measurement","sequential SET of a changing LONG on one address, fresh schema,200warmups,2000measured unique IDs");
  try(var admin=DriverManager.getConnection(base,user,password)) {
   try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
   try {
    try(var connection=DriverManager.getConnection(url,user,password)){SchemaMigrator.migrate(connection,"benchmark");}
    var settings=new PostgresSettings(url,user,password,"benchmark","writecost","disable",true,2,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(5));
    try(var backend=new PostgresBackend(settings)) {
     backend.initialize();var target=new Target<Long>(new Address("benchmark","cost",ScopeKind.NETWORK,"_",Owner.system("global"),"counter"),ValueType.LONG);
     for(int i=0;i<200;i++)backend.write(target,WriteKind.SET,(long)i,0,null,UUID.randomUUID(),deadline());
     String before=lsn(admin);long start=System.nanoTime();long[] latency=new long[count];
     for(int i=0;i<count;i++){long tick=System.nanoTime();var receipt=backend.write(target,WriteKind.SET,200L+i,0,null,UUID.randomUUID(),deadline());if(receipt.outcome()!=Outcome.APPLIED)throw new AssertionError("Changed write was not applied");latency[i]=(System.nanoTime()-tick)/1000;}
     double elapsed=(System.nanoTime()-start)/1_000_000_000d;Arrays.sort(latency);report.put("elapsedSeconds",elapsed);report.put("writes",count);report.put("writesPerSecond",count/elapsed);report.put("p50Micros",latency[count/2]);report.put("p95Micros",latency[(int)(count*.95)]);report.put("p99Micros",latency[(int)(count*.99)]);
     try(var statement=admin.prepareStatement("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)::bigint")){statement.setString(1,before);try(var result=statement.executeQuery()){result.next();report.put("databaseWideWalBytes",result.getLong(1));}}
     if(backend.get(target,deadline()).orElseThrow().value()!=2199L)throw new AssertionError("Final value mismatch");
    }
    try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
     try(var result=statement.executeQuery("SELECT max(version) FROM vs_schema_history")){result.next();report.put("schemaVersion",result.getInt(1));}
     try(var result=statement.executeQuery("SELECT count(*),pg_table_size('vs_operations'),pg_indexes_size('vs_operations') FROM vs_operations")){result.next();report.put("operations",result.getLong(1));report.put("operationsTableBytes",result.getLong(2));report.put("operationsIndexBytes",result.getLong(3));}
     try(var result=statement.executeQuery("SELECT coalesce(sum(pg_total_relation_size(relid)),0)::bigint FROM pg_stat_user_tables WHERE schemaname=current_schema()")){result.next();report.put("allTableAndIndexBytes",result.getLong(1));}
     try(var result=statement.executeQuery("SELECT to_regclass('vs_outbox') IS NOT NULL")){result.next();if(result.getBoolean(1)){try(var other=connection.createStatement();var events=other.executeQuery("SELECT count(*) FROM vs_outbox")){events.next();report.put("outboxEvents",events.getLong(1));}}else report.put("outboxEvents",0);}
    }
    report.put("status","PASS");report.put("limitations",List.of("Single-host sequential microbenchmark; no Paper TPS or multiplayer throughput claim.","WAL counter is database-wide and can include concurrent activity; run comparison without other load.","Each version uses a fresh schema; cache warmth and host noise can influence short measurements."));
   }finally{try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
  }
  Path output=Path.of(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,HarnessJson.json(report)+"\n");System.out.println(HarnessJson.json(report));
 }
 private static String lsn(Connection connection)throws SQLException{try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT pg_current_wal_lsn()::text")){result.next();return result.getString(1);}}
 private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}
}
