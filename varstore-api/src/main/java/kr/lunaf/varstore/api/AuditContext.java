package kr.lunaf.varstore.api;

/** Administrator identity and action captured in the same commit as the operation. */
public record AuditContext(String actor, String action) {
    public AuditContext {
        if (actor == null || actor.isBlank() || Names.utf8Bytes(actor) > 256 || actor.chars().anyMatch(Character::isISOControl))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Invalid audit actor");
        if (action == null || !action.matches("[A-Z_]{1,32}"))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Invalid audit action");
    }
}
