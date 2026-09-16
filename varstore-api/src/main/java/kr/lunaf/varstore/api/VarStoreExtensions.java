package kr.lunaf.varstore.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import kr.lunaf.varstore.api.events.EventService;

/** Optional capabilities; existing VarStore implementations retain their original interface. */
public interface VarStoreExtensions {
    KeyRegistry definitions();
    CompletionStage<KeyPage> scanKeys(VarStore.Data data, String prefix, Optional<String> cursor, int limit);
    default CompletionStage<KeyPage> scanKeys(VarStore.Data data, String prefix) {
        return scanKeys(data, prefix, Optional.empty(), 50);
    }
    EventService events();
    PendingWrites pendingWrites();
    CompletionStage<Map<String,Long>> capacity();
    /** Delivered for confirmed local mutations and uncertain writes, without values. */
    AutoCloseable onInvalidation(Consumer<Address> listener);
    /** Invalidates display state after event gaps, connectivity changes, or epoch fencing. */
    AutoCloseable onResync(Runnable listener);
}
