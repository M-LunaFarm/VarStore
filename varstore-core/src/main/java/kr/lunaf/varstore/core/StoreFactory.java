package kr.lunaf.varstore.core;

import kr.lunaf.varstore.api.VarStore;

/** Creates a service immediately; database initialization runs off the caller thread. */
public final class StoreFactory {
    private StoreFactory() {}
    public static VarStore open(StoreConfig config) { return open(config, ignored -> {}); }
    /** Registers a STARTING service before database validation can begin. */
    public static VarStore open(StoreConfig config, java.util.function.Consumer<VarStore> beforeStart) {
        AsyncVarStore store = new AsyncVarStore(config);
        try { beforeStart.accept(store); store.start(); return store; }
        catch (RuntimeException error) { store.close(); throw error; }
    }
}
