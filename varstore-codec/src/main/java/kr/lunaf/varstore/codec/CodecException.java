package kr.lunaf.varstore.codec;

import java.util.Objects;

/** Explicit adapter failure; never converted into a missing stored value. */
public final class CodecException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final CodecError code;
    public CodecException(CodecError code, String message) { this(code, message, null); }
    public CodecException(CodecError code, String message, Throwable cause) {
        super(message, cause); this.code = Objects.requireNonNull(code);
    }
    public CodecError code() { return code; }
}
