package kr.lunaf.varstore.cache;

import kr.lunaf.varstore.api.ErrorCode;
import kr.lunaf.varstore.api.VersionToken;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Display-only observation. STALE/UNAVAILABLE may carry an explicitly old value.
 * Neither MISS nor an error is logical absence. No default value is substituted. */
public record CachedValue<T>(CacheState state, Optional<T> value, Optional<VersionToken> version,
                             Duration age, Optional<ErrorCode> error) {
    public CachedValue {
        Objects.requireNonNull(state); Objects.requireNonNull(value); Objects.requireNonNull(version);
        Objects.requireNonNull(age); Objects.requireNonNull(error);
        if (age.isNegative()) throw new IllegalArgumentException("Negative cache age");
    }
}
