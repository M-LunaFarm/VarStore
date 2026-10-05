package kr.lunaf.varstore.paper;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import kr.lunaf.varstore.api.*;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminCommandPermissionTest {
    private static final UUID OPERATION = UUID.fromString("8ef2eec4-7831-49cf-a850-3201c1a87dbe");

    @Test void diagnosticsCannotReadRetainedValuesOrReachStorage() {
        Fixture fixture = new Fixture(Set.of("varstore.diagnostics"));
        fixture.command("operation", "rewards", OPERATION.toString());
        assertEquals(List.of("Permission denied."), fixture.messages);
        assertEquals(0, fixture.lookups.get());
    }

    @Test void inspectCanReadRetainedOperationWithoutDiagnostics() {
        Fixture fixture = new Fixture(Set.of("varstore.inspect"));
        fixture.command("operation", "rewards", OPERATION.toString());
        assertEquals(1, fixture.lookups.get());
        assertEquals(1, fixture.messages.size());
        assertTrue(fixture.messages.getFirst().contains("private-retained-value"));
    }

    @Test void diagnosticsRemainsAvailableWithoutInspect() {
        Fixture fixture = new Fixture(Set.of("varstore.diagnostics"));
        fixture.command("diagnostics");
        assertTrue(fixture.messages.getFirst().startsWith("VarStore state=READY"));
        assertEquals(0, fixture.lookups.get());
    }

    @Test void permissionDenialPrecedesNamespaceAndIdParsing() {
        Fixture fixture = new Fixture(Set.of());
        fixture.command("operation", "invalid namespace", "invalid-uuid");
        assertEquals(List.of("Permission denied."), fixture.messages);
        assertEquals(0, fixture.lookups.get());
    }

    private static final class Fixture {
        final List<String> messages = new ArrayList<>();
        final AtomicInteger lookups = new AtomicInteger();
        final Player player;
        final AdminCommand command;

        Fixture(Set<String> permissions) {
            UUID playerId = UUID.randomUUID();
            player = proxy(Player.class, (name, args) -> switch (name) {
                case "getUniqueId" -> playerId;
                case "isOnline" -> true;
                case "hasPermission" -> permissions.contains(args[0]);
                case "sendMessage" -> { messages.add((String) args[0]); yield null; }
                default -> null;
            });
            PluginManager manager = proxy(PluginManager.class, (name, args) -> null);
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (name, args) -> {
                if (name.equals("runTask")) ((Runnable) args[1]).run();
                return null;
            });
            Server server = proxy(Server.class, (name, args) -> switch (name) {
                case "getPluginManager" -> manager;
                case "getScheduler" -> scheduler;
                case "getPlayer" -> player;
                default -> null;
            });
            Plugin plugin = proxy(Plugin.class, (name, args) -> switch (name) {
                case "getServer" -> server;
                case "isEnabled" -> true;
                default -> null;
            });
            Address address = new Address("production", "rewards", ScopeKind.NETWORK, "_", Owner.player(playerId), "secret");
            WriteReceipt<String> write = new WriteReceipt<>(OPERATION, Outcome.APPLIED, Optional.empty(),
                    Optional.of("private-retained-value"), false);
            OperationStatus status = new OperationStatus(OPERATION, OperationStatus.State.COMPLETED,
                    Optional.of(new TransactionReceipt(OPERATION, Outcome.APPLIED, Map.of(address, write), false)));
            VarStore.Namespace namespace = proxy(VarStore.Namespace.class, (name, args) -> {
                if (name.equals("operation")) {
                    assertEquals(OPERATION, args[0]);
                    return CompletableFuture.completedFuture(status);
                }
                throw new AssertionError("Unexpected namespace call: " + name);
            });
            VarStore store = proxy(VarStore.class, (name, args) -> switch (name) {
                case "namespace" -> {
                    lookups.incrementAndGet();
                    assertEquals("rewards", args[0]);
                    yield namespace;
                }
                case "state" -> StoreState.READY;
                case "metrics" -> StoreMetrics.empty();
                case "lastError" -> Optional.empty();
                default -> throw new AssertionError("Unexpected store call: " + name);
            });
            command = new AdminCommand(plugin, store);
        }

        void command(String... args) { assertTrue(command.onCommand(player, null, "varstore", args)); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            return calls.apply(method.getName(), args);
        });
    }
}
