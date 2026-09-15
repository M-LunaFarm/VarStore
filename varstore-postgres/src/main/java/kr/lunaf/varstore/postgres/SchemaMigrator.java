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
    public static final int VERSION = 1;
    private static final long LOCK_ID = 0x56415253544f5245L;
    private SchemaMigrator() {}

    public static void migrate(Connection connection, String networkId) throws SQLException {
        if (!networkId.matches("[a-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid network ID");
        if (!connection.getAutoCommit()) throw new SQLException("Migration requires an idle auto-commit connection");
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_ID); statement.execute();
            }
            boolean exists;
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT to_regclass('vs_schema_history') IS NOT NULL")) { result.next(); exists = result.getBoolean(1); }
            if (!exists) {
                try (var statement = connection.createStatement()) { statement.execute(script()); }
                try (var statement = connection.prepareStatement("INSERT INTO vs_schema_history(version,checksum,catalog_checksum,tool_version) VALUES(?,?,?,?)")) {
                    statement.setInt(1, VERSION); statement.setString(2, sha256(script())); statement.setString(3, catalogChecksum(connection)); statement.setString(4, "1.0.0"); statement.executeUpdate();
                }
            } else validate(connection);
            try (var statement = connection.prepareStatement("INSERT INTO vs_networks(network_id,storage_epoch) VALUES(?,?) ON CONFLICT DO NOTHING")) {
                statement.setString(1,networkId); statement.setObject(2,UUID.randomUUID()); statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        finally { connection.setAutoCommit(true); }
    }

    public static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT version,checksum,catalog_checksum FROM vs_schema_history ORDER BY version")) {
            if (!result.next() || result.getInt(1) != VERSION || !sha256(script()).equals(result.getString(2)) || !catalogChecksum(connection).equals(result.getString(3)) || result.next())
                throw new SQLException("VarStore schema version, migration checksum, or catalog mismatch");
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

    private static String catalogChecksum(Connection connection) throws SQLException {
        String query = "SELECT item FROM (" +
            "SELECT 'column:'||c.relname||':'||a.attnum||':'||a.attname||':'||format_type(a.atttypid,a.atttypmod)||':'||a.attnotnull||':'||COALESCE(pg_get_expr(d.adbin,d.adrelid),'')||':'||a.attidentity::text||':'||a.attcollation::regcollation::text AS item FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum WHERE n.nspname=current_schema() AND c.relname IN ('vs_networks','vs_variables','vs_operations','vs_admin_audit','vs_schema_history') AND a.attnum>0 AND NOT a.attisdropped " +
            "UNION ALL SELECT 'constraint:'||c.relname||':'||t.conname||':'||pg_get_constraintdef(t.oid) FROM pg_constraint t JOIN pg_class c ON c.oid=t.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND c.relname LIKE 'vs_%' " +
            "UNION ALL SELECT 'index:'||indexname||':'||indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename IN ('vs_networks','vs_variables','vs_operations','vs_admin_audit','vs_schema_history')) all_items ORDER BY item COLLATE \"C\"";
        StringBuilder values = new StringBuilder();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) { while(result.next()) values.append(result.getString(1)).append('\n'); }
        return sha256(values.toString());
    }

    private static String script() {
        try (var stream = SchemaMigrator.class.getResourceAsStream("/db/V001__initial.sql")) {
            if (stream == null) throw new IllegalStateException("Migration resource missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("Cannot read migration",error); }
    }
    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
