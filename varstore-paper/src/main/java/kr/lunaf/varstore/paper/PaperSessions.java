package kr.lunaf.varstore.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/** Consumer-owned connection generations and scheduler-safe completion delivery. */
public final class PaperSessions implements Listener, AutoCloseable {
    public record Session(UUID playerId, UUID generation) { }
    private final Plugin owner;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<org.bukkit.scheduler.BukkitTask> tasks = new HashSet<>();
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile boolean closed;

    public PaperSessions(Plugin owner) {
        requireMainThread();
        this.owner = Objects.requireNonNull(owner);
        owner.getServer().getPluginManager().registerEvents(this, owner);
        owner.getServer().getOnlinePlayers().forEach(p -> joined(p.getUniqueId()));
    }
    private void joined(UUID id) { sessions.put(id, new Session(id, UUID.randomUUID())); }
    @EventHandler(priority = EventPriority.LOWEST) public void joined(PlayerJoinEvent event) { joined(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public void left(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void disabled(PluginDisableEvent event) { if (event.getPlugin() == owner) close(); }

    /** Capture on the main thread. Does not retain a Player object. */
    public Session capture(UUID id) {
        requireMainThread();
        Session session = sessions.get(id);
        if (closed || session == null) throw new IllegalStateException("No active player session");
        return session;
    }
    public boolean isCurrent(Session session) {
        requireMainThread();
        return !closed && owner.isEnabled() && session.equals(sessions.get(session.playerId()));
    }
    /** Register a listener/cache handle for cleanup with this consumer. */
    public <T extends AutoCloseable> T own(T resource) {
        requireMainThread();
        if (closed) throw new IllegalStateException("Session helper closed");
        resources.add(Objects.requireNonNull(resource)); return resource;
    }
    public <T> void complete(Session session, CompletionStage<T> stage,
                             BiConsumer<Player, T> success, BiConsumer<Player, Throwable> failure) {
        Objects.requireNonNull(stage).whenComplete((value, error) -> run(session, player -> {
            if (error == null) success.accept(player, value);
            else failure.accept(player, unwrap(error));
        }));
    }
    /** Returns false only when scheduling is already impossible; late stale sessions are discarded. */
    public boolean run(Session session, Consumer<Player> action) {
        synchronized (tasks) {
            if (closed) return false;
            try {
                org.bukkit.scheduler.BukkitTask[] ticket = new org.bukkit.scheduler.BukkitTask[1];
                ticket[0] = owner.getServer().getScheduler().runTask(owner, () -> {
                    synchronized (tasks) { tasks.remove(ticket[0]); }
                    if (!isCurrent(session)) return;
                    Player current = owner.getServer().getPlayer(session.playerId());
                    if (current != null && current.isOnline()) action.accept(current);
                });
                tasks.add(ticket[0]);
                return true;
            } catch (org.bukkit.plugin.IllegalPluginAccessException disabled) { return false; }
        }
    }
    public static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error;
    }
    public static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Capture player sessions on the Paper main thread");
    }
    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true; sessions.clear(); HandlerList.unregisterAll(this);
        synchronized (tasks) { tasks.forEach(org.bukkit.scheduler.BukkitTask::cancel); tasks.clear(); }
        for (AutoCloseable resource : resources) {
            try { resource.close(); } catch (Exception error) { owner.getLogger().warning("Consumer resource cleanup failed: " + error.getClass().getSimpleName()); }
        }
        resources.clear();
    }
}
