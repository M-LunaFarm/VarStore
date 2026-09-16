package kr.lunaf.varstore.postgres;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/** Explicit maintenance-only schema management. Runtime connections only call validate. */
public final class SchemaMigrator {
    public static final int VERSION = 2;
    private static final long LOCK_ID = 0x56415253544f5245L;
    private SchemaMigrator() {}

    public static void migrate(Connection connection,String networkId)throws SQLException {migrateTo(connection,networkId,VERSION);}

    /** Maintenance-only sequential upgrade; all missing versions commit together or all roll back. */
    public static void migrateTo(Connection connection,String networkId,int targetVersion)throws SQLException {
        if(networkId==null||!networkId.matches("[a-z0-9._-]{1,64}"))throw new IllegalArgumentException("Invalid network ID");
        if(targetVersion<1||targetVersion>VERSION)throw new IllegalArgumentException("Unsupported target schema");
        if(!connection.getAutoCommit())throw new SQLException("Migration requires an idle auto-commit connection");
        connection.setAutoCommit(false);
        try {
            try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")){statement.setLong(1,LOCK_ID);statement.execute();}
            boolean exists;try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT to_regclass('vs_schema_history') IS NOT NULL")){result.next();exists=result.getBoolean(1);}
            int current=exists?historyVersion(connection):0;
            if(current>targetVersion)throw new SQLException("Downgrade or mixed-version schema is unsupported");
            if(current>0)validateCatalog(connection,current);
            for(int version=current+1;version<=targetVersion;version++) {
                try(var statement=connection.createStatement()){statement.execute(script(version));}
                try(var statement=connection.prepareStatement("INSERT INTO vs_schema_history(version,checksum,catalog_checksum,tool_version) VALUES(?,?,?,?)")) {
                    statement.setInt(1,version);statement.setString(2,sha256(script(version)));statement.setString(3,catalogChecksum(connection,version));statement.setString(4,"1.3.0");statement.executeUpdate();
                }
            }
            validateCatalog(connection,targetVersion);
            try(var statement=connection.prepareStatement("INSERT INTO vs_networks(network_id,storage_epoch) VALUES(?,?) ON CONFLICT DO NOTHING")){statement.setString(1,networkId);statement.setObject(2,UUID.randomUUID());statement.executeUpdate();}
            connection.commit();
        }catch(SQLException|RuntimeException error){connection.rollback();throw error;}
        finally{connection.setAutoCommit(true);}
    }
    private static int historyVersion(Connection connection)throws SQLException {
        int expected=1;
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT version,checksum FROM vs_schema_history ORDER BY version")) {
            while(result.next()) {
                if(result.getInt(1)!=expected||expected>VERSION||!sha256(script(expected)).equals(result.getString(2)))throw new SQLException("Migration history gap, unsupported version, or immutable SQL checksum mismatch");
                expected++;
            }
        }
        if(expected==1)throw new SQLException("Migration history is empty");return expected-1;
    }

    public static void validate(Connection connection) throws SQLException {
        int version=historyVersion(connection);if(version!=VERSION)throw new SQLException("Schema upgrade required before starting this runtime");validateCatalog(connection,version);
    }
    private static void validateCatalog(Connection connection,int version)throws SQLException {
        // Version 1 owns plain tables. User triggers, rewrite rules, or row policies
        // can change storage semantics without changing columns or index definitions.
        String unsupported="SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace " +
                "WHERE n.nspname=current_schema() AND c.relname IN ("+tables(version)+") " +
                "AND (c.relrowsecurity OR c.relforcerowsecurity " +
                "OR EXISTS(SELECT 1 FROM pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal) " +
                "OR EXISTS(SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid) " +
                "OR EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)))";
        try(var statement=connection.createStatement();var result=statement.executeQuery(unsupported)) {
            result.next();if(result.getBoolean(1))throw new SQLException("VarStore schema contains unsupported triggers, rewrite rules, or row security");
        }
        try(var statement=connection.prepareStatement("SELECT catalog_checksum FROM vs_schema_history WHERE version=?")) {
            statement.setInt(1,version);try(var result=statement.executeQuery()){if(!result.next()||!catalogChecksum(connection,version).equals(result.getString(1)))throw new SQLException("VarStore final catalog mismatch");}
        }
    }

    /** All writers must be stopped for restore; this update also fences transactions holding the old epoch. */
    public static UUID rotateEpoch(Connection connection, String networkId) throws SQLException {
        UUID epoch = UUID.randomUUID();
        try (var statement = connection.prepareStatement("UPDATE vs_networks SET storage_epoch=? WHERE network_id=?")) {
            statement.setObject(1,epoch); statement.setString(2,networkId);
            if (statement.executeUpdate()!=1) throw new SQLException("Network is not provisioned");
        }
        return epoch;
    }

    /** Removes full receipts in bounded chunks and permanently retains fingerprint markers. */
    public static int pruneResults(Connection connection, Duration retention) throws SQLException {
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("Retention must be positive");
        try (var statement = connection.prepareStatement("WITH expired AS (SELECT network_id,namespace,operation_id FROM vs_operations WHERE NOT result_expired AND completed_at < clock_timestamp() - (? * interval '1 millisecond') ORDER BY completed_at LIMIT 1000 FOR UPDATE SKIP LOCKED) UPDATE vs_operations o SET result_payload=NULL,result_expired=true FROM expired e WHERE o.network_id=e.network_id AND o.namespace=e.namespace AND o.operation_id=e.operation_id")) {
            statement.setLong(1,retention.toMillis()); return statement.executeUpdate();
        }
    }

    /** Bounded maintenance compaction; durable loss boundaries are marked before rows are removed. */
    public static int pruneOutbox(Connection connection,String networkId,Duration retention,int limit)throws SQLException {
        if(networkId==null||!networkId.matches("[a-z0-9._-]{1,64}"))throw new IllegalArgumentException("Invalid network ID");
        if(!connection.getAutoCommit())throw new SQLException("Outbox maintenance requires an idle connection");
        connection.setAutoCommit(false);
        try {
            validate(connection);
            try(var statement=connection.prepareStatement("SELECT storage_epoch FROM vs_networks WHERE network_id=? FOR UPDATE")){statement.setString(1,networkId);statement.setQueryTimeout(30);try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("Network is not provisioned");}}
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            OutboxRepository repository=new OutboxRepository(networkId,"maintenance",(c,query,end)->{var statement=c.prepareStatement(query);statement.setQueryTimeout(Math.max(1,(int)java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(Math.max(0,end-System.nanoTime()))));return statement;});
            int removed=repository.prune(connection,retention,limit,deadline);connection.commit();return removed;
        }catch(SQLException|RuntimeException failure){connection.rollback();throw failure;}finally{connection.setAutoCommit(true);}
    }

    private static String catalogChecksum(Connection connection,int version) throws SQLException {
        String query = "SELECT item FROM (" +
            "SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||COALESCE(pg_get_expr(d.adbin,d.adrelid),'')||':'||a.attidentity::text||':'||a.attcollation::regcollation::text AS item FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum WHERE n.nspname=current_schema() AND c.relname IN ("+tables(version)+") AND a.attnum>0 AND NOT a.attisdropped " +
            "UNION ALL SELECT 'constraint:'||c.relname||':'||t.conname||':'||pg_get_constraintdef(t.oid) FROM pg_constraint t JOIN pg_class c ON c.oid=t.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relname LIKE 'vs_%' " +
            "UNION ALL SELECT 'index:'||indexname||':'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ("+tables(version)+") " +
            (version>=2?"UNION ALL SELECT 'sequence:'||c.relname||':'||format_type(s.seqtypid,NULL)||':'||s.seqstart||':'||s.seqincrement||':'||s.seqmax||':'||s.seqmin||':'||s.seqcache||':'||s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relname IN ('vs_admin_audit_audit_id_seq','vs_outbox_event_id_seq') ":"") +
            ") all_items ORDER BY item COLLATE \"C\"";
        StringBuilder values = new StringBuilder();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) { while(result.next()) values.append(result.getString(1)).append('\n'); }
        return sha256(values.toString());
    }

    private static String tables(int version){return "'vs_networks','vs_variables','vs_operations','vs_admin_audit','vs_schema_history'"+(version>=2?",'vs_outbox','vs_outbox_delivery','vs_subscriptions'":"");}
    private static String script(int version) {
        try (var stream = SchemaMigrator.class.getResourceAsStream(version==1?"/db/V001__initial.sql":"/db/V002__outbox.sql")) {
            if (stream == null) throw new IllegalStateException("Migration resource missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("Cannot read migration",error); }
    }
    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
