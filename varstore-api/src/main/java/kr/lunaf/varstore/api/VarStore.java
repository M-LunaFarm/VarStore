package kr.lunaf.varstore.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Asynchronous primary-database storage. All storage failures complete the returned
 * stage exceptionally; a missing value alone produces Optional.empty(). Writes
 * succeed only after confirmed commit. Submission order is not execution order:
 * compose returned stages when order matters. Cancelling a future cannot undo a
 * committed write; resolve uncertain writes with their original operation ID.
 *
 * <p>Completion callbacks are not guaranteed to run on the game thread. Return to
 * the platform scheduler before touching players or worlds. Callbacks attached
 * after completion may execute on the attaching thread. Do not block game threads
 * with Future.get() or join(). Handles retain their original storage epoch and must
 * be discarded when the store is closed or the database is restored.</p>
 */
public interface VarStore extends AutoCloseable {
    /** Stop admission and drain already accepted requests within the configured deadline. */
    @Override void close();
    Namespace namespace(String namespace);
    CompletionStage<Void> ready();
    StoreState state();
    StoreMetrics metrics();
    /** Most recently observed health or storage error, without parameters or credentials. */
    default Optional<ErrorCode> lastError() { return Optional.empty(); }
    /** Register a subsequent state-change listener; close the subscription to unregister. */
    AutoCloseable onStateChange(Consumer<StoreState> listener);

    /** Trusted logical namespace; it is not a sandbox against hostile plugins in one JVM. */
    interface Namespace {
        Scope network();
        Scope server(String serverId);
        CompletionStage<TransactionReceipt> execute(TransactionPlan plan, UUID operationId);
        CompletionStage<OperationStatus> operation(UUID operationId);
    }

    interface Scope {
        Data owner(Owner owner);
        default Data player(UUID playerId) { return owner(Owner.player(playerId)); }
        default Data system(String systemId) { return owner(Owner.system(systemId)); }
    }

    /** Addressed data handle; supports only bounded, explicit key access. */
    interface Data {
        Address address(VarKey<?> key);
        default <T> Target<T> target(VarKey<T> key) { return Target.of(address(key), key); }
        <T> CompletionStage<Optional<VersionedValue<T>>> getVersioned(VarKey<T> key);
        default <T> CompletionStage<Optional<T>> get(VarKey<T> key) {
            return getVersioned(key).thenApply(value -> value.map(VersionedValue::value));
        }
        /** The fallback is used only for logical absence, never for storage errors. */
        default <T> CompletionStage<T> getOrDefault(VarKey<T> key, T defaultValue) {
            return get(key).thenApply(value -> value.orElse(defaultValue));
        }
        CompletionStage<BatchRead> getAll(Collection<VarKey<?>> keys);
        <T> CompletionStage<WriteReceipt<T>> set(VarKey<T> key, T value, UUID operationId);
        /** Compatibility default for external providers; UUID.randomUUID may read OS
         * entropy. The supplied core overrides this with preinitialized RuntimeIds.
         * Game-thread providers should override it or accept an explicit operation ID. */
        default <T> CompletionStage<WriteReceipt<T>> set(VarKey<T> key, T value) { return set(key, value, UUID.randomUUID()); }
        CompletionStage<WriteReceipt<Void>> delete(VarKey<?> key, UUID operationId);
        /** External-provider compatibility default; see set(key, value) entropy caveat. */
        default CompletionStage<WriteReceipt<Void>> delete(VarKey<?> key) { return delete(key, UUID.randomUUID()); }
        <T> CompletionStage<WriteReceipt<T>> setIfAbsent(VarKey<T> key, T value, UUID operationId);
        /** Fails with MISSING_VALUE if there is no living LONG value. */
        CompletionStage<WriteReceipt<Long>> increment(VarKey<Long> key, long delta, UUID operationId);
        /** Only a living value with the complete matching token can be changed. */
        <T> CompletionStage<WriteReceipt<T>> compareAndSet(VarKey<T> key, VersionToken version, T value, UUID operationId);
    }
}
