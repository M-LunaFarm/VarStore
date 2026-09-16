package kr.lunaf.varstore.paper;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.plugin.*;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperSessionsTest {
    private static final Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
    private static final UUID playerId = UUID.randomUUID();
    private static Thread main;
    private static Player player;
    private static Plugin owner;
    private static final AtomicBoolean enabled = new AtomicBoolean(true);
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, java.util.function.BiFunction<Method,Object[],Object> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            return calls.apply(method,args);
        });
    }
    @BeforeAll static void installServer() throws Exception {
        main = Thread.currentThread();
        player = proxy(Player.class, (method,args) -> switch(method.getName()) {
            case "getUniqueId" -> playerId; case "isOnline" -> true; default -> null;
        });
        PluginManager manager = proxy(PluginManager.class, (method,args) -> null);
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method,args) -> {
            if (!method.getName().equals("runTask")) return null;
            AtomicBoolean cancelled = new AtomicBoolean();
            Runnable action = (Runnable) args[1]; scheduled.add(() -> { if (!cancelled.get()) action.run(); });
            return proxy(BukkitTask.class, (task,unused) -> switch(task.getName()) {
                case "cancel" -> { cancelled.set(true); yield null; }
                case "isCancelled" -> cancelled.get(); default -> null;
            });
        });
        Server server = proxy(Server.class, (method,args) -> switch(method.getName()) {
            case "isPrimaryThread" -> Thread.currentThread() == main;
            case "getPluginManager" -> manager; case "getScheduler" -> scheduler;
            case "getOnlinePlayers" -> List.of(player); case "getPlayer" -> player;
            case "getLogger" -> java.util.logging.Logger.getLogger("PaperSessionsTest");
            case "getName", "getVersion", "getBukkitVersion" -> "test"; default -> null;
        });
        // Isolated scheduler fixture; Bukkit.setServer also requires the actual Paper build-info service.
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, server);
        owner = proxy(Plugin.class, (method,args) -> switch(method.getName()) {
            case "getServer" -> server; case "isEnabled" -> enabled.get();
            case "getLogger" -> java.util.logging.Logger.getLogger("PaperSessionsTest"); default -> null;
        });
    }
    @AfterAll static void resetServer() throws Exception {
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null,null);
    }
    @BeforeEach void reset() { enabled.set(true); scheduled.clear(); }
    private void tick() { Runnable job; while ((job = scheduled.poll()) != null) job.run(); }
    @Test void completionAfterReconnectCannotReachNewPlayerSession() {
        try (PaperSessions sessions = new PaperSessions(owner)) {
            var old = sessions.capture(playerId); CompletableFuture<String> read = new CompletableFuture<>(); AtomicInteger calls = new AtomicInteger();
            sessions.complete(old, read, (p,v) -> calls.incrementAndGet(), (p,e) -> calls.incrementAndGet());
            sessions.left(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
            sessions.joined(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
            assertNotEquals(old, sessions.capture(playerId)); read.complete("late"); tick(); assertEquals(0,calls.get());
        }
    }
    @Test void failureIsUnwrappedAndDeliveredOnlyOnMainThread() throws Exception {
        try (PaperSessions sessions = new PaperSessions(owner)) {
            var captured = sessions.capture(playerId); var read = new CompletableFuture<String>(); var received = new AtomicReference<Throwable>();
            sessions.complete(captured, read, (p,v) -> fail(), (p,e) -> { assertTrue(Bukkit.isPrimaryThread()); received.set(e); });
            IllegalStateException original = new IllegalStateException("failed");
            Thread worker = new Thread(() -> read.completeExceptionally(new CompletionException(original))); worker.start(); worker.join();
            assertNull(received.get()); tick(); assertSame(original,received.get());
        }
    }
    @Test void closeCancelsScheduledCompletionAndOwnedResources() {
        PaperSessions sessions = new PaperSessions(owner); AtomicInteger calls = new AtomicInteger(); AtomicBoolean closed = new AtomicBoolean();
        sessions.own(() -> closed.set(true));
        sessions.complete(sessions.capture(playerId), CompletableFuture.completedFuture(1), (p,v) -> calls.incrementAndGet(), (p,e) -> fail());
        sessions.close(); tick(); assertTrue(closed.get()); assertEquals(0,calls.get());
    }
}
