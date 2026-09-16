package kr.lunaf.varstore.codec;

/** Explicit user-authored conversion. Implementations must be thread-safe.
 * encode must copy mutable inputs into immutable JsonValue data. decode must return
 * a fresh result or an immutable value; modifying it never persists a write. */
public interface Codec<T> {
    String id();
    int schemaVersion();
    JsonValue encode(T value);
    T decode(int storedSchemaVersion, JsonValue payload);
    /** Opt in to reading older versions; writing always uses schemaVersion(). */
    default boolean supportsVersion(int version) { return version == schemaVersion(); }
}
