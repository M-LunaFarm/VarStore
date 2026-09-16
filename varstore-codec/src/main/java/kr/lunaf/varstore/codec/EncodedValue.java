package kr.lunaf.varstore.codec;

/** Immutable, fully validated snapshot prepared before submitting a storage write.
 * Its envelope, including codec metadata, is at most 16,384 UTF-8 bytes. */
public final class EncodedValue<T> {
    private final String codecId;
    private final int schemaVersion;
    private final String envelope;
    private final int encodedBytes;
    EncodedValue(String codecId, int schemaVersion, String envelope, int encodedBytes) {
        this.codecId = codecId; this.schemaVersion = schemaVersion; this.envelope = envelope; this.encodedBytes = encodedBytes;
    }
    public String codecId() { return codecId; }
    public int schemaVersion() { return schemaVersion; }
    public String envelope() { return envelope; }
    public int encodedBytes() { return encodedBytes; }
}
