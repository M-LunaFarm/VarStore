package kr.lunaf.varstore.examples.quests;

import java.nio.charset.StandardCharsets;
import java.util.*;
import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.paper.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Network quest progress: authoritative primary load, then atomic per-event increments. */
public final class QuestsPlugin extends JavaPlugin implements Listener {
    private static final VarKey<Long> KILLS = VarKey.longKey("quests/monster-kills");
    private final Set<UUID> loaded = new HashSet<>();
    private final Set<UUID> unresolved = new HashSet<>();
    private final Map<UUID, PaperSessions.Session> active = new HashMap<>();
    private PaperSessions sessions;
    private TrackedWrites writes;
    private VarStore store;
    private VarStore.Namespace namespace;
    @Override public void onEnable() {
        store = Objects.requireNonNull(getServer().getServicesManager().load(VarStore.class));
        namespace = Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class)).register(this, "varstorequests");
        sessions = new PaperSessions(this);
        sessions.own(java.util.Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class)).define(this, "varstorequests", new KeyDefinition<>(KILLS, 0L, "Network monster kill quest", false, CachePolicy.DISPLAY_ONLY, 1))); writes = sessions.own(new TrackedWrites(16));
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("questprogress")).setExecutor(this);
        getServer().getOnlinePlayers().forEach(this::load);
    }
    @EventHandler public void join(PlayerJoinEvent event) { load(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); loaded.remove(id); unresolved.remove(id);
        PaperSessions.Session session = active.remove(id); if (session != null) writes.forget(session);
    }
    private void load(Player player) {
        UUID id = player.getUniqueId();
        if (unresolved.contains(id)) { player.sendMessage("Quest write requires operation reconciliation; a display reload cannot resolve it."); return; }
        loaded.remove(id);
        PaperSessions.Session session = sessions.capture(id); active.put(id, session);
        VarStore.Data data = namespace.network().player(id);
        sessions.complete(session, store.ready().thenCompose(ignored -> data.get(KILLS)).thenCompose(value ->
                value.isPresent() ? java.util.concurrent.CompletableFuture.completedFuture(value.get())
                : data.setIfAbsent(KILLS, 0L, operation(id, "initialize")).thenCompose(ignored -> data.get(KILLS)).thenApply(Optional::orElseThrow)),
            (current, count) -> { loaded.add(id); current.sendMessage("Quest ready: monster kills=" + count); },
            (current, error) -> current.sendMessage("Quest unavailable; progress is paused. /questprogress retry"));
    }
    @EventHandler(ignoreCancelled = true) public void killed(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !(event.getEntity() instanceof org.bukkit.entity.Monster)) return;
        UUID id = killer.getUniqueId();
        if (!loaded.contains(id)) { killer.sendMessage("Quest progress is not ready."); return; }
        PaperSessions.Session session = sessions.capture(id);
        UUID operation = operation(id, "kill/" + event.getEntity().getUniqueId());
        sessions.complete(session, writes.submit(session, () -> namespace.network().player(id).increment(KILLS, 1, operation), WriteReceipt::outcome),
            (current, receipt) -> current.sendMessage("Quest progress committed: " + receipt.value().orElseThrow()),
            (current, error) -> { loaded.remove(id); unresolved.add(id); current.sendMessage("Quest write unresolved/failed; operation=" + operation + ". Progress paused."); });
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player required."); return true; }
        if (args.length == 1 && args[0].equals("retry")) { load(player); return true; }
        if (args.length != 0) return false;
        sessions.complete(sessions.capture(player.getUniqueId()), namespace.network().player(player.getUniqueId()).get(KILLS),
            (current, count) -> current.sendMessage("Quest monster kills: " + count.map(Object::toString).orElse("not initialized")),
            (current, error) -> current.sendMessage("Quest query unavailable."));
        return true;
    }
    private static UUID operation(UUID player, String event) {
        return UUID.nameUUIDFromBytes(("varstore-quests/v1/" + player + "/" + event).getBytes(StandardCharsets.UTF_8));
    }
    @Override public void onDisable() { if (sessions != null) sessions.close(); loaded.clear(); unresolved.clear(); active.clear(); }
}
