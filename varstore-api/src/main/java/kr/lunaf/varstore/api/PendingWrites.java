package kr.lunaf.varstore.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Bounded same-ID reconciliation; callers retain a durable business ledger across process restarts. */
public interface PendingWrites {
    enum State { CONFIRMED, REQUIRES_CONFIRMATION, RESULT_EXPIRED }
    record Policy(int maxAttempts, Duration timeout, Duration retryDelay) {
        public Policy {
            if (maxAttempts < 1 || maxAttempts > 16 || timeout == null || retryDelay == null
                    || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0
                    || retryDelay.compareTo(Duration.ofMillis(10)) < 0 || retryDelay.compareTo(Duration.ofSeconds(5)) > 0)
                throw new IllegalArgumentException("Invalid reconciliation limits");
        }
        public static Policy defaults() { return new Policy(8, Duration.ofSeconds(15), Duration.ofMillis(100)); }
    }
    record Result(UUID operationId, State state, Optional<TransactionReceipt> receipt, int attempts,
                  Optional<ErrorCode> lastError) {
        public Result {
            java.util.Objects.requireNonNull(operationId); java.util.Objects.requireNonNull(state);
            java.util.Objects.requireNonNull(receipt); java.util.Objects.requireNonNull(lastError);
            if ((state == State.CONFIRMED) != receipt.isPresent() || attempts < 0)
                throw new IllegalArgumentException("Invalid reconciliation result");
        }
    }
    record Tracked(UUID operationId, String namespace, Instant startedAt, int attempts,
                   Optional<ErrorCode> lastError, boolean resolving) { }
    CompletionStage<Result> execute(String namespace, TransactionPlan immutablePlan, UUID operationId, Policy policy);
    List<Tracked> tracked();
    /** Remove only a finished in-memory reminder; does not cancel, replay, or erase the DB operation. */
    boolean forget(UUID operationId);
}
