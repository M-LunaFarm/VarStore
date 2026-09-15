package kr.lunaf.varstore.api;

import java.util.Objects;
import java.util.UUID;

/** Storage failure carrying the write's operation ID, when one exists. */
public final class VarStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode code;
    private final UUID operationId;

    public VarStoreException(ErrorCode code, String message) { this(code, message, null, null); }
    public VarStoreException(ErrorCode code, String message, UUID operationId) { this(code, message, operationId, null); }
    public VarStoreException(ErrorCode code, String message, UUID operationId, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.operationId = operationId;
    }
    public ErrorCode code() { return code; }
    /** Null for validation or read failures without an associated write. */
    public UUID operationId() { return operationId; }
    public VarStoreException withOperationId(UUID id) {
        return operationId != null ? this : new VarStoreException(code, getMessage(), id, this);
    }
}
