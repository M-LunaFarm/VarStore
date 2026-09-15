package kr.lunaf.varstore.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Acknowledged only after commit; replay returns the original outcome and version. */
public record WriteReceipt<T>(UUID operationId, Outcome outcome, Optional<VersionToken> version,
                              Optional<T> value, boolean replayed) {
    public WriteReceipt {
        Objects.requireNonNull(operationId, "operationId"); Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(version, "version"); Objects.requireNonNull(value, "value");
    }
    public WriteReceipt<T> asReplay() { return new WriteReceipt<>(operationId, outcome, version, value, true); }
}
