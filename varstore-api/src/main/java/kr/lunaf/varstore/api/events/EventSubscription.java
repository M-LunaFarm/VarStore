package kr.lunaf.varstore.api.events;

import java.util.concurrent.CompletionStage;

/** A bounded live delivery handle. Closing a durable subscription preserves its DB delivery rows. */
public interface EventSubscription extends AutoCloseable {
    SubscriptionState state();
    /** Discards this subscriber's pending/dead deliveries before snapshot reload.
     * Clear derived local state first, await reset, then load from Primary. */
    CompletionStage<Void> reset();
    CompletionStage<Integer> retryDeadLetters(int limit);
    @Override void close();
}
