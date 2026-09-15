package kr.lunaf.varstore.api;

import java.util.Objects;
import java.util.UUID;

/** Complete CAS token, including database recovery and physical row generations. */
public record VersionToken(UUID storageEpoch, UUID generation, long revision) {
    public VersionToken {
        Objects.requireNonNull(storageEpoch, "storageEpoch");
        Objects.requireNonNull(generation, "generation");
        if (revision < 0) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Negative revision");
    }
}
