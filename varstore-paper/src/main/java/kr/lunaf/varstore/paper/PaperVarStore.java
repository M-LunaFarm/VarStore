package kr.lunaf.varstore.paper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.lunaf.varstore.api.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Namespace registration for cooperating plugins; this is not a hostile-JVM security boundary. */
public final class PaperVarStore {
    private final VarStore store;
    private final PluginManager plugins;
    private final Map<String, Set<String>> shared;

    PaperVarStore(VarStore store, PluginManager plugins, Map<String, Set<String>> shared) {
        this.store = store;
        this.plugins = plugins;
        this.shared = shared.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /** Own metadata registrations with the consumer's lifecycle helper. */
    public AutoCloseable define(Plugin consumer, String namespace, KeyDefinition<?> definition) {
        register(consumer, namespace);
        return ((VarStoreExtensions) store).definitions().register(namespace, definition);
    }

    public VarStore.Namespace register(Plugin consumer, String namespace) {
        if (consumer == null || plugins.getPlugin(consumer.getName()) != consumer || !consumer.isEnabled())
            throw new IllegalArgumentException("Consumer must be an enabled registered plugin");
        if (!namespace.equals(consumer.getName().toLowerCase(Locale.ROOT))
                && !shared.getOrDefault(consumer.getName(), Set.of()).contains(namespace))
            throw new IllegalArgumentException("Namespace is not granted to this consumer");
        return store.namespace(namespace);
    }
}
