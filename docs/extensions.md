# 확장 API

[README로 돌아가기](../README.md) · [개발과 수정 안내](development.md)

[기본 API](api.md)의 서비스와 owner 핸들을 사용합니다. 아래 예제의 `sessions`는 [PaperSessions](integrations.md#paper-세션과-선택적-애드온)이며, 키 정의·구독·캐시의 등록 수명을 관리합니다.

## 확장 API: 정의·목록·불명확 결과 복구

1.3.0의 선택 기능은 `VarStoreExtensions`에 있습니다. 기존 `VarStore` 구현체에 새 추상 메서드를 강제하지 않습니다. 아래 예제의 `store`는 `ready()`가 완료된 서비스이며, `data`는 해당 namespace의 owner 핸들입니다.

```java
if (!(store instanceof VarStoreExtensions extensions)) {
    throw new IllegalStateException("VarStore extensions unavailable");
}
var chat = new KeyDefinition<>(VarKey.booleanKey("preferences/chat-notify"),
    true, "채팅 알림 표시", false, CachePolicy.DISPLAY_ONLY, 1);
AutoCloseable registration = extensions.definitions().register("myrpg", chat);
// 소비 플러그인 종료 시 registration.close(). Paper에서는 sessions.own(registration).
data.getOrDefault(chat.key(), chat.defaultValue());

extensions.scanKeys(data, "quests/", Optional.empty(), 50)
    .thenAccept(page -> {
        // page.keys(): key/type/version만 포함. 다음 요청에는 page.nextCursor() 사용.
    });
```

키 정의는 같은 JVM의 namespace·key 충돌만 검사합니다. 동일 정의의 여러 등록은 참조 수를 관리하며 마지막 등록을 닫으면 해제합니다. 서버 간 정의 정책을 동기화하지 않습니다. 기본값 등록은 DB 쓰기가 아니고 읽기 실패의 대체값도 아닙니다. 민감한 정의에는 표시 캐시를 허용하지 않습니다. 레지스트리는 서버당 서로 다른 키 4,096개로 제한합니다.

목록은 **한 owner 안의 literal prefix**, 기본 50·최대 200개입니다. `_` 등의 SQL LIKE 문자를 와일드카드로 해석하지 않습니다. cursor는 조회 범위·prefix·epoch·마지막 key에 묶이며 다른 범위로 재사용할 수 없습니다. 페이지마다 별도 스냅샷이므로 동시 변경 중 누락이 가능하며 백업이나 전체 원자적 목록으로 사용하지 않습니다. 대량 prefix 삭제는 제공하지 않습니다.

```java
extensions.pendingWrites()
    .execute("myrpg", plan, persistedRewardEventId, PendingWrites.Policy.defaults())
    .whenComplete((resolved, error) -> {
        if (error != null) {
            // ID 내용 충돌·타입 오류 등 명시적 실패 처리.
        } else {
            switch (resolved.state()) {
                case CONFIRMED -> {
                    // receipt().orElseThrow().outcome()도 확인: CONDITION_FAILED는 보상 성공 아님.
                }
                case RESULT_EXPIRED -> { /* 이미 처리한 ID. 결과 원장 확인, 새 ID 재지급 금지. */ }
                case REQUIRES_CONFIRMATION -> { /* 업무를 보류하고 원래 ID로 운영 확인. */ }
            }
        }
    });
```

복구 도우미는 동일 불변 계획·ID로 조회/재전송하며 횟수·지터·총 기한이 제한됩니다. NOT_OBSERVED_YET를 실패로 확정하지 않습니다. 기본 정책은 최대 8회·15초, 최대 허용은 16회·1분이며 추적 한도는 128건입니다. 미해결/결과 만료 건은 `tracked()`에 남고 완료된 알림만 `forget(id)`로 지웁니다. 이것은 **프로세스 메모리 도우미**이며 재시작을 견디는 게임 사건 원장이 아닙니다. 영속 사건 ID·계획·처리 상태는 소비자가 보존해야 합니다.

## 변경 이벤트와 표시 캐시

실제로 바뀐 주소의 SET/DELETE 이벤트는 변수·작업 결과와 같은 DB 트랜잭션으로 outbox에 기록합니다. NO_CHANGE·CONDITION_FAILED·동일 ID 재요청에는 새 변경 이벤트를 만들지 않습니다. 이벤트에는 값이 없으며 전체 주소·버전·작업 ID·발생 서버가 있습니다. 구독별 전달 상태와 lease/ack를 사용하므로 큰 event ID를 먼저 처리해도 늦게 커밋한 작은 ID를 건너뛰지 않습니다.

```java
var spec = new kr.lunaf.varstore.api.events.SubscriptionSpec(
    "myrpg-display-lobby", "myrpg",
    kr.lunaf.varstore.api.events.SubscriptionMode.EPHEMERAL,
    Duration.ofMinutes(1), Duration.ofHours(24));
extensions.events().subscribe(spec, event -> {
    displayState.invalidate(event.address()); // 호출자 제공: 멱등인 표시 상태 무효화
    return CompletableFuture.completedFuture(null); // 성공 완료 후 ack
}, displayState::clear).thenAccept(subscription -> {
    // 등록 완료 이후에만 첫 Primary 로딩 시작. 종료 시 subscription.close().
});
```

위의 `displayState`는 소비자의 캐시/표시 상태입니다. 아래 `CacheHandle`을 사용하면 구독·무효화·재설정이 자동 연결되므로 별도 구독을 만들 필요가 없습니다. EPHEMERAL은 살아 있는 대상별 표시용, DURABLE은 고정 subscriber ID로 재시작 후 미처리 알림을 회수하는 용도입니다. 각 서버는 서로 다른 ID를 사용해야 모두 전달받습니다. 구독은 활성화 이후부터 시작하며 이전 변경 이력 전체를 제공하지 않습니다.

코어 인스턴스별 구독은 최대 64개, 소비자 콜백 슬롯은 128개입니다. 250ms 간격으로 최대 8개씩 회수하고 heartbeat로 구독 리스를 갱신합니다. 콜백 기한은 전달 리스와 20초 중 짧은 쪽보다 작으며, 실패는 지수 간격으로 최대 8회 전달한 뒤 dead letter로 남습니다. 실패 응답 없이 프로세스가 반복 종료되는 경우에도 DB의 누적 회수 100회 한도로 제한하며, 재등록으로 이 한도를 초기화하지 않습니다. 기한이 지난 콜백의 사용자 코드는 나중에 끝날 수도 있으므로 그 효과는 소비자가 멱등하게 처리해야 합니다.

전달은 중복될 수 있습니다. 콜백은 멱등하게 만들고 비동기 완료가 성공해야 ack합니다. 지속 실패는 dead letter에 남으며 `subscription.retryDeadLetters(limit)`로 제한된 재전달을 요청합니다. `state().resyncRequired()` 또는 resync 콜백을 받으면 표시 상태를 지운 뒤 `subscription.reset()` 완료까지 새 로딩을 보류합니다. reset은 해당 구독의 대기·dead-letter 행을 버리는 명시적 재동기화 경계이며, 완료 뒤 Primary 스냅샷을 읽습니다. 보존 범위를 넘은 알림을 복구했다고 가정하지 않습니다. 자체 이벤트 구독은 이 재설정 절차를 직접 구현해야 합니다. outbox는 아이템 지급의 exactly-once 보장이 아닙니다.

### 캐시는 명시적이며 기본 OFF

Paper `config.yml`의 `cache.enabled: true`로 서버 공용 `DisplayCache` 서비스를 켭니다. 전역 기본값은 10,000항목·16MiB이며 소비자는 이 안에서 더 작은 한도를 배정받습니다. 소비 플러그인마다 별도의 전역 관리자를 만들지 말고 Bukkit 서비스를 공유하세요. 클래스는 `kr.lunaf.varstore.cache`이며 소비 빌드에 `varstore-cache-1.3.0.jar`를 compileOnly로 추가합니다.

```java
DisplayCache shared = getServer().getServicesManager().load(DisplayCache.class);
if (shared == null) throw new IllegalStateException("Display cache disabled");
CacheHandle view = sessions.own(shared.open(store, "myrpg", new CacheLimits(500, 1_048_576)));
view.ready().thenCompose(ignored -> view.getCached(data, chat, Duration.ofSeconds(5)));

CachedValue<Boolean> visible = view.peekCached(data, chat, Duration.ofSeconds(5));
switch (visible.state()) {
    case VALUE -> { /* visible.value().orElseThrow() 표시 */ }
    case ABSENT -> { /* 실제 값 없음. 명시적으로 chat.defaultValue() 표시 가능 */ }
    case MISS -> { /* 미로딩: 로딩 중 표시 */ }
    case STALE -> { /* 오래된 표시임을 알리거나 로딩 중 표시 */ }
    case UNAVAILABLE -> { /* 조회 불가. 오류를 false나 기본값으로 숨기지 않음 */ }
}
```

`getCached`만 비동기 로딩을 시작하고 `peekCached`는 메모리만 즉시 읽습니다. 실제 구독 활성화 전에는 NOT_READY/UNAVAILABLE이며 `ready()`를 기다려야 합니다. `maxAge`는 양수·최대 1일이고 **쿼리 시작 시각부터** 계산합니다. 느린 쿼리가 끝났다고 신선도가 새로 연장되지 않습니다. 같은 주소의 로딩은 합치고 로컬 커밋·UNKNOWN·원격 이벤트가 로딩과 겹치면 이전 결과를 버립니다. 역순 이벤트·삭제·재생성은 값 설치 대신 무효화로 처리합니다. 이벤트 단절은 캐시를 지우고 구독을 재설정하며 epoch 변경은 핸들을 폐기합니다.

한도는 값의 인코딩 크기·메타데이터·로딩 예약을 포함합니다. STRING 로딩은 기존 표시값에 더해 최대 16KiB를 미리 예약하므로 충분한 바이트 한도가 필요합니다. 한도를 넘으면 OVERLOADED/UNAVAILABLE이고 무한히 대기 요청을 쌓지 않습니다. `view.close()`는 구독·리스너·표시 상태를 정리합니다. 기존 `get/getVersioned`, CAS·트랜잭션·increment는 계속 Primary를 사용하며 캐시 값으로 결제/보상 조건을 결정하지 않습니다.

## 명시적 객체 Codec

실행 가능한 [구조화 설정 예제](../examples/structured/src/main/java/kr/lunaf/varstore/examples/structured/StructuredPlugin.java)는 `/profilesettings get`과 `/profilesettings save <language> <true|false> <persisted-operation-uuid>`를 제공합니다. 설정 직렬화는 제한된 worker에서 수행하고 완료 시 현재 플레이어 세션을 검사합니다.

`varstore-codec`는 기본 STRING 위에 `{codec,schema,payload}` JSON envelope를 저장합니다. 전체 envelope가 UTF-8 16KiB 안이어야 하며 깊이 32·노드 4,096 한도도 적용합니다. 일반 STRING 키를 자동으로 객체로 해석하지 않으며 Java 직렬화·임의 클래스 생성은 제공하지 않습니다. 소비 프로젝트에 `varstore-codec-1.3.0.jar`를 compileOnly로 추가합니다.

```java
// imports: kr.lunaf.varstore.codec.*, java.util.Map
record ProfileSettings(String language, boolean notifications) {}
Codec<ProfileSettings> settingsCodec = new Codec<>() {
    public String id() { return "myrpg.profile-settings"; }
    public int schemaVersion() { return 1; }
    public JsonValue encode(ProfileSettings value) {
        return JsonValue.object(Map.of(
            "language", JsonValue.string(value.language()),
            "notifications", JsonValue.bool(value.notifications())));
    }
    public ProfileSettings decode(int version, JsonValue payload) {
        var fields = ((JsonValue.ObjectValue) payload).fields();
        return new ProfileSettings(
            ((JsonValue.StringValue) fields.get("language")).value(),
            ((JsonValue.BooleanValue) fields.get("notifications")).value());
    }
};
CodecAdapter objects = new CodecAdapter(2, 128); // 소비 플러그인 종료 시 close()
CodecKey<ProfileSettings> settingsKey = CodecKey.of("profile/settings", settingsCodec);
EncodedValue<ProfileSettings> snapshot = objects.prepare(settingsKey, new ProfileSettings("ko_kr", true));
objects.data(data).set(settingsKey, snapshot, persistedSettingsOperationId)
    .thenCompose(receipt -> objects.data(data).get(settingsKey));
```

`prepare`는 저장 전의 **동기 CPU 단계**입니다. mutable 입력은 안전하게 소유한 시점에 불변 `JsonValue`로 복사하며 이후 입력 수정은 준비된 STRING을 바꾸지 않습니다. 비용이 큰 사용자 encoder는 게임 스레드에서 실행하지 말고 호출자가 관리하는 제한된 worker에서 준비하세요. 저장 큐에는 `EncodedValue`만 전달합니다. decode는 어댑터의 제한된 worker/queue에서 실행됩니다. 조회된 객체 수정은 자동 저장이 아닙니다.

Codec ID 불일치·지원하지 않는 schema·잘못된 JSON·크기 초과·decode 오류는 `CodecException.code()`로 명시합니다. 이전 버전 지원은 `supportsVersion(version)`과 `decode(version,payload)`에 직접 구현해야 하며 읽는 중 데이터를 자동 덮어쓰지 않습니다. 쓰기 receipt는 원래 STRING receipt를 유지해 커밋 후 decode 실패를 쓰기 실패로 오인하지 않게 합니다. 재시도에는 동일 작업 ID와 같은 준비 snapshot을 사용합니다. API 버전·DB 스키마 버전·Codec schema는 서로 별개입니다.
