package kr.lunaf.varstore.api.events;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** At-least-once notifications without value payloads. Subscriber effects must be idempotent.
 * A callback may finish after its delivery lease expires and after redelivery begins.
 * Successful callback completion acknowledges only that delivery lease; no global order is promised. */
public interface EventService {
    /** Registration commits before completion. Begin snapshot/loading only after this stage completes. */
    CompletionStage<EventSubscription> subscribe(SubscriptionSpec spec,
            Function<ChangeEvent, CompletionStage<Void>> listener, Runnable onResync);
}
