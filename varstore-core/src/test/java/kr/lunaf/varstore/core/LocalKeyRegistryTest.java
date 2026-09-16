package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalKeyRegistryTest {
    private static KeyDefinition<Long> definition(String name, long fallback) {
        return new KeyDefinition<>(VarKey.longKey(name), fallback, "display", false, CachePolicy.DISPLAY_ONLY, 1);
    }

    @Test void identicalRegistrationsAreReferenceCountedAndDoubleCloseCannotReleaseAnotherConsumer() throws Exception {
        try (LocalKeyRegistry registry = new LocalKeyRegistry()) {
            var expected = definition("level", 1); AutoCloseable first = registry.register("rpg", expected);
            AutoCloseable second = registry.register("rpg", expected); first.close(); first.close();
            assertEquals(expected, registry.find("rpg", "level").orElseThrow());
            second.close(); assertEquals(Optional.empty(), registry.find("rpg", "level"));
            AutoCloseable replacement = registry.register("rpg", definition("level", 2));
            first.close(); assertEquals(2L, registry.find("rpg", "level").orElseThrow().defaultValue());
            replacement.close(); assertTrue(registry.list("rpg").isEmpty());
        }
    }

    @Test void sameNamespaceConflictIncludesMetadataAndDoesNotAffectOtherNamespaces() {
        try (LocalKeyRegistry registry = new LocalKeyRegistry()) {
            registry.register("rpg", definition("level", 1));
            assertEquals(ErrorCode.TYPE_MISMATCH, assertThrows(VarStoreException.class,
                    () -> registry.register("rpg", definition("level", 2))).code());
            KeyDefinition<String> string = new KeyDefinition<>(VarKey.stringKey("level"), "beginner", "label", false, CachePolicy.DISABLED, 1);
            assertEquals(ErrorCode.TYPE_MISMATCH, assertThrows(VarStoreException.class, () -> registry.register("rpg", string)).code());
            registry.register("other", string); assertEquals(string, registry.find("other", "level").orElseThrow());
            assertEquals(1L, registry.find("rpg", "level").orElseThrow().defaultValue());
        }
    }

    @Test void boundedRegistryReturnsImmutableOrderedSnapshotsAndCloseClearsDefinitions() throws Exception {
        LocalKeyRegistry registry = new LocalKeyRegistry(); List<AutoCloseable> registrations = new ArrayList<>();
        for (int i = 4095; i >= 0; i--) registrations.add(registry.register("rpg", definition(String.format(Locale.ROOT, "key/%04d", i), i)));
        List<KeyDefinition<?>> snapshot = registry.list("rpg"); assertEquals(4096, snapshot.size());
        assertEquals("key/0000", snapshot.getFirst().key().name());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(ErrorCode.OVERLOADED, assertThrows(VarStoreException.class, () -> registry.register("rpg", definition("extra", 0))).code());
        registrations.getFirst().close(); registry.register("rpg", definition("extra", 0));
        assertEquals(4096, snapshot.size()); registry.close(); assertTrue(registry.list("rpg").isEmpty());
        assertEquals(ErrorCode.SHUTTING_DOWN, assertThrows(VarStoreException.class, () -> registry.register("rpg", definition("later", 1))).code());
    }

    @Test void concurrentConflictingDefinitionsNeverBothRegister() throws Exception {
        try (LocalKeyRegistry registry = new LocalKeyRegistry(); ExecutorService threads = Executors.newFixedThreadPool(2)) {
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int value = i;
                attempts.add(threads.submit(() -> {
                    start.await();
                    try { registry.register("rpg", definition("level", value)); return true; }
                    catch (VarStoreException conflict) { assertEquals(ErrorCode.TYPE_MISMATCH, conflict.code()); return false; }
                }));
            }
            int accepted = 0; for (var attempt : attempts) if (attempt.get(2, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted); assertEquals(1, registry.list("rpg").size());
        }
    }
}
