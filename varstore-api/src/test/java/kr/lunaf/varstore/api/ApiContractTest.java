package kr.lunaf.varstore.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {
    private final Owner owner = Owner.player(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"));
    private Address address(String key) { return new Address("production", "example", ScopeKind.NETWORK, "_", owner, key); }
    private Target<Long> target(String key) { return Target.of(address(key), VarKey.longKey(key)); }
    private void code(ErrorCode expected, Runnable action) { assertEquals(expected, assertThrows(VarStoreException.class, action::run).code()); }

    @Test void addressNamesAreCanonicalAndBounded() {
        new Address("a".repeat(64), "a", ScopeKind.SERVER, "server-1", owner, "a/" + "b".repeat(126));
        code(ErrorCode.INVALID_ARGUMENT, () -> new Address("a".repeat(65), "a", ScopeKind.NETWORK, "_", owner, "key"));
        for (String invalid : List.of("", "UPPER", "has space", "quote'", "a:b", "a\\b", "한글")) {
            code(ErrorCode.INVALID_ARGUMENT, () -> address(invalid));
        }
        code(ErrorCode.INVALID_ARGUMENT, () -> address("x".repeat(129)));
        code(ErrorCode.INVALID_ARGUMENT, () -> new Owner("PLAYER", "1-1-1-1-1"));
        assertTrue(address("aa").compareTo(address("z")) < 0, "Ordering must compare bytes, not encoded field lengths");
    }

    @Test void valuesEnforceBytesAndUnambiguousUnicode() {
        ValueType.STRING.validate("😀".repeat(4096));
        code(ErrorCode.VALUE_TOO_LARGE, () -> ValueType.STRING.validate("😀".repeat(4097)));
        code(ErrorCode.INVALID_ARGUMENT, () -> ValueType.STRING.validate("\uD800"));
        code(ErrorCode.INVALID_ARGUMENT, () -> ValueType.STRING.validate("\uDC00"));
        code(ErrorCode.INVALID_ARGUMENT, () -> ValueType.STRING.validate("a\u0000b"));
        code(ErrorCode.TYPE_MISMATCH, () -> ValueType.LONG.validate(1));
        code(ErrorCode.INVALID_ARGUMENT, () -> ValueType.LONG.validate(null));
        ValueType.STRING.validate(""); ValueType.BOOLEAN.validate(false); ValueType.LONG.validate(0L);
        ValueType.LONG.validate(Long.MIN_VALUE); ValueType.LONG.validate(Long.MAX_VALUE);
    }

    @Test void planIsImmutableAndBoundsAllTargetsIncludingConditions() {
        TransactionPlan.Builder builder = TransactionPlan.builder().set(target("one"), 1L);
        TransactionPlan first = builder.build();
        builder.set(target("two"), 2L);
        assertEquals(1, first.mutations().size());
        assertThrows(UnsupportedOperationException.class, () -> first.mutations().clear());
        code(ErrorCode.INVALID_ARGUMENT, () -> TransactionPlan.builder().set(target("a"), 1L).delete(target("a")).build());
        TransactionPlan.Builder many = TransactionPlan.builder().set(target("root"), 0L);
        for (int i=0; i<16; i++) many.requireAbsent(target("condition"+i));
        code(ErrorCode.INVALID_ARGUMENT, many::build);
        Target<Long> other = new Target<>(new Address("other", "example", ScopeKind.NETWORK, "_", owner, "key"), ValueType.LONG);
        code(ErrorCode.INVALID_ARGUMENT, () -> TransactionPlan.builder().set(target("one"), 1L).set(other, 2L).build());
    }

    @Test void planRejectsTypeAmbiguityAndOversizedAggregate() {
        Target<String> sameAddress = new Target<>(address("same"), ValueType.STRING);
        code(ErrorCode.TYPE_MISMATCH, () -> TransactionPlan.builder().requireAbsent(sameAddress).set(target("same"), 1L).build());
        TransactionPlan.Builder large = TransactionPlan.builder();
        for (int i=0; i<4; i++) large.set(new Target<String>(address("string"+i), ValueType.STRING), "x".repeat(16_384));
        code(ErrorCode.VALUE_TOO_LARGE, large::build);
        assertTrue(TransactionPlan.builder().set(target("small"), 0L).build().estimatedBytes() < 65_536);
    }

    @Test void batchDistinguishesAbsentUnrequestedAndWrongType() {
        VarKey<Long> key = VarKey.longKey("key");
        BatchRead absent = new BatchRead(Map.of(key, Optional.empty()));
        assertEquals(Optional.empty(), absent.get(key));
        code(ErrorCode.INVALID_ARGUMENT, () -> absent.get(VarKey.longKey("unrequested")));
        VersionToken version = new VersionToken(UUID.randomUUID(), UUID.randomUUID(), 1);
        BatchRead corrupt = new BatchRead(Map.of(key, Optional.of(new VersionedValue<>("wrong", version))));
        code(ErrorCode.TYPE_MISMATCH, () -> corrupt.get(key));
    }

    @Test void failureRetainsAssignedOperationIdAndReplayRetainsVersion() {
        UUID id = UUID.randomUUID();
        VarStoreException failure = new VarStoreException(ErrorCode.INVALID_ARGUMENT, "test").withOperationId(id);
        assertEquals(id, failure.operationId());
        assertSame(failure, failure.withOperationId(UUID.randomUUID()));
        VersionToken token = new VersionToken(UUID.randomUUID(), UUID.randomUUID(), 7);
        WriteReceipt<Long> original = new WriteReceipt<>(id, Outcome.APPLIED, Optional.of(token), Optional.of(4L), false);
        assertEquals(token, original.asReplay().version().orElseThrow());
        assertEquals(4L, original.asReplay().value().orElseThrow());
        assertTrue(original.asReplay().replayed());
        assertThrows(IllegalArgumentException.class, () -> new OperationStatus(id, OperationStatus.State.COMPLETED, Optional.empty()));
    }
}
