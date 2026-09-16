package kr.lunaf.varstore.placeholder;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.cache.*;
import kr.lunaf.varstore.paper.*;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional allowlisted display expansion; calculation only calls peekCached, never submits a load. */
public final class VarStorePlaceholderPlugin extends JavaPlugin implements Listener {
    private record Mapping(String namespace, VarStore.Scope scope, KeyDefinition<?> definition, CacheHandle handle) { }
    private final Map<String, Mapping> mappings = new ConcurrentHashMap<>();
    private final Map<CacheState, String> labels = new EnumMap<>(CacheState.class);
    private final List<CacheHandle> handles = new ArrayList<>();
    private Duration maxAge;
    private KeyRegistry definitions;
    private Expansion expansion;
    private volatile boolean stopping;
    @Override public void onEnable() {
        saveDefaultConfig();
        long age = getConfig().getLong("max-age-seconds", 5), ticks = getConfig().getLong("refresh-ticks", 20);
        if (age < 1 || age > 300 || ticks < 1 || ticks > 6000) throw new IllegalArgumentException("Invalid display refresh limits");
        maxAge = Duration.ofSeconds(age);
        for (CacheState state : CacheState.values()) labels.put(state, getConfig().getString("states." + state.name().toLowerCase(Locale.ROOT), state.name()));
        expansion = new Expansion(); expansion.register();
        getServer().getPluginManager().registerEvents(this, this);
        VarStore store = Objects.requireNonNull(getServer().getServicesManager().load(VarStore.class));
        definitions = ((VarStoreExtensions) store).definitions();
        store.ready().whenComplete((ignored, error) -> {
            if (stopping || error != null) return;
            try { getServer().getScheduler().runTask(this, this::configure); }
            catch (org.bukkit.plugin.IllegalPluginAccessException disabled) { }
        });
        getServer().getScheduler().runTaskTimer(this, this::refreshOnline, ticks, ticks);
    }
    private void configure() {
        if (stopping) return;
        DisplayCache cache = getServer().getServicesManager().load(DisplayCache.class);
        if (cache == null) { getLogger().info("VarStore display cache disabled; placeholders report unavailable."); return; }
        VarStore store = Objects.requireNonNull(getServer().getServicesManager().load(VarStore.class));
        PaperVarStore registration = Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class));
        ConfigurationSection configured = getConfig().getConfigurationSection("mappings");
        if (configured == null) return;
        if (configured.getKeys(false).size() > 128) throw new IllegalArgumentException("At most 128 placeholder mappings");
        Map<String, CacheHandle> namespaces = new HashMap<>();
        for (String name : configured.getKeys(false)) {
            if (!name.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid placeholder alias");
            String ns = configured.getString(name + ".namespace"), key = configured.getString(name + ".key");
            VarStore.Namespace namespace = registration.register(this, ns);
            KeyDefinition<?> definition = ((VarStoreExtensions) store).definitions().find(ns, key).orElseThrow(() -> new IllegalArgumentException("Unregistered display key"));
            if (definition.sensitive() || definition.cachePolicy() != CachePolicy.DISPLAY_ONLY) throw new IllegalArgumentException("Key does not allow display caching");
            String scope = configured.getString(name + ".scope", "network");
            VarStore.Scope addressed;
            if (scope.equals("network")) addressed = namespace.network();
            else if (scope.startsWith("server:")) addressed = namespace.server(scope.substring(7));
            else throw new IllegalArgumentException("Scope must be network or server:<id>");
            CacheHandle handle = namespaces.computeIfAbsent(ns, unused -> {
                CacheHandle opened = cache.open(store, ns, new CacheLimits(getConfig().getInt("max-entries", 1000), getConfig().getLong("max-bytes", 2097152L)));
                handles.add(opened); return opened;
            });
            mappings.put(name, new Mapping(ns, addressed, definition, handle));
            handle.ready().whenComplete((ignored, error) -> { if (error != null && !stopping) getLogger().warning("Display subscription unavailable; no value fallback applied"); });
        }
        refreshOnline();
    }
    @EventHandler public void joined(PlayerJoinEvent event) { refresh(event.getPlayer().getUniqueId()); }
    private void refreshOnline() { if (!stopping) getServer().getOnlinePlayers().forEach(player -> refresh(player.getUniqueId())); }
    private void refresh(UUID player) {
        for (Mapping mapping : mappings.values()) load(mapping, player);
    }
    private <T> void loadTyped(Mapping mapping, UUID player, KeyDefinition<T> definition) {
        mapping.handle().getCached(mapping.scope().player(player), definition, maxAge)
                .exceptionally(error -> null); // UI state is UNAVAILABLE; failures are counted by the cache service.
    }
    private boolean allowed(Mapping mapping) {
        return definitions.find(mapping.namespace(), mapping.definition().key().name()).filter(mapping.definition()::equals).isPresent();
    }
    private void load(Mapping mapping, UUID player) { if (allowed(mapping)) loadTyped(mapping, player, mapping.definition()); }
    private <T> String peek(Mapping mapping, UUID player, KeyDefinition<T> definition) {
        if (!allowed(mapping)) return labels.get(CacheState.UNAVAILABLE);
        CachedValue<T> cached = mapping.handle().peekCached(mapping.scope().player(player), definition, maxAge);
        return cached.state() == CacheState.VALUE ? cached.value().map(Object::toString).orElse(labels.get(CacheState.UNAVAILABLE)) : labels.get(cached.state());
    }
    private final class Expansion extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "varstore"; }
        @Override public String getAuthor() { return "LeeSeungmin"; }
        @Override public String getVersion() { return VarStorePlaceholderPlugin.this.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }
        @Override public String onRequest(OfflinePlayer player, String params) {
            Mapping mapping = mappings.get(params);
            if (mapping == null) return getConfig().contains("mappings." + params) ? labels.get(CacheState.UNAVAILABLE) : null;
            if (player == null || stopping) return labels.get(CacheState.UNAVAILABLE);
            return peek(mapping, player.getUniqueId(), mapping.definition());
        }
    }
    @Override public void onDisable() {
        stopping = true; if (expansion != null) expansion.unregister();
        handles.forEach(CacheHandle::close); handles.clear(); mappings.clear();
        HandlerList.unregisterAll((Listener) this);
    }
}
