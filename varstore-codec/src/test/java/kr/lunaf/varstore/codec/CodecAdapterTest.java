package kr.lunaf.varstore.codec;

import kr.lunaf.varstore.api.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CodecAdapterTest {
    private static final Codec<String> STRING = new Codec<>() {
        public String id() { return "test.text"; }
        public int schemaVersion() { return 1; }
        public JsonValue encode(String text) { return JsonValue.string(text); }
        public String decode(int version, JsonValue payload) { return ((JsonValue.StringValue) payload).value(); }
    };
    private static final CodecKey<String> KEY = CodecKey.of("config", STRING);

    @Test void roundTripsUnicodeEscapesAndEveryJsonDataKindWithoutObjectDeserialization() {
        Codec<JsonValue> tree = new Codec<>() {
            public String id() { return "tree"; } public int schemaVersion() { return 1; }
            public JsonValue encode(JsonValue value) { return value; }
            public JsonValue decode(int version, JsonValue payload) { return payload; }
        };
        var payload = JsonValue.object(Map.of("한글😀\n", JsonValue.array(List.of(JsonValue.string("\"\\\u0000"),
                JsonValue.number(-4), JsonValue.number(new java.math.BigDecimal("1.25e10")), JsonValue.bool(true), JsonValue.nil()))));
        try (CodecAdapter adapter = new CodecAdapter()) {
            var key = CodecKey.of("tree", tree); var encoded = adapter.prepare(key, payload);
            assertEquals(payload, await(adapter.decode(key, encoded.envelope())));
            assertEquals(encoded.envelope().getBytes(StandardCharsets.UTF_8).length, encoded.encodedBytes());
            assertFalse(encoded.envelope().contains("\u0000"));
        }
    }

    @Test void mutableInputIsSnapshottedBeforeAnyStorageSubmissionAndDecodedObjectsAreDetached() {
        Codec<List<String>> listCodec = new Codec<>() {
            public String id() { return "list"; } public int schemaVersion() { return 1; }
            public JsonValue encode(List<String> value) { return JsonValue.array(value.stream().map(s -> (JsonValue) JsonValue.string(s)).toList()); }
            public List<String> decode(int version, JsonValue payload) {
                return new ArrayList<>(((JsonValue.ArrayValue) payload).values().stream().map(x -> ((JsonValue.StringValue) x).value()).toList());
            }
        };
        try (CodecAdapter adapter = new CodecAdapter()) {
            var key = CodecKey.of("list", listCodec); List<String> input = new ArrayList<>(List.of("before"));
            EncodedValue<List<String>> snapshot = adapter.prepare(key, input); input.set(0, "after");
            assertEquals(List.of("before"), await(adapter.decode(key, snapshot.envelope())));
            List<String> decoded = await(adapter.decode(key, snapshot.envelope())); decoded.add("local-only");
            assertEquals(List.of("before"), await(adapter.decode(key, snapshot.envelope())));
            AtomicReference<String> stored = new AtomicReference<>();
            VarStore.Data data = data((method, args) -> {
                assertEquals("set", method); stored.set((String) args[1]);
                return CompletableFuture.completedFuture(new WriteReceipt<>((UUID) args[2], Outcome.APPLIED, Optional.empty(), Optional.of(args[1]), false));
            });
            UUID id = UUID.randomUUID(); WriteReceipt<String> receipt = await(adapter.data(data).set(key, snapshot, id));
            assertEquals(id, receipt.operationId()); assertEquals(snapshot.envelope(), stored.get());
        }
    }

    @Test void entireUtf8EnvelopeIncludingMetadataIsLimitedToSixteenKiB() {
        try (CodecAdapter adapter = new CodecAdapter()) {
            int overhead = adapter.prepare(KEY, "").encodedBytes();
            String fits = "a".repeat(16_384 - overhead);
            assertEquals(16_384, adapter.prepare(KEY, fits).encodedBytes());
            assertEquals(CodecError.VALUE_TOO_LARGE, assertThrows(CodecException.class, () -> adapter.prepare(KEY, fits + "x")).code());
            assertEquals(CodecError.VALUE_TOO_LARGE, assertThrows(CodecException.class, () -> adapter.prepare(KEY, "한".repeat(6000))).code());
            assertError(CodecError.VALUE_TOO_LARGE, adapter.decode(KEY, " ".repeat(16_385)));
        }
    }

    @Test void codecIdentityAndUnsupportedVersionsFailExplicitlyAndLegacySupportIsOptIn() {
        try (CodecAdapter adapter = new CodecAdapter()) {
            assertError(CodecError.CODEC_MISMATCH, adapter.decode(KEY, "{\"codec\":\"another\",\"schema\":1,\"payload\":\"x\"}"));
            assertError(CodecError.UNSUPPORTED_VERSION, adapter.decode(KEY, "{\"codec\":\"test.text\",\"schema\":2,\"payload\":\"x\"}"));
            Codec<String> upgraded = new Codec<>() {
                public String id() { return "test.text"; } public int schemaVersion() { return 2; }
                public boolean supportsVersion(int v) { return v == 1 || v == 2; }
                public JsonValue encode(String value) { return JsonValue.string(value); }
                public String decode(int version, JsonValue value) { return version + ":" + ((JsonValue.StringValue) value).value(); }
            };
            var key2 = CodecKey.of("config", upgraded); String legacy = adapter.prepare(KEY, "old").envelope();
            assertEquals("1:old", await(adapter.decode(key2, legacy)));
            assertEquals(2, adapter.prepare(key2, "new").schemaVersion());
            AtomicInteger writes = new AtomicInteger(); ObjectData data = adapter.data(data((method, args) -> { writes.incrementAndGet(); return null; }));
            assertEquals(CodecError.UNSUPPORTED_VERSION, assertThrows(CodecException.class,
                    () -> data.set(key2, adapter.prepare(KEY, "old"), UUID.randomUUID())).code());
            assertEquals(0, writes.get());
        }
    }

    @Test void malformedEnvelopeDuplicateFieldsAndUnpairedUnicodeAreRejected() {
        try (CodecAdapter adapter = new CodecAdapter()) {
            for (String input : List.of("plain text", "{}", "[]", "{\"codec\":\"test.text\",\"schema\":1,\"payload\":1,\"extra\":2}",
                    "{\"codec\":\"test.text\",\"schema\":1,\"schema\":1,\"payload\":null}",
                    "{\"codec\":\"test.text\",\"schema\":1.1,\"payload\":null}",
                    "{\"codec\":\"test.text\",\"schema\":0,\"payload\":null}",
                    "{\"codec\":\"test.text\",\"schema\":01,\"payload\":null}",
                    "{\"codec\":\"test.text\",\"schema\":1,\"payload\":\"\\ud800\"}",
                    "{\"codec\":\"test.text\",\"schema\":1,\"payload\":\"\\uZZZZ\"}"))
                assertError(CodecError.INVALID_ENVELOPE, adapter.decode(KEY, input));
            assertEquals(CodecError.INVALID_ENVELOPE, assertThrows(CodecException.class, () -> adapter.prepare(KEY, "\ud800")).code());
        }
    }

    @Test void nestingAndNodeCountsAreBoundedEvenForSmallBytePayloads() {
        try (CodecAdapter adapter = new CodecAdapter()) {
            String deep = "{\"codec\":\"test.text\",\"schema\":1,\"payload\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}";
            assertError(CodecError.VALUE_TOO_LARGE, adapter.decode(KEY, deep));
            String many = "{\"codec\":\"test.text\",\"schema\":1,\"payload\":[" + "0,".repeat(4100) + "0]}";
            assertError(CodecError.VALUE_TOO_LARGE, adapter.decode(KEY, many));
            JsonValue nested = JsonValue.nil(); for (int i = 0; i < 40; i++) nested = JsonValue.array(List.of(nested));
            JsonValue finalNested = nested;
            assertEquals(CodecError.VALUE_TOO_LARGE, assertThrows(CodecException.class, () -> BoundedJson.write(finalNested)).code());
        }
    }

    @Test void decoderRunsOnBoundedWorkersAndRejectedCallsDoNotEnterAnUnboundedQueue() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        Codec<String> blocked = new Codec<>() {
            public String id() { return "blocking"; } public int schemaVersion() { return 1; }
            public JsonValue encode(String value) { return JsonValue.string(value); }
            public String decode(int version, JsonValue payload) {
                thread.set(Thread.currentThread().getName()); started.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return ((JsonValue.StringValue) payload).value();
            }
        };
        try (CodecAdapter adapter = new CodecAdapter(1, 1)) {
            var key = CodecKey.of("blocked", blocked); String envelope = adapter.prepare(key, "x").envelope();
            var first = adapter.decode(key, envelope); assertTrue(started.await(1, TimeUnit.SECONDS));
            var second = adapter.decode(key, envelope);
            assertError(CodecError.OVERLOADED, adapter.decode(key, envelope));
            assertEquals(2, adapter.pendingDecodes()); assertTrue(thread.get().startsWith("varstore-codec-"));
            release.countDown(); assertEquals("x", await(first)); assertEquals("x", await(second));
        } finally { release.countDown(); }
    }

    @Test void malformedPayloadIsDecodeErrorAndPrimaryFailuresNeverBecomeAbsent() {
        try (CodecAdapter adapter = new CodecAdapter()) {
            assertError(CodecError.DECODE_FAILED, adapter.decode(KEY, "{\"codec\":\"test.text\",\"schema\":1,\"payload\":123}"));
            ObjectData missing = adapter.data(data((method, args) -> CompletableFuture.completedFuture(Optional.empty())));
            assertEquals(Optional.empty(), await(missing.get(KEY)));
            VarStoreException storage = new VarStoreException(ErrorCode.STORAGE_UNAVAILABLE, "offline");
            ObjectData failed = adapter.data(data((method, args) -> CompletableFuture.failedFuture(storage)));
            assertSame(storage, assertThrows(CompletionException.class, () -> failed.get(KEY).toCompletableFuture().join()).getCause());
            ObjectData malformed = adapter.data(data((method, args) -> CompletableFuture.completedFuture(Optional.of(
                    new VersionedValue<>("ordinary STRING", new VersionToken(UUID.randomUUID(), UUID.randomUUID(), 1))))));
            assertError(CodecError.INVALID_ENVELOPE, malformed.get(KEY));
        }
    }

    @Test void closeRejectsNewWorkAndImmutableJsonContainersDoNotRetainMutableCollections() {
        Map<String, JsonValue> map = new HashMap<>(); map.put("x", JsonValue.string("before"));
        var object = JsonValue.object(map); map.put("x", JsonValue.string("after"));
        assertEquals(JsonValue.string("before"), object.fields().get("x"));
        assertThrows(UnsupportedOperationException.class, () -> object.fields().clear());
        List<JsonValue> list = new ArrayList<>(List.of(object)); var array = JsonValue.array(list); list.clear(); assertEquals(1, array.values().size());
        CodecAdapter adapter = new CodecAdapter(); String encoded = adapter.prepare(KEY, "x").envelope(); adapter.close();
        assertError(CodecError.CLOSED, adapter.decode(KEY, encoded));
        assertEquals(CodecError.CLOSED, assertThrows(CodecException.class, () -> adapter.prepare(KEY, "x")).code());
    }

    @Test void equivalentObjectsProduceIdenticalWireSnapshotsRegardlessOfMapIterationOrder() {
        Codec<Map<String, JsonValue>> mapCodec = new Codec<>() {
            public String id() { return "canonical.map"; } public int schemaVersion() { return 1; }
            public JsonValue encode(Map<String, JsonValue> value) { return JsonValue.object(value); }
            public Map<String, JsonValue> decode(int version, JsonValue payload) { return ((JsonValue.ObjectValue) payload).fields(); }
        };
        try (CodecAdapter adapter = new CodecAdapter()) {
            var key = CodecKey.of("canonical", mapCodec);
            LinkedHashMap<String, JsonValue> forward = new LinkedHashMap<>(), reverse = new LinkedHashMap<>();
            forward.put("language", JsonValue.string("ko_kr")); forward.put("notifications", JsonValue.bool(true));
            reverse.put("notifications", JsonValue.bool(true)); reverse.put("language", JsonValue.string("ko_kr"));
            assertEquals(adapter.prepare(key, forward).envelope(), adapter.prepare(key, reverse).envelope());
            assertEquals(adapter.prepare(key, forward).envelope(), adapter.prepare(key, Map.copyOf(forward)).envelope());
        }
    }

    private static VarStore.Data data(java.util.function.BiFunction<String, Object[], Object> call) {
        return (VarStore.Data) Proxy.newProxyInstance(VarStore.Data.class.getClassLoader(), new Class<?>[]{VarStore.Data.class},
                (proxy, method, args) -> call.apply(method.getName(), args));
    }
    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private static void assertError(CodecError expected, CompletionStage<?> stage) {
        Throwable actual = assertThrows(CompletionException.class, () -> await(stage)).getCause();
        assertInstanceOf(CodecException.class, actual); assertEquals(expected, ((CodecException) actual).code());
    }
}
