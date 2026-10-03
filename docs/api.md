# 기본 API와 저장 계약

[README로 돌아가기](../README.md) · [개발과 수정 안내](development.md)

처음 연동할 때는 [소비 API](#소비-api)를 먼저 읽고, 결제·보상·재시도를 구현하기 전에 저장 계약을 확인하세요. 정의·목록·캐시·Codec는 [확장 API](extensions.md), 게임 스레드 처리는 [Paper 세션](integrations.md)에 있습니다.

## 저장 계약

- 주소는 `network / namespace / scope kind / scope id / owner type / owner id / key`의 독립 필드입니다. NETWORK의 scope ID는 `_`, SERVER는 명시적인 서버 ID입니다. 플레이어 소유자는 UUID입니다.
- network·namespace·server는 소문자 ASCII 영숫자·`.`·`_`·`-`, 각 64바이트까지입니다. key는 `/`도 허용하며 128바이트까지입니다. owner type은 대문자 ASCII 유형 32바이트, owner ID는 공백·제어 문자·`/`·`\`·`:`를 제외한 Unicode를 UTF-8 128바이트까지 허용하며 원래 표기를 보존합니다. 잘못된 이름은 자동으로 변경하지 않습니다.
- STRING은 UTF-8 16KiB, LONG은 부호 있는 64비트 정수, BOOLEAN·UUID를 지원합니다. PostgreSQL text가 저장할 수 없는 NUL과 잘못된 Unicode는 거절합니다. `null`은 값이 아니며 삭제는 `delete`로 요청합니다.
- 빈 문자열·0·false·값 없음은 다릅니다. 조회 장애·타입 불일치는 예외이며 `getOrDefault`가 기본값으로 감추지 않습니다. 삭제 후에도 기존 타입은 보존합니다.
- `set`의 동일 값, `increment`의 0 증가, 이미 없는 값 삭제는 NO_CHANGE입니다. CAS·존재 조건 불일치는 CONDITION_FAILED이며 장애가 아닙니다.
- 버전은 `(storageEpoch, generation, revision)`입니다. 실제 변경만 revision을 증가시키고 tombstone을 자동 삭제하지 않습니다. 이전 버전은 삭제·재생성 후에도 유효해지지 않습니다.
- `getAll`은 최대 64개 명시적 키를 한 SQL 문장 스냅샷에서 읽습니다. `execute`는 한 network·namespace에서 최대 16주소·64KiB이며 주소당 변경은 한 번입니다.
- 쓰기 ID와 내용 fingerprint, 결과를 변수 변경과 함께 커밋합니다. 같은 ID·같은 내용은 원 결과를 재사용하고 다른 내용은 IDEMPOTENCY_KEY_REUSED입니다. 조건 실패도 원 결과로 보존합니다.
- 전체 결과는 운영 도구의 `prune-results`로 7일 이후 최대 1,000개씩 정리합니다. ID·fingerprint·최종 결과 표식은 영구 보존합니다. 만료된 ID는 ALREADY_PROCESSED_RESULT_EXPIRED이며 다시 실행하지 않습니다.
- `operation(id)`의 NOT_OBSERVED_YET는 실패 확정이 아닙니다. 아직 진행 중일 수 있습니다. UNKNOWN_COMMIT_OUTCOME은 같은 ID로 조회·재요청합니다. 새 ID로 무작정 재시도하지 않습니다.

`fsync=on`, `full_page_writes=on`, `synchronous_commit=on`을 운영 기준으로 사용합니다. strict 검사가 위험한 설정이나 읽기 복제본을 발견하면 저장을 거절합니다. DB 디스크 손실, 과거 백업으로의 복구, API에 전달되지 않은 게임 사건까지 보존하는 계약은 아닙니다.

## 소비 API

API JAR은 `compileOnly`로 참조하고 소비 JAR에 포함하지 않습니다. 소비 플러그인의 `plugin.yml`에는 `depend: [VarStore]`를 선언합니다. 공개 API 패키지는 `kr.lunaf.varstore.api`입니다. 다운로드한 API를 소비 프로젝트의 `libs/`에 둔 Gradle Kotlin DSL 예입니다.

```kotlin
dependencies {
    compileOnly(files("libs/varstore-api-1.3.0.jar"))
    // PaperVarStore 등록 서비스를 사용할 때만 추가합니다.
    compileOnly(files("libs/varstore-paper-1.3.0.jar"))
}
```

소비 프로젝트에는 Java 21과 Paper API 의존성도 필요합니다. 전체 구성은 [preferences 빌드 설정](../examples/preferences/build.gradle.kts)과 [공통 빌드 설정](../build.gradle.kts)을 참고하세요.

```java
VarStore store = getServer().getServicesManager().load(VarStore.class);
if (store == null) throw new IllegalStateException("VarStore service missing");
var level = VarKey.longKey("level");
store.ready()
    .thenCompose(ignored -> {
        var data = store.namespace("myrpg").network().player(playerUuid);
        return data.set(level, 10L).thenCompose(receipt -> data.get(level));
    })
    .whenComplete((value, error) -> {
        // Handle storage errors. To touch players, return to the Paper scheduler
        // and re-resolve playerUuid after checking the current session token.
    });
```

기본 namespace 소유권 검사를 사용하려면 `PaperVarStore` 서비스를 받아 `register(this, getName().toLowerCase(java.util.Locale.ROOT))`로 자기 플러그인 영역을 등록합니다. 이 경우 Paper 모듈도 compileOnly로 참조합니다. 공유 영역은 `shared-namespaces` 설정으로 허용합니다. raw `VarStore`는 신뢰하는 JVM 내 공통 API입니다.

```java
UUID eventId = persistedQuestEventId; // 재시작 후에도 같은 사건에 같은 ID 사용
var initialized = data.setIfAbsent(level, 0L, persistedInitializationId);
initialized.thenCompose(r -> data.increment(level, 1L, eventId));

data.getVersioned(level).thenCompose(current -> {
    if (current.isEmpty()) return data.setIfAbsent(level, 1L, RuntimeIds.random());
    return data.compareAndSet(level, current.get().version(), 20L, RuntimeIds.random());
});

var balanceKey = VarKey.longKey("balance");
var balance = data.target(balanceKey);
var claimed = data.target(VarKey.booleanKey("rewards/2026-09-15"));
var plan = TransactionPlan.builder()
    .requireAbsent(claimed)
    .requireLongRange(balance, 0L, Long.MAX_VALUE - 100)
    .increment(balance, 100)
    .set(claimed, true)
    .build();
data.setIfAbsent(balanceKey, 0L, persistedBalanceInitializationId)
    .thenCompose(ignored -> store.namespace("myrpg").execute(plan, persistedRewardEventId));
```

실제 아이템 지급과 DB 트랜잭션은 하나의 원자적 작업이 아닙니다. 예제 보상은 DB 내 점수만 지급합니다. 외부 지급은 지급 원장·복구·중복 처리 정책이 추가로 필요합니다. 날짜별 키를 만드는 기능에는 키 보존 정책도 필요합니다. 제공한 보상 플러그인은 고정된 마지막 날짜·점수 두 키를 사용합니다.

콜백은 게임 메인 스레드에서 실행된다고 가정하지 않습니다. DB 연결과 잠금을 반환한 뒤 별도 완료 실행기에 전달합니다. 이미 완료된 Future에 붙인 콜백은 붙인 스레드에서 실행될 수 있습니다. `join()`·`get()`으로 게임 스레드를 막지 않습니다. 독립 제출의 순서는 보장하지 않으므로 필요한 순서는 `thenCompose`로 연결합니다.

대기열은 개수·바이트 모두 제한하며 결과 전달을 기다리는 요청도 한도에 포함합니다. 접수한 각 요청은 별도 가상 스레드에서 결과를 전달하므로 느린 소비 콜백이 다른 접수 요청의 완료를 막지 않습니다. 실행 중 콜백까지 보유하는 전달 슬롯은 대기열 개수 한도 + 2, 요청 페이로드 합계는 대기열 바이트 한도 + 128KiB로 제한합니다. OVERLOADED는 접수 거절이며 저장 성공이 아닙니다. 소비 콜백을 장시간 막으면 후속 요청도 역압을 받습니다. 취소된 Future는 DB 변경 취소의 증거가 아닙니다.

코어는 READY 전에 백그라운드에서 `RuntimeIds`의 난수 시드를 준비합니다. 이후 자동 작업 ID·확인 토큰은 메모리 안의 HMAC 카운터로 생성하므로 게임 스레드에서 난수 장치를 읽지 않습니다. 직접 ID가 필요한 예제에서는 READY 이후 `RuntimeIds.random()`을 사용합니다. 재시도에는 생성한 ID를 보존하세요. 다른 저장 제공자를 구현한다면 `RuntimeIds.initialize()`를 자체 백그라운드 초기화에서 호출해야 합니다.
