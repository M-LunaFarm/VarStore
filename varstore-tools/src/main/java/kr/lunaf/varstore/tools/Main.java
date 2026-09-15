package kr.lunaf.varstore.tools;

import java.sql.*;
import java.time.Duration;
import java.util.Properties;
import kr.lunaf.varstore.postgres.SchemaMigrator;
import kr.lunaf.varstore.postgres.PostgresSettings;

/** Offline administration. Use a separate DDL account for migrate; never log credentials. */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) { usage(); System.exit(2); }
        String command=args[0];
        if (!java.util.Set.of("migrate","validate","rotate-epoch","prune-results","diagnostics").contains(command)) {usage();System.exit(2);}
        String network=args.length==2?args[1]:"production";
        if (command.equals("rotate-epoch") && !"true".equals(System.getenv("VARSTORE_WRITERS_STOPPED"))) {
            System.err.println("Stop all writers, then set VARSTORE_WRITERS_STOPPED=true before epoch rotation.");System.exit(2);
        }
        try {
            String url=required("VARSTORE_JDBC_URL"),user=required("VARSTORE_DB_USER"),password=required("VARSTORE_DB_PASSWORD");
            String tls=System.getenv().getOrDefault("VARSTORE_TLS_MODE","verify-full");
            new PostgresSettings(url,user,password,network,"tools",tls,true,1,Duration.ofSeconds(1),Duration.ofMillis(500),Duration.ofSeconds(2));
            Properties properties=new Properties();properties.setProperty("user",user);properties.setProperty("password",password);
            properties.setProperty("sslmode",tls);properties.setProperty("ApplicationName","VarStore-tools");
            properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","30");
            try(Connection connection=DriverManager.getConnection(url,properties)) {
                switch(command) {
                    case "migrate" -> { SchemaMigrator.migrate(connection,network);System.out.println("Schema "+SchemaMigrator.VERSION+" migrated and validated; network="+network); }
                    case "validate" -> { SchemaMigrator.validate(connection);System.out.println("Schema "+SchemaMigrator.VERSION+" validated"); }
                    case "rotate-epoch" -> { SchemaMigrator.validate(connection);System.out.println("New storage epoch: "+SchemaMigrator.rotateEpoch(connection,network)); }
                    case "prune-results" -> { SchemaMigrator.validate(connection);System.out.println("Expired full results: "+SchemaMigrator.pruneResults(connection,Duration.ofDays(7))+" (batch limit 1000; markers retained)"); }
                    case "diagnostics" -> diagnostics(connection);
                    default -> throw new IllegalStateException();
                }
            }
        } catch(Exception e) { System.err.println("VarStore tool failed: "+e.getClass().getSimpleName()+(e instanceof SQLException sql?" SQLSTATE="+sql.getSQLState():"")+". Check connectivity, credentials, schema and command arguments.");System.exit(1); }
    }
    private static String required(String name) { String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value; }
    private static void usage() { System.err.println("Usage: java -jar varstore-tools.jar <migrate|validate|rotate-epoch|prune-results|diagnostics> [network-id]"); }
    private static void diagnostics(Connection c)throws SQLException {
        SchemaMigrator.validate(c);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT relname,n_live_tup,n_dead_tup,pg_table_size(relid),pg_indexes_size(relid) FROM pg_stat_user_tables WHERE relname IN ('vs_networks','vs_variables','vs_operations','vs_admin_audit','vs_schema_history') ORDER BY relname")) {
            System.out.println("table,estimated_live_rows,estimated_dead_rows,table_bytes,index_bytes");
            while(r.next())System.out.printf("%s,%d,%d,%d,%d%n",r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5));
        }
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'VarStore%' AND wait_event_type='Lock'")) {r.next();System.out.println("lock_waiting_connections="+r.getLong(1));}
    }
}
