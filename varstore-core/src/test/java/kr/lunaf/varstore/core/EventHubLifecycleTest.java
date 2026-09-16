package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.api.events.*;
import kr.lunaf.varstore.postgres.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real database lock ordering, not a timed sleep pretending to create a race. */
class EventHubLifecycleTest {
    @Test void closeDuringExpiredResetCannotReactivateSubscription() throws Exception {
        String jdbc = System.getenv("VARSTORE_TEST_JDBC_URL"); Assumptions.assumeTrue(jdbc != null);
        String user = System.getenv().getOrDefault("VARSTORE_TEST_DB_USER", "varstore");
        String password = System.getenv().getOrDefault("VARSTORE_TEST_DB_PASSWORD", "varstore-test");
        String network = "eventclose-" + UUID.randomUUID();
        try (Connection setup = DriverManager.getConnection(jdbc, user, password)) { SchemaMigrator.migrate(setup, network); }
        var settings = new PostgresSettings(jdbc, user, password, network, "lifecycle", "disable", true, 4,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
        try (VarStore store = StoreFactory.open(new StoreConfig(settings, 4, 64, 1_048_576, Duration.ofSeconds(6), Duration.ofSeconds(2), 1));
             Connection control = DriverManager.getConnection(jdbc, user, password);
             Connection observer = DriverManager.getConnection(jdbc, user, password)) {
            store.ready().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var spec = new SubscriptionSpec("closing", "test", SubscriptionMode.EPHEMERAL, Duration.ofSeconds(60), Duration.ofHours(1));
            EventSubscription sub = ((VarStoreExtensions) store).events().subscribe(spec,
                    event -> CompletableFuture.completedFuture(null), () -> {}).toCompletableFuture().get(5, TimeUnit.SECONDS);
            try {
                try (var statement = control.prepareStatement("UPDATE vs_subscriptions SET lease_until=clock_timestamp()-interval '1 second' WHERE network_id=? AND subscriber_id='closing'")) {
                    statement.setString(1, network); assertEquals(1, statement.executeUpdate());
                }
                control.setAutoCommit(false);
                int blocker;
                try (var statement = control.prepareStatement("SELECT pg_backend_pid() FROM vs_networks WHERE network_id=? FOR SHARE")) {
                    statement.setString(1, network); try (var row = statement.executeQuery()) { assertTrue(row.next()); blocker = row.getInt(1); }
                }
                CompletionStage<Void> reset = sub.reset();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); boolean waiting = false;
                while (System.nanoTime() < until) {
                    try (var statement = observer.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))")) {
                        statement.setInt(1, blocker); try (var row = statement.executeQuery()) { row.next(); waiting = row.getBoolean(1); }
                    }
                    if (waiting) break; Thread.sleep(5);
                }
                assertTrue(waiting, "Reset must be waiting on the known network lock before close");
                sub.close(); control.commit();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> reset.toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals(ErrorCode.SHUTTING_DOWN, assertInstanceOf(VarStoreException.class, failure.getCause()).code());
                // A live row after this sequence would continue fan-out after consumer shutdown.
                try (var statement = observer.prepareStatement("SELECT count(*) FROM vs_subscriptions WHERE network_id=? AND subscriber_id='closing' AND active AND lease_until>clock_timestamp()")) {
                    statement.setString(1, network); try (var row = statement.executeQuery()) { row.next(); assertEquals(0, row.getLong(1)); }
                }
            } finally { control.rollback(); sub.close(); }
        }
    }
}
