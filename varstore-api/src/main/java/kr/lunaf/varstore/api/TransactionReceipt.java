package kr.lunaf.varstore.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashMap;

/** Committed result of one immutable plan; results are indexed by complete address. */
public record TransactionReceipt(UUID operationId, Outcome outcome,
                                 Map<Address, WriteReceipt<?>> results, boolean replayed) {
    public TransactionReceipt {
        Objects.requireNonNull(operationId, "operationId"); Objects.requireNonNull(outcome, "outcome");
        results = Map.copyOf(results);
    }
    public TransactionReceipt asReplay() {
        Map<Address, WriteReceipt<?>> copied = new LinkedHashMap<>();
        results.forEach((address, receipt) -> copied.put(address, receipt.asReplay()));
        return new TransactionReceipt(operationId, outcome, copied, true);
    }
}
