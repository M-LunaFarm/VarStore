package kr.lunaf.varstore.skript;

import ch.njol.skript.Skript;
import kr.lunaf.varstore.api.*;
import kr.lunaf.varstore.paper.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional addon pinned to Skript 2.16.1. No ordinary Skript variables are intercepted. */
public final class VarStoreSkriptPlugin extends JavaPlugin {
    static VarStoreSkriptPlugin instance;
    volatile boolean stopping;
    VarStore store;
    VarStoreExtensions extensions;
    PaperVarStore registrations;
    PaperSessions sessions;
    @Override public void onEnable() {
        if (!getServer().getPluginManager().getPlugin("Skript").getDescription().getVersion().equals("2.16.1"))
            throw new IllegalStateException("VarStoreSkript requires tested Skript 2.16.1");
        instance = this;
        store = java.util.Objects.requireNonNull(getServer().getServicesManager().load(VarStore.class));
        extensions = (VarStoreExtensions) store;
        registrations = java.util.Objects.requireNonNull(getServer().getServicesManager().load(PaperVarStore.class));
        sessions = new PaperSessions(this);
        Skript.registerAddon(this);
        Skript.registerEffect(StorageEffect.class,
            "varstore read %string% key %string% in namespace %string% scope %string% owner %string% [guarded by %-player%] into %objects%",
            "varstore set %string% key %string% to %object% in namespace %string% scope %string% owner %string% operation %string% [guarded by %-player%] into %objects%",
            "varstore add %number% to key %string% in namespace %string% scope %string% owner %string% operation %string% [guarded by %-player%] into %objects%",
            "varstore delete %string% key %string% in namespace %string% scope %string% owner %string% operation %string% [guarded by %-player%] into %objects%",
            "varstore keys prefix %string% in namespace %string% scope %string% owner %string% cursor %string% limit %number% [guarded by %-player%] into %objects%");
    }
    @Override public void onDisable() { stopping = true; if (sessions != null) sessions.close(); instance = null; }
}
