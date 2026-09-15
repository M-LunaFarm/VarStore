package kr.lunaf.varstore.api;

/** Supported immutable wire values. Null is represented only by explicit deletion. */
public enum ValueType {
    STRING(String.class), LONG(Long.class), BOOLEAN(Boolean.class), UUID(java.util.UUID.class);
    private final Class<?> javaType;
    ValueType(Class<?> javaType) { this.javaType = javaType; }
    public Class<?> javaType() { return javaType; }
    public void validate(Object value) {
        if (value == null) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Null values cannot be stored");
        if (!javaType.isInstance(value)) throw new VarStoreException(ErrorCode.TYPE_MISMATCH, "Value does not match declared type");
        if (this == STRING) {
            String text = (String) value;
            // Every UTF-16 code unit requires at least one encoded byte overall.
            // Reject huge inputs before scanning or allocating an encoded copy.
            if (text.length() > 16_384) throw new VarStoreException(ErrorCode.VALUE_TOO_LARGE, "String exceeds 16384 UTF-8 bytes");
            if (text.indexOf(0) >= 0) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "NUL is not supported in PostgreSQL text");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                        throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "String contains malformed Unicode");
                } else if (Character.isLowSurrogate(c))
                    throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "String contains malformed Unicode");
            }
            if (Names.utf8Bytes(text) > 16_384) throw new VarStoreException(ErrorCode.VALUE_TOO_LARGE, "String exceeds 16384 UTF-8 bytes");
        }
    }
    public int encodedBytes(Object value) {
        validate(value);
        return switch (this) { case STRING -> Names.utf8Bytes((String) value); case LONG -> 8; case BOOLEAN -> 1; case UUID -> 16; };
    }
}
