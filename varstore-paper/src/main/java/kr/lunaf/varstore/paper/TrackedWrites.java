package kr.lunaf.varstore.paper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import kr.lunaf.varstore.api.Outcome;

/** Tracks only writes submitted through this consumer. A frozen session rejects new work. */
public final class TrackedWrites implements AutoCloseable {
    public record Drain(long applied, long noChange, long conditionFailed) { }
    private static final class State {
        boolean frozen;
        int pending;
        long applied, noChange, failed;
        Throwable error;
        CompletableFuture<Drain> drain;
    }
    private final Map<PaperSessions.Session, State> states = new HashMap<>();
    private final int maxPending;
    private boolean closed;
    public TrackedWrites(int maxPending) {
        if (maxPending < 1 || maxPending > 1024) throw new IllegalArgumentException("maxPending must be 1..1024");
        this.maxPending = maxPending;
    }
    public synchronized <T> CompletionStage<T> submit(PaperSessions.Session session,
            Supplier<CompletionStage<T>> action, Function<T, Outcome> outcome) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Consumer closed"));
        if (!states.containsKey(session) && states.size() >= 4096) return CompletableFuture.failedFuture(new IllegalStateException("Consumer session limit reached"));
        State state = states.computeIfAbsent(session, ignored -> new State());
        if (state.frozen || state.error != null || state.pending >= maxPending)
            return CompletableFuture.failedFuture(new IllegalStateException("Writes paused, unresolved, or at consumer limit"));
        state.pending++;
        CompletionStage<T> result;
        try { result = action.get(); } catch (Throwable error) { result = CompletableFuture.failedFuture(error); }
        return result.whenComplete((value, error) -> {
            synchronized (TrackedWrites.this) {
                state.pending--;
                if (error != null) state.error = PaperSessions.unwrap(error);
                else {
                    try {
                        switch (outcome.apply(value)) {
                            case APPLIED -> state.applied++;
                            case NO_CHANGE -> state.noChange++;
                            case CONDITION_FAILED -> state.failed++;
                        }
                    } catch (Throwable invalid) { state.error = invalid; }
                }
                finish(state);
            }
        });
    }
    /** Stops new writes and waits asynchronously for this consumer's submitted work only. */
    public synchronized CompletionStage<Drain> freeze(PaperSessions.Session session) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Consumer closed"));
        if (!states.containsKey(session) && states.size() >= 4096) return CompletableFuture.failedFuture(new IllegalStateException("Consumer session limit reached"));
        State state = states.computeIfAbsent(session, ignored -> new State());
        state.frozen = true;
        if (state.drain == null) state.drain = new CompletableFuture<>();
        finish(state); return state.drain;
    }
    private void finish(State state) {
        if (state.drain == null || state.pending != 0) return;
        if (state.error != null) state.drain.completeExceptionally(state.error);
        else state.drain.complete(new Drain(state.applied, state.noChange, state.failed));
    }
    /** Forget a disconnected session; submitted DB work still settles but cannot affect a new session. */
    public synchronized void forget(PaperSessions.Session session) { states.remove(session); }
    @Override public synchronized void close() {
        closed = true;
        states.values().forEach(s -> { if (s.drain != null) s.drain.completeExceptionally(new IllegalStateException("Consumer closed")); });
        states.clear();
    }
}
