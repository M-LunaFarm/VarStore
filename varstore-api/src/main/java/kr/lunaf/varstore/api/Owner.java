package kr.lunaf.varstore.api;

import java.util.UUID;

/** An explicit logical owner; PLAYER identifiers must use canonical UUID strings. */
public record Owner(String type, String id) {
    public Owner {
        Names.ownerType(type);
        Names.ownerId(id);
        if (type.equals("PLAYER")) {
            try {
                if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "PLAYER owner requires a canonical UUID");
            }
        }
    }
    public static Owner player(UUID id) {
        if (id == null) throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Missing player UUID");
        return new Owner("PLAYER", id.toString());
    }
    public static Owner system(String id) { return new Owner("SYSTEM", id); }
}
