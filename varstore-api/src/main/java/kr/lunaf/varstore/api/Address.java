package kr.lunaf.varstore.api;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Six-part durable address, ordered by canonical UTF-8 bytes for consistent locks. */
public record Address(String networkId, String namespace, ScopeKind scopeKind,
                      String scopeId, Owner owner, String key) implements Comparable<Address> {
    public Address {
        Names.identifier(networkId, "network ID");
        Names.identifier(namespace, "namespace");
        Objects.requireNonNull(scopeKind, "scopeKind");
        Names.identifier(scopeId, "scope ID");
        if (scopeKind == ScopeKind.NETWORK && !scopeId.equals("_"))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "NETWORK scope ID must be _");
        Objects.requireNonNull(owner, "owner");
        Names.key(key);
    }
    public String[] fields() { return new String[]{networkId, namespace, scopeKind.name(), scopeId, owner.type(), owner.id(), key}; }
    @Override public int compareTo(Address other) {
        String[] left = fields(), right = other.fields();
        for (int i = 0; i < left.length; i++) {
            int result = Arrays.compareUnsigned(left[i].getBytes(StandardCharsets.UTF_8), right[i].getBytes(StandardCharsets.UTF_8));
            if (result != 0) return result;
        }
        return 0;
    }
}
