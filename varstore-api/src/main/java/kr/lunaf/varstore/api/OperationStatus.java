package kr.lunaf.varstore.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** NOT_OBSERVED_YET does not prove an in-flight or disconnected writer cannot still commit. */
public record OperationStatus(UUID operationId, State state, Optional<TransactionReceipt> receipt) {
    public enum State { NOT_OBSERVED_YET, COMPLETED, IN_PROGRESS, RESULT_EXPIRED, UNKNOWN }
    public OperationStatus {
        Objects.requireNonNull(operationId, "operationId"); Objects.requireNonNull(state, "state");
        Objects.requireNonNull(receipt, "receipt");
        if ((state == State.COMPLETED) != receipt.isPresent())
            throw new IllegalArgumentException("Only COMPLETED status has a retained receipt");
    }
}
