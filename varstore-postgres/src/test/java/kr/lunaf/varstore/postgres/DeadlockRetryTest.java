package kr.lunaf.varstore.postgres;

import kr.lunaf.varstore.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** A real opposite-order external writer forces PostgreSQL's deadlock victim/retry path. */
@EnabledIfEnvironmentVariable(named="VARSTORE_TEST_JDBC_URL",matches=".+")
class DeadlockRetryTest {
    @Test void actualDeadlockRollsBackThenRetriesOriginalOperationExactlyOnce()throws Exception {
        String base=System.getenv("VARSTORE_TEST_JDBC_URL");String user=System.getenv().getOrDefault("VARSTORE_TEST_DB_USER","varstore");String password=System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD","varstore-test");
        String schema="deadlock_"+UUID.randomUUID().toString().replace("-","");String application="deadlock-"+UUID.randomUUID().toString().substring(0,8);
        String url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;UUID operation=UUID.randomUUID();
        try(var admin=DriverManager.getConnection(base,user,password)) {
            try(var statement=admin.createStatement()){statement.execute("CREATE SCHEMA "+schema);}
            try {
                admin.setSchema(schema);SchemaMigrator.migrate(admin,"deadlock");
                try(var statement=admin.createStatement();var settings=statement.executeQuery("SELECT setting::bigint FROM pg_settings WHERE name='deadlock_timeout'")) {
                    assertTrue(settings.next());assertTrue(settings.getLong(1)<=5000,"Test requires backend deadlock detection within five seconds");
                }
                var settings=new PostgresSettings(url,user,password,"deadlock",application,"disable",true,2,Duration.ofSeconds(1),Duration.ofSeconds(12),Duration.ofSeconds(15));
                try(var backend=new PostgresBackend(settings);var external=DriverManager.getConnection(url,user,password)) {
                    backend.initialize();Target<Long> first=target("a"),second=target("b");
                    // Inject the test-only counter after startup validation; production schemas reject user triggers.
                    // nextval is deliberately nontransactional: the victim's rolled-back reservation
                    // leaves an observable attempt count, without changing production backend code.
                    try(var statement=admin.createStatement()) {
                        statement.execute("CREATE SEQUENCE retry_attempts");
                        statement.execute("CREATE FUNCTION count_retry_attempt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_id='"+operation+"'::uuid THEN PERFORM nextval('retry_attempts'); END IF; RETURN NEW; END $$");
                        statement.execute("CREATE TRIGGER test_retry_attempt BEFORE INSERT ON vs_operations FOR EACH ROW EXECUTE FUNCTION count_retry_attempt()");
                    }
                    backend.write(first,WriteKind.SET,10L,0,null,UUID.randomUUID(),deadline());
                    backend.write(second,WriteKind.SET,20L,0,null,UUID.randomUUID(),deadline());
                    long deadlocksBefore=deadlocks(admin);
                    external.setAutoCommit(false);
                    try(var statement=external.createStatement()) {
                        statement.execute("SET LOCAL deadlock_timeout='30s'");statement.execute("SET LOCAL lock_timeout='20s'");statement.execute("SET LOCAL statement_timeout='25s'");
                        statement.executeQuery("SELECT revision FROM vs_variables WHERE variable_key='b' FOR UPDATE").close();
                    }
                    ExecutorService worker=Executors.newSingleThreadExecutor();
                    try {
                        var plan=TransactionPlan.builder().increment(first,1).increment(second,1).build();
                        Future<TransactionReceipt> result=worker.submit(()->backend.execute(plan,operation,deadline()));
                        long observeUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
                        while(System.nanoTime()<observeUntil) {
                            try(var statement=admin.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')")) {
                                statement.setString(1,"VarStore/"+application);try(var rows=statement.executeQuery()){rows.next();waiting=rows.getBoolean(1);}
                            }
                            if(waiting)break;if(result.isDone())result.get();Thread.sleep(10);
                        }
                        assertTrue(waiting,"Backend must hold a and be waiting for externally locked b");
                        // The backend's earlier, shorter deadlock timer fires first, making it the
                        // victim. This statement proceeds only after backend rollback releases a.
                        try(var statement=external.createStatement()){statement.executeQuery("SELECT revision FROM vs_variables WHERE variable_key='a' FOR UPDATE").close();}
                        external.commit();
                        TransactionReceipt receipt=result.get(30,TimeUnit.SECONDS);assertEquals(Outcome.APPLIED,receipt.outcome());
                        assertEquals(11L,backend.get(first,deadline()).orElseThrow().value());assertEquals(21L,backend.get(second,deadline()).orElseThrow().value());
                        assertEquals(OperationStatus.State.COMPLETED,backend.operation("contract",operation,deadline()).state());
                        try(var statement=admin.createStatement();var attempts=statement.executeQuery("SELECT last_value FROM retry_attempts")){assertTrue(attempts.next());assertEquals(2,attempts.getLong(1),"One failed reservation plus one backend retry, with the same operation ID");}
                        try(var statement=admin.prepareStatement("SELECT count(*) FROM vs_operations WHERE operation_id=?")){statement.setObject(1,operation);try(var rows=statement.executeQuery()){rows.next();assertEquals(1,rows.getInt(1));}}
                        long statsUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);long observed=deadlocks(admin);
                        while(observed<=deadlocksBefore&&System.nanoTime()<statsUntil){Thread.sleep(50);observed=deadlocks(admin);}
                        assertTrue(observed>deadlocksBefore,"PostgreSQL must report an actual deadlock, not only a lock timeout");
                    }finally {
                        external.rollback();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
                    }
                }
            }finally {try(var statement=admin.createStatement()){statement.execute("DROP SCHEMA "+schema+" CASCADE");}}
        }
    }
    private static Target<Long> target(String key){return new Target<>(new Address("deadlock","contract",ScopeKind.NETWORK,"_",Owner.system("global"),key),ValueType.LONG);}
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(40);}
    private static long deadlocks(Connection connection)throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT deadlocks FROM pg_stat_database WHERE datname=current_database()")){assertTrue(result.next());return result.getLong(1);}
    }
}
