package kr.lunaf.varstore.examples.preferences;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.paper.PaperVarStore;
import kr.lunaf.varstore.paper.PaperSessions;
import kr.lunaf.varstore.paper.TrackedWrites;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;

/** Chat preference saved in PostgreSQL; the local mirror only controls presentation. */
public final class PreferencesPlugin extends JavaPlugin implements Listener {
    private static final VarKey<Boolean> CHAT = VarKey.booleanKey("chat-visible");
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PaperSessions.Session> pendingConnections = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> visible = new ConcurrentHashMap<>();
    private PaperSessions connections;
    private TrackedWrites writes;
    private VarStore store;
    private VarStore.Namespace namespace;

    @Override public void onEnable() {
        store = getServer().getServicesManager().load(VarStore.class);
        PaperVarStore registrations = getServer().getServicesManager().load(PaperVarStore.class);
        if (store == null || registrations == null) throw new IllegalStateException("VarStore service missing");
        namespace = registrations.register(this, "varstorepreferences");
        connections = new PaperSessions(this);
        writes = connections.own(new TrackedWrites(16));
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        connections.own(java.util.Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class)).define(this, "varstorepreferences", new KeyDefinition<>(CHAT, true, "Show network chat", false, CachePolicy.DISPLAY_ONLY, 1)));
        getServer().getPluginManager().registerEvents(this, this);
        java.util.Objects.requireNonNull(getCommand("preferences")).setExecutor(this);
        for (Player player : getServer().getOnlinePlayers()) load(player.getUniqueId());
    }
    @EventHandler public void joined(PlayerJoinEvent event) { load(event.getPlayer().getUniqueId()); }
    @EventHandler public void left(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); writes.forget(connections.capture(id)); sessions.remove(id); visible.remove(id); pendingConnections.entrySet().removeIf(e -> e.getValue().playerId().equals(id));
    }
    @EventHandler public void chat(AsyncChatEvent event) {
        event.viewers().removeIf(viewer -> viewer instanceof Player player && !visible.getOrDefault(player.getUniqueId(), false));
    }
    private void load(UUID id) {
        UUID session = UUID.randomUUID(); sessions.put(id, session); pendingConnections.put(session, connections.capture(id));
        store.ready().thenCompose(unused -> namespace.network().player(id).getOrDefault(CHAT, true))
                .whenComplete((value, error) -> online(id, session, player -> {
                    if (error != null) { player.sendMessage("Chat preference unavailable. Use /preferences to retry."); return; }
                    visible.put(id, value);
                    player.sendMessage("Network chat is " + (value ? "visible" : "hidden") + ". /preferences toggle");
                }));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command requires a player."); return true; }
        if (args.length == 2 && args[0].equals("transfer") && args[1].matches("[a-zA-Z0-9_-]{1,64}")) {
            PaperSessions.Session session = connections.capture(player.getUniqueId());
            String destination = args[1];
            connections.complete(session, writes.freeze(session), (current, drain) -> {
                if (drain.conditionFailed() != 0) { current.sendMessage("Transfer paused: a preference conflict needs review."); return; }
                try {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    java.io.DataOutputStream message = new java.io.DataOutputStream(bytes);
                    message.writeUTF("Connect"); message.writeUTF(destination);
                    current.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
                } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
            }, (current, error) -> current.sendMessage("Transfer paused: a tracked write is unresolved or failed."));
            return true;
        }
        if (args.length > 1 || (args.length == 1 && !java.util.Set.of("on", "off", "toggle").contains(args[0]))) return false;
        UUID id = player.getUniqueId();
        UUID session = UUID.randomUUID();
        sessions.put(id, session); pendingConnections.put(session, connections.capture(id));
        VarStore.Data data = namespace.network().player(id);
        if (args.length == 0) {
            data.getOrDefault(CHAT, true).whenComplete((value, error) -> online(id, session, current -> {
                if (error != null) { current.sendMessage("Storage unavailable; preference was not loaded."); return; }
                visible.put(id, value); current.sendMessage("Network chat is " + (value ? "visible" : "hidden"));
            }));
            return true;
        }
        String choice = args[0];
        UUID operation = UUID.randomUUID();
        writes.submit(connections.capture(id), () -> data.getVersioned(CHAT).thenCompose(before -> {
            boolean value = choice.equals("toggle") ? !before.map(VersionedValue::value).orElse(true) : choice.equals("on");
            return before.isPresent() ? data.compareAndSet(CHAT, before.get().version(), value, operation)
                    : data.setIfAbsent(CHAT, value, operation);
        }), WriteReceipt::outcome).whenComplete((receipt, error) -> online(id, session, current -> {
            if (error != null) {
                current.sendMessage("Preference update unresolved/failed; operation=" + operation + ". Check its result before retrying.");
                return;
            }
            if (receipt.outcome() == Outcome.CONDITION_FAILED) {
                current.sendMessage("Preference changed on another server; run /preferences then retry."); return;
            }
            receipt.value().ifPresent(value -> visible.put(id, value));
            current.sendMessage("Preference committed: " + receipt.value().orElseThrow());
        }));
        return true;
    }
    private void online(UUID id, UUID request, java.util.function.Consumer<Player> action) {
        // Request tokens also prevent an older load from replacing a newer command's mirror.
        PaperSessions.Session connection = pendingConnections.remove(request);
        if (connection == null) return;
        connections.run(connection, current -> {
            if (request.equals(sessions.get(id))) action.accept(current);
        });
    }
    @Override public void onDisable() { sessions.clear(); pendingConnections.clear(); visible.clear(); if (connections != null) connections.close(); getServer().getMessenger().unregisterOutgoingPluginChannel(this); }
}
