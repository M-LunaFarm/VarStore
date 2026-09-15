package kr.lunaf.varstore.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable conditions and changes in one network and namespace. A plan may touch
 * at most 16 addresses and carry at most 64 KiB, with one mutation per address.
 * Builder instances are mutable and confined to their caller; built plans are safe to reuse.
 */
public record TransactionPlan(List<Condition> conditions, List<Mutation> mutations,
                              Optional<AuditContext> audit) {
    public TransactionPlan(List<Condition> conditions, List<Mutation> mutations) {
        this(conditions, mutations, Optional.empty());
    }
    public TransactionPlan {
        if (mutations.size() > 16) throw invalid("A transaction may mutate at most 16 keys");
        // Even the smallest encoded condition exceeds 64 bytes; bound snapshots
        // before copying a caller-owned list that cannot possibly fit the byte cap.
        if (conditions.size() > 1024) throw new VarStoreException(ErrorCode.VALUE_TOO_LARGE, "Transaction exceeds 65536 bytes");
        conditions = List.copyOf(conditions);
        mutations = List.copyOf(mutations);
        Objects.requireNonNull(audit, "audit");
        if (mutations.isEmpty()) throw invalid("A plan requires at least one mutation");
        Map<Address, ValueType> targets = new HashMap<>();
        Set<Address> changed = new HashSet<>();
        long bytes = 32;
        for (Condition condition : conditions) {
            checkTarget(targets, condition.target());
            bytes += targetBytes(condition.target()) + 64;
        }
        for (Mutation mutation : mutations) {
            checkTarget(targets, mutation.target());
            if (!changed.add(mutation.target().address())) throw invalid("Only one mutation per address is allowed");
            bytes += targetBytes(mutation.target()) + 32;
            if (mutation.kind() == Mutation.Kind.SET) bytes += mutation.target().type().encodedBytes(mutation.value());
        }
        if (targets.size() > 16) throw invalid("A transaction may touch at most 16 keys");
        Address first = targets.keySet().iterator().next();
        for (Address address : targets.keySet()) {
            if (!address.networkId().equals(first.networkId()) || !address.namespace().equals(first.namespace()))
                throw invalid("All transaction targets must share network and namespace");
        }
        if (audit.isPresent()) bytes += Names.utf8Bytes(audit.get().actor()) + Names.utf8Bytes(audit.get().action()) + 16;
        if (bytes > 65_536) throw new VarStoreException(ErrorCode.VALUE_TOO_LARGE, "Transaction exceeds 65536 bytes");
    }
    private static void checkTarget(Map<Address, ValueType> targets, Target<?> target) {
        ValueType old = targets.putIfAbsent(target.address(), target.type());
        if (old != null && old != target.type()) throw new VarStoreException(ErrorCode.TYPE_MISMATCH, "Conflicting transaction target types");
    }
    private static long targetBytes(Target<?> target) {
        long size = 32;
        for (String field : target.address().fields()) size += Names.utf8Bytes(field) + 4L;
        return size;
    }
    private static VarStoreException invalid(String message) { return new VarStoreException(ErrorCode.INVALID_ARGUMENT, message); }
    /** Conservative encoded request size used for bounded queue admission. */
    public long estimatedBytes() {
        long bytes = 32;
        for (Condition condition : conditions) bytes += targetBytes(condition.target()) + 64;
        for (Mutation mutation : mutations) {
            bytes += targetBytes(mutation.target()) + 32;
            if (mutation.kind() == Mutation.Kind.SET) bytes += mutation.target().type().encodedBytes(mutation.value());
        }
        if (audit.isPresent()) bytes += Names.utf8Bytes(audit.get().actor()) + Names.utf8Bytes(audit.get().action()) + 16;
        return bytes;
    }
    public TransactionPlan withAudit(AuditContext context) { return new TransactionPlan(conditions, mutations, Optional.of(context)); }
    public static Builder builder() { return new Builder(); }

    /** Fluent declarative plan construction; no callbacks or open transactions. */
    public static final class Builder {
        private final List<Condition> conditions = new ArrayList<>();
        private final List<Mutation> mutations = new ArrayList<>();
        private Builder() {}
        public Builder requireExists(Target<?> target) { conditions.add(Condition.exists(target)); return this; }
        public Builder requireAbsent(Target<?> target) { conditions.add(Condition.absent(target)); return this; }
        public Builder requireVersion(Target<?> target, VersionToken version) { conditions.add(Condition.version(target, version)); return this; }
        public Builder requireLongRange(Target<Long> target, long min, long max) { conditions.add(Condition.longRange(target, min, max)); return this; }
        public <T> Builder set(Target<T> target, T value) { mutations.add(Mutation.set(target, value)); return this; }
        public Builder delete(Target<?> target) { mutations.add(Mutation.delete(target)); return this; }
        public Builder increment(Target<Long> target, long delta) { mutations.add(Mutation.increment(target, delta)); return this; }
        public TransactionPlan build() { return new TransactionPlan(conditions, mutations); }
    }
}
