package kr.lunaf.varstore.api;

import java.util.Objects;

/** Local JVM metadata. A declared default neither writes the DB nor masks read errors. */
public record KeyDefinition<T>(VarKey<T> key, T defaultValue, String description,
                               boolean sensitive, CachePolicy cachePolicy, int schemaVersion) {
    public KeyDefinition {
        Objects.requireNonNull(key); Objects.requireNonNull(description); Objects.requireNonNull(cachePolicy);
        key.type().validate(defaultValue);
        if (description.length() > 1024 || schemaVersion < 1 || description.indexOf('\0') >= 0)
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Invalid key metadata");
        if (sensitive && cachePolicy == CachePolicy.DISPLAY_ONLY)
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Sensitive keys cannot enable display caching");
    }
    /** Built-in value checks execute before storage admission; business conditions belong in a plan. */
    public void validate(T value) { key.type().validate(value); }
}
