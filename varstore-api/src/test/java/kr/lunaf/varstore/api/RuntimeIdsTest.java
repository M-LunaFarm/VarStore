package kr.lunaf.varstore.api;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeIdsTest {
    @Test void uninitializedCallerFailsWithoutAcquiringTheEntropyInitializationLock() throws Exception {
        try (URLClassLoader isolated = isolated()) {
            Class<?> ids = isolated.loadClass(RuntimeIds.class.getName());
            assertEquals(false, ids.getMethod("isInitialized").invoke(null));
            Field initialization = ids.getDeclaredField("INITIALIZATION"); initialization.setAccessible(true);
            Object lock = initialization.get(null);
            try (ExecutorService calls = Executors.newSingleThreadExecutor()) {
                synchronized (lock) {
                    Future<Object> attempt = calls.submit(() -> {
                        try { return ids.getMethod("random").invoke(null); }
                        catch (InvocationTargetException error) { return error.getCause().getClass().getMethod("code").invoke(error.getCause()).toString(); }
                    });
                    assertEquals("NOT_READY", attempt.get(1, TimeUnit.SECONDS), "random() must not wait for entropy initialization");
                }
            }
            ids.getMethod("initialize").invoke(null);
            assertEquals(true, ids.getMethod("isInitialized").invoke(null));
            UUID generated = (UUID) ids.getMethod("random").invoke(null);
            assertEquals(4, generated.version()); assertEquals(2, generated.variant());
        }
    }

    @Test void concurrentCallersGetDistinctV4IdsAndRepeatedInitializationDoesNotResetTheSecretOrCounter() throws Exception {
        RuntimeIds.initialize();
        Field generator = RuntimeIds.class.getDeclaredField("generator"); generator.setAccessible(true);
        Object original = generator.get(null);
        Set<UUID> seen = ConcurrentHashMap.newKeySet();
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 8; i++) work.add(workers.submit(() -> {
                RuntimeIds.initialize();
                for (int j = 0; j < 2000; j++) {
                    UUID id = RuntimeIds.random(); assertEquals(4, id.version()); assertEquals(2, id.variant());
                    assertTrue(seen.add(id), "Duplicate runtime UUID in concurrency sample");
                }
            }));
            for (Future<?> task : work) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(16000, seen.size()); assertSame(original, generator.get(null));
    }

    @Test void exhaustedCounterFailsClosedWithoutReseedingOrWrapping() throws Exception {
        try (URLClassLoader isolated = isolated()) {
            Class<?> ids = isolated.loadClass(RuntimeIds.class.getName()); ids.getMethod("initialize").invoke(null);
            Field current = ids.getDeclaredField("generator"); current.setAccessible(true); Object generator = current.get(null);
            Field counter = generator.getClass().getDeclaredField("counter"); counter.setAccessible(true); counter.setLong(generator, Long.MAX_VALUE);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> ids.getMethod("random").invoke(null));
            assertEquals("NOT_READY", failure.getCause().getClass().getMethod("code").invoke(failure.getCause()).toString());
            ids.getMethod("initialize").invoke(null);
            assertSame(generator, current.get(null)); assertEquals(Long.MAX_VALUE, counter.getLong(generator));
        }
    }

    @Test void warmedGameplayGenerationPerformsNoRecordedFileOrSocketIo() throws Exception {
        RuntimeIds.initialize();
        Path recordingFile = Files.createTempFile("varstore-runtime-ids-", ".jfr");
        try (Recording recording = new Recording()) {
            for (String event : List.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite"))
                recording.enable(event).withThreshold(Duration.ZERO).withStackTrace();
            recording.start();
            for (int i = 0; i < 10000; i++) RuntimeIds.random();
            recording.stop(); recording.dump(recordingFile);
            long ioInGeneration = RecordingFile.readAllEvents(recordingFile).stream().filter(event -> event.getStackTrace() != null
                    && event.getStackTrace().getFrames().stream().anyMatch(frame -> frame.getMethod().getType().getName().startsWith(RuntimeIds.class.getName()))).count();
            assertEquals(0, ioInGeneration, "Warmed ID generation must never reach entropy or other I/O");
        } finally { Files.deleteIfExists(recordingFile); }
    }

    private static URLClassLoader isolated() {
        return new URLClassLoader(new java.net.URL[]{RuntimeIds.class.getProtectionDomain().getCodeSource().getLocation()}, ClassLoader.getPlatformClassLoader());
    }
}
