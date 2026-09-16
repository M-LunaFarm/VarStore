package kr.lunaf.varstore.tools;

import java.sql.*;
import java.time.Duration;
import java.util.Properties;
import kr.lunaf.varstore.postgres.SchemaMigrator;
import kr.lunaf.varstore.postgres.PostgresSettings;
import kr.lunaf.varstore.postgres.PostgresBackend;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Offline administration. Use a separate DDL account for migrate; never log credentials. */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 3) { usage(); System.exit(2); }
        String command=args[0];
        if(command.equals("import-dry-run")) {
            if(args.length<2||(args.length==3&&!args[2].equals("--check-database"))){usage();System.exit(2);return;}
            try {var report=inspectCsv(Path.of(args[1]),args.length==3);System.out.println("mode=DRY_RUN databaseWrites=0 rows="+report.rows()+" validRows="+report.validRows()+" invalidRows="+report.invalidRows()+" collisions="+report.collisions()+" existingAddresses="+report.existingAddresses()+" existingTypeConflicts="+report.existingTypeConflicts()+" databaseChecked="+(args.length==3)+" normalizedUuids="+report.normalizedUuids());System.out.println("supportedTypes=STRING,LONG,BOOLEAN,UUID observedTypes="+report.types());report.samples().forEach(System.out::println);report.errors().forEach(System.out::println);if(!report.valid())System.exit(2);}
            catch(Exception failure){System.err.println("CSV dry run failed: malformed input, invalid UTF-8, unreadable file, or configured bounds exceeded; no database writes occurred.");System.exit(1);}return;
        }
        if (args.length>2 || !java.util.Set.of("migrate","validate","rotate-epoch","prune-results","prune-outbox","diagnostics","capacity").contains(command)) {usage();System.exit(2);}
        String network=args.length==2?args[1]:"production";
        if (command.equals("rotate-epoch") && !"true".equals(System.getenv("VARSTORE_WRITERS_STOPPED"))) {
            System.err.println("Stop all writers, then set VARSTORE_WRITERS_STOPPED=true before epoch rotation.");System.exit(2);
        }
        try {
            String url=required("VARSTORE_JDBC_URL"),user=required("VARSTORE_DB_USER"),password=required("VARSTORE_DB_PASSWORD");
            String tls=System.getenv().getOrDefault("VARSTORE_TLS_MODE","verify-full");
            var settings=new PostgresSettings(url,user,password,network,"tools",tls,true,1,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofSeconds(2));
            if(command.equals("capacity")){capacity(settings);return;}
            Properties properties=new Properties();properties.setProperty("user",user);properties.setProperty("password",password);
            properties.setProperty("sslmode",tls);properties.setProperty("ApplicationName","VarStore-tools");
            properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","30");
            try(Connection connection=DriverManager.getConnection(url,properties)) {
                switch(command) {
                    case "migrate" -> { SchemaMigrator.migrate(connection,network);System.out.println("Schema "+SchemaMigrator.VERSION+" migrated and validated; network="+network); }
                    case "validate" -> { SchemaMigrator.validate(connection);System.out.println("Schema "+SchemaMigrator.VERSION+" validated"); }
                    case "rotate-epoch" -> { SchemaMigrator.validate(connection);System.out.println("New storage epoch: "+SchemaMigrator.rotateEpoch(connection,network)); }
                    case "prune-results" -> { SchemaMigrator.validate(connection);System.out.println("Expired full results: "+SchemaMigrator.pruneResults(connection,Duration.ofDays(7))+" (batch limit 1000; markers retained)"); }
                    case "prune-outbox" -> {int hours=boundedEnv("VARSTORE_OUTBOX_RETENTION_HOURS",24,1,720);int batch=boundedEnv("VARSTORE_OUTBOX_PRUNE_BATCH",1000,1,1000);System.out.println("Pruned outbox events: "+SchemaMigrator.pruneOutbox(connection,network,Duration.ofHours(hours),batch)+" (retentionHours="+hours+", batchLimit="+batch+", affected outstanding subscriptions marked RESYNC_REQUIRED)");}
                    case "diagnostics" -> diagnostics(connection);
                    default -> throw new IllegalStateException();
                }
            }
        } catch(Exception e) { System.err.println("VarStore tool failed: "+e.getClass().getSimpleName()+(e instanceof SQLException sql?" SQLSTATE="+sql.getSQLState():"")+". Check connectivity, credentials, schema and command arguments.");System.exit(1); }
    }
    private static CsvDryRun.Report inspectCsv(Path path,boolean checkDatabase)throws Exception {
        if(!checkDatabase)return CsvDryRun.inspect(path);
        String url=required("VARSTORE_JDBC_URL"),user=required("VARSTORE_DB_USER"),password=required("VARSTORE_DB_PASSWORD"),tls=System.getenv().getOrDefault("VARSTORE_TLS_MODE","verify-full");
        new PostgresSettings(url,user,password,"tools","tools",tls,true,1,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofSeconds(2));
        Properties properties=new Properties();properties.setProperty("user",user);properties.setProperty("password",password);properties.setProperty("sslmode",tls);properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","30");properties.setProperty("ApplicationName","VarStore-import-dry-run");
        try(var connection=DriverManager.getConnection(url,properties)) {
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            try {
                SchemaMigrator.validate(connection);
                var report=CsvDryRun.inspect(path,address->{
                    long remaining=deadline-System.nanoTime();if(remaining<=0)throw new java.io.IOException("Dry-run database inspection deadline exceeded");
                    try(var statement=connection.prepareStatement("SELECT value_type FROM vs_variables WHERE network_id=? AND namespace=? AND scope_kind=? AND scope_id=? AND owner_type=? AND owner_id=? AND variable_key=?")) {
                        statement.setQueryTimeout(Math.max(1,(int)Math.ceil(remaining/1_000_000_000d)));String[] fields=address.fields();for(int i=0;i<fields.length;i++)statement.setString(i+1,fields[i]);
                        try(var result=statement.executeQuery()){return result.next()?java.util.Optional.of(kr.lunaf.varstore.api.ValueType.valueOf(result.getString(1))):java.util.Optional.empty();}
                    }catch(SQLException failure){throw new java.io.IOException("Dry-run database inspection failed");}
                });connection.rollback();return report;
            }finally{connection.rollback();}
        }
    }
    private static int boundedEnv(String name,int fallback,int min,int max){String value=System.getenv(name);int result=value==null?fallback:Integer.parseInt(value);if(result<min||result>max)throw new IllegalArgumentException("Out of bounds setting");return result;}
    private static void capacity(PostgresSettings settings)throws Exception {
        int seconds=boundedEnv("VARSTORE_CAPACITY_SAMPLE_SECONDS",2,1,30);
        try(var backend=new PostgresBackend(settings)) {
            backend.initialize();Map<String,Long> first=backend.capacity(System.nanoTime()+TimeUnit.SECONDS.toNanos(10));long start=System.nanoTime();Thread.sleep(seconds*1000L);Map<String,Long> second=backend.capacity(System.nanoTime()+TimeUnit.SECONDS.toNanos(10));double elapsed=(System.nanoTime()-start)/1_000_000_000d;
            new TreeMap<>(second).forEach((key,value)->System.out.println(key+"="+value));
            long delta=second.getOrDefault("operationsInsertedSinceStatsReset",0L)-first.getOrDefault("operationsInsertedSinceStatsReset",0L);
            System.out.println("growthSource=postgres_statistics_sample scope=TABLE_GLOBAL estimates=true sampleSeconds="+elapsed);
            if(delta<0){System.out.println("growthUnavailable=statistics_reset");return;}
            double rate=delta/elapsed;System.out.println("estimatedNewOperationsPerSecond="+rate);System.out.println("projectedOperationsPerDay="+(long)(rate*86400));System.out.println("projectedOperationsPer30Days="+(long)(rate*2592000));
            long rows=second.getOrDefault("operationsEstimatedRows",0L),bytes=second.getOrDefault("vs_operationsTableBytes",0L)+second.getOrDefault("vs_operationsIndexBytes",0L);
            System.out.println("estimatedOperationBytesIncludingIndexes="+(rows==0?-1:bytes/rows));System.out.println("projectionExcludes=WAL,vacuum,variable_rows,outbox,backup_compression");
            long warning=Long.parseLong(System.getenv().getOrDefault("VARSTORE_OPERATIONS_WARNING_BYTES","10737418240"));if(warning<1)throw new IllegalArgumentException("Invalid warning threshold");System.out.println("operationsCapacityWarning="+(bytes>=warning)+" thresholdBytes="+warning);System.out.println("unavailableHostMetrics=-1; supply disk headroom, backup bytes and restore duration from host monitoring");
        }
    }
    private static String required(String name) { String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value; }
    private static void usage() { System.err.println("Usage: java -jar varstore-tools.jar <migrate|validate|rotate-epoch|prune-results|prune-outbox|diagnostics|capacity> [network-id]; import-dry-run <csv-path> [--check-database]"); }
    private static void diagnostics(Connection c)throws SQLException {
        SchemaMigrator.validate(c);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT relname,n_live_tup,n_dead_tup,pg_table_size(relid),pg_indexes_size(relid) FROM pg_stat_user_tables WHERE relname IN ('vs_networks','vs_variables','vs_operations','vs_admin_audit','vs_schema_history','vs_outbox','vs_subscriptions','vs_outbox_delivery') AND schemaname=current_schema() ORDER BY relname")) {
            System.out.println("table,estimated_live_rows,estimated_dead_rows,table_bytes,index_bytes");
            while(r.next())System.out.printf("%s,%d,%d,%d,%d%n",r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5));
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'VarStore%' AND wait_event_type='Lock'")) {r.next();System.out.println("lock_waiting_connections="+r.getLong(1));}
    }
}
