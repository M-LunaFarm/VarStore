package kr.lunaf.varstore.paper;

import kr.lunaf.varstore.api.VarStore;
import kr.lunaf.varstore.api.VarStoreExtensions;
import kr.lunaf.varstore.cache.DisplayCache;
import kr.lunaf.varstore.cache.CacheLimits;
import kr.lunaf.varstore.core.StoreFactory;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class VarStorePlugin extends JavaPlugin {
    private VarStore store;
    private DisplayCache displayCache;
    private AutoCloseable stateSubscription;
    private volatile boolean stopping;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            if (getConfig().getBoolean("cache.enabled", false)) {
                int entries = getConfig().getInt("cache.max-entries", 10000);
                long bytes = getConfig().getLong("cache.max-bytes", 16777216L);
                if (entries > 1000000 || bytes > 1073741824L) throw new IllegalArgumentException("Cache global budget too large");
                displayCache = new DisplayCache(new CacheLimits(entries, bytes));
                getServer().getServicesManager().register(DisplayCache.class, displayCache, this, ServicePriority.Normal);
            }
            StoreFactory.open(PaperConfiguration.read(getConfig()), service -> {
                this.store = service;
                getServer().getServicesManager().register(VarStoreExtensions.class, (VarStoreExtensions) store, this, ServicePriority.Normal);
                getServer().getServicesManager().register(VarStore.class, store, this, ServicePriority.Normal);
                getServer().getServicesManager().register(PaperVarStore.class,
                        new PaperVarStore(store, getServer().getPluginManager(), PaperConfiguration.shared(getConfig())),
                        this, ServicePriority.Normal);
                java.util.Objects.requireNonNull(getCommand("varstore")).setExecutor(new AdminCommand(this, store));
                stateSubscription = store.onStateChange(state -> {
                    if (stopping) return;
                    try { getServer().getScheduler().runTask(this, () -> {
                        if (!stopping) getServer().getPluginManager().callEvent(new VarStoreStateEvent(state));
                    }); } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Disable race. */ }
                });
            });
            store.ready().whenComplete((unused, error) -> {
                if (!stopping) getLogger().info(error == null ? "VarStore READY" : "VarStore not ready; check DB connectivity and schema with varstore-tools");
            });
        } catch (RuntimeException error) {
            // JDBC exception messages may contain credentials or URLs. Never log the raw exception.
            getLogger().severe("VarStore configuration/startup rejected (" + error.getClass().getSimpleName() + "). Check config and required environment variables.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        stopping = true;
        getServer().getServicesManager().unregisterAll(this);
        if (stateSubscription != null) try { stateSubscription.close(); } catch (Exception ignored) { }
        if (displayCache != null) displayCache.close();
        if (store != null) store.close();
    }
}
