# VarStore

Paper 서버들이 공용 PostgreSQL Primary에 영속 변수를 저장하는 Java 21 플러그인입니다. 모든 저장 API는 비동기이며 쓰기 성공은 DB 커밋 응답을 확인한 뒤 전달합니다. BungeeCord는 접속·이동만 담당하고 저장 요청을 중계하지 않습니다. 접속자가 없어도 저장할 수 있습니다.

**현재 소스 버전: 1.3.0, DB 스키마: 2.** 키 정의·페이지 조회·변경 이벤트·표시 캐시·Paper 세션 도우미·선택적 PlaceholderAPI/Skript·객체 Codec를 제공합니다. 기존 1.0.0 실행 기록은 아래에서 별도로 표시합니다. 측정 기록에 없는 환경의 지원이나 성능을 보장하지 않습니다.

## 구성과 빌드

| 모듈 | 내용 |
| --- | --- |
| `varstore-api` | 타입 키·결과·버전·트랜잭션과 선택적 `VarStoreExtensions` 계약 |
| `varstore-core` | 제한된 비동기 실행기, 정의 레지스트리·동일 ID 복구·이벤트 전달 |
| `varstore-postgres` | JDBC·행 잠금·작업 ID, 순차 마이그레이션·outbox·목록 조회 |
| `varstore-cache` | 전역/소비자 한도가 있는 명시적 표시 캐시 |
| `varstore-codec` | STRING 위의 16KiB JSON envelope와 명시적 객체 어댑터 |
| `varstore-placeholderapi` | 허용한 표시 키만 제공하는 선택적 PlaceholderAPI 애드온 |
| `varstore-skript` | 비동기 scalar/목록 구문을 제공하는 선택적 Skript 애드온 |
| `varstore-paper` | Bukkit 서비스, 설정, 관리자 명령, 권한, 감사 |
| `varstore-tools` | 별도 계정으로 수행하는 마이그레이션·검증·복구 도구 |
| `varstore-testkit` | 소비 구현에서도 실행할 수 있는 실제 저장 계약 시험 |
| `varstore-testkit-paper` | 시험 전용 Paper 메인 스레드 부하 측정 플러그인 |
| `examples/preferences` | 플레이어 알림 설정 저장 예제 |
| `examples/rewards` | 날짜 조건과 점수를 함께 변경하는 일일 보상 예제 |
| `examples/quests` | 원자적 퀘스트 증가·조회·페이지 목록 예제 |
| `examples/structured` | 버전이 있는 객체 설정·불변 snapshot·동일 ID 저장 예제 |

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew build
# 실제 DB 계약 시험을 포함하려면 먼저 전용 시험 DB를 준비합니다.
export VARSTORE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:25432/varstore
export VARSTORE_TEST_DB_USER=varstore
export VARSTORE_TEST_DB_PASSWORD='your-test-password'
./gradlew build
```

환경 변수가 없는 로컬 빌드는 DB 시험을 건너뜁니다. CI는 PostgreSQL 18 서비스를 제공하여 DB 시험을 실행합니다. `build/reports/tests`와 JUnit XML에서 건너뛴 시험을 확인하세요. Java 25를 기본으로 쓰는 호스트에서도 Gradle은 Java 21로 실행하세요.

전체 서버 시험을 재현하려면 전용 시험 DB를 선택하고 다음 순서로 실행합니다. Python 3, Node.js 22 이상, npm, PostgreSQL 클라이언트, Docker가 필요합니다. 서버 시험은 루프백의 오프라인 인증 네트워크를 만들며 운영 프록시 설정으로 사용하지 않습니다. 시험 도구는 실제 테스트 서버 실행을 위해 EULA 동의 파일을 생성합니다.

```sh
python3 scripts/fetch-test-servers.py
npm ci --prefix scripts/bots
export VARSTORE_JDBC_URL="$VARSTORE_TEST_JDBC_URL"
export VARSTORE_DB_USER="$VARSTORE_TEST_DB_USER"
export VARSTORE_DB_PASSWORD="$VARSTORE_TEST_DB_PASSWORD"
python3 scripts/paper-smoke.py
```

Paper 시험은 세 실제 서버·BungeeCord·봇을 띄워 저장·강제 종료·재시작·이동·권한·두 예제 플러그인을 확인하고, 10만 키에서 일반/큰 값/최대 트랜잭션/동일 키 부하를 기록합니다. 시험 전용 `VarStoreBench` JAR은 운영 서버에 설치하지 않습니다.

실제 DB 장애·복구 시험은 별도 컨테이너를 사용합니다. 다음 고정 계정은 루프백의 폐기 가능한 시험 DB에만 사용합니다.

```sh
docker run -d --name varstore-fault-postgres -p 127.0.0.1:25435:5432 \
  -e POSTGRES_USER=varstore -e POSTGRES_PASSWORD=varstore-test \
  -e POSTGRES_DB=varstore postgres:18
python3 scripts/recovery-test.py
python3 scripts/outage-test.py
```

커밋 응답 단절은 `python3 scripts/fault-proxy.py`를 별도 터미널에 띄우고 `./gradlew :varstore-testkit:faultHarness`로 재현합니다. 기본 프록시는 실제 PostgreSQL 25432 앞의 25433에서 COMMIT 프레임의 응답을 버립니다. 다른 시험 DB명은 `VARSTORE_TEST_JDBC_URL`과 `VARSTORE_FAULT_JDBC_URL`로 동일하게 지정합니다. 프록시와 테스트 서버는 운영 DB에 연결하지 않습니다.

산출물은 각 모듈의 `build/libs/`에 있습니다. 기본 서버에는 `varstore-paper-1.3.0.jar`를 설치합니다. PlaceholderAPI/Skript 연동이 필요할 때만 해당 애드온 JAR과 그 외부 플러그인을 추가합니다. cache·codec·core·postgres/API JAR은 별도 Bukkit 플러그인으로 설치하지 않습니다. `-thin.jar`는 배포용이 아닙니다. API·sources·Javadoc JAR와 실행 가능한 tools JAR도 생성합니다. JDBC와 HikariCP는 서버 JAR 안에서 별도 패키지로 재배치합니다.

## 배포 파일

[GitHub Releases](https://github.com/M-LunaFarm/VarStore/releases)에서 서버 JAR, API·sources·Javadoc, tools, 예제 JAR, 전체 ZIP과 `SHA256SUMS`를 받습니다. ZIP의 JAR은 `jars/`에 있습니다. ZIP을 푼 위치에서 스키마 도구는 `java -jar jars/varstore-tools-1.3.0.jar migrate production`으로 실행합니다. 아래 모듈 경로·`./gradlew`·`scripts/` 명령은 [소스 저장소](https://github.com/M-LunaFarm/VarStore)를 체크아웃한 경우의 경로입니다. ZIP의 예제 소스는 참고용이며 실행 가능한 Gradle 예제 프로젝트는 소스 저장소에 있습니다.

## 설치

1. PostgreSQL 18 Primary와 전용 DB를 준비합니다. 원격 DB는 게임 서버 IP만 허용하고 인증서를 배치합니다.
2. 모든 저장 작성자를 정지한 유지보수 시간에 DDL 계정으로 아래 스키마 도구를 실행합니다.
3. 각 Paper 서버에 서버 JAR을 설치합니다. `plugins/VarStore/config.yml`의 `network-id`는 같게, `server-id`는 서버마다 다르게 설정합니다.
4. Paper 프로세스에 런타임 DML 계정의 환경 변수를 전달합니다. `/varstore status`에서 READY를 확인합니다.
5. 소비 플러그인에서 서비스의 `ready()` 완료 후 저장 기능을 활성화합니다.

```sh
export VARSTORE_JDBC_URL=jdbc:postgresql://db.example.net:5432/varstore
export VARSTORE_DB_USER=varstore_migrator
export VARSTORE_DB_PASSWORD='read-from-your-secret-manager'
# 기본 TLS 모드는 verify-full입니다. PostgreSQL CA를 pgjdbc 표준 경로에 설치합니다.
java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar migrate production
java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar validate
```

`compose.yaml`은 루프백의 개발용 DB입니다. `VARSTORE_LOCAL_DB_PASSWORD`를 정하고 `docker compose up -d`로 실행합니다. 개발용 비TLS 연결에 한해 tools의 `VARSTORE_TLS_MODE=disable`, Paper의 `storage.tls-mode: disable`을 명시합니다. 원격 배포 기본값은 `verify-full`입니다. JDBC URL에 비밀번호·TLS 우회 옵션을 넣지 않습니다.

런타임 계정에는 스키마 소유권을 주지 않습니다. 관리자가 계정을 별도로 만든 뒤 필요한 권한만 부여합니다. 아래 역할명은 예시입니다.

```sql
GRANT CONNECT ON DATABASE varstore TO varstore_runtime;
GRANT USAGE ON SCHEMA public TO varstore_runtime;
GRANT SELECT ON vs_schema_history TO varstore_runtime;
GRANT SELECT, UPDATE ON vs_networks TO varstore_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON vs_variables TO varstore_runtime;
GRANT SELECT, INSERT, UPDATE ON vs_operations TO varstore_runtime;
GRANT INSERT ON vs_admin_audit TO varstore_runtime;
GRANT USAGE ON SEQUENCE vs_admin_audit_audit_id_seq TO varstore_runtime;
-- 스키마 2의 이벤트 구독·전달 및 정리 권한
GRANT SELECT, INSERT, UPDATE, DELETE ON vs_subscriptions, vs_outbox, vs_outbox_delivery TO varstore_runtime;
GRANT USAGE ON SEQUENCE vs_outbox_event_id_seq TO varstore_runtime;
```

PostgreSQL의 공유 행 잠금에는 해당 테이블의 UPDATE 권한도 필요합니다. 이 권한은 스키마 변경 권한이 아닙니다. 동일 JVM의 악성 플러그인이나 런타임 DB 자격 증명의 탈취를 namespace가 방어하지는 않습니다.

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

소비 프로젝트에는 Java 21과 Paper API 의존성도 필요합니다. 전체 구성은 [`examples/`](examples/) 및 루트 `build.gradle.kts`를 참고하세요.

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

실행 가능한 [구조화 설정 예제](examples/structured/src/main/java/kr/lunaf/varstore/examples/structured/StructuredPlugin.java)는 `/profilesettings get`과 `/profilesettings save <language> <true|false> <persisted-operation-uuid>`를 제공합니다. 설정 직렬화는 제한된 worker에서 수행하고 완료 시 현재 플레이어 세션을 검사합니다.

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

## Paper 세션과 선택적 애드온

`PaperSessions`는 메인 스레드에서 만든 소비자 소유 도우미입니다. 접속마다 새로운 토큰을 기록하고 완료 콜백을 메인 스레드로 돌린 뒤 UUID로 Player를 다시 찾습니다. 이전 접속·이동 뒤 늦은 결과, 비활성화된 소비자의 콜백은 폐기합니다.

```java
PaperSessions sessions = new PaperSessions(this); // onEnable, main thread
PaperSessions.Session session = sessions.capture(player.getUniqueId());
sessions.complete(session, data.get(level),
    (currentPlayer, value) -> currentPlayer.sendMessage("Level: " + value.orElse(0L)),
    (currentPlayer, error) -> currentPlayer.sendMessage("저장소 조회 실패"));
```

리스너·캐시 핸들·정의 등록은 `sessions.own(resource)`에 넣고 `onDisable`에서 `sessions.close()`합니다. `TrackedWrites(maxPending)`의 `submit(session, action, receipt -> receipt.outcome())`로 자기 소비자의 쓰기를 추적할 수 있습니다. `freeze(session)`은 새 제출을 막고 등록한 요청의 결과만 기다립니다. 완료 결과는 APPLIED/NO_CHANGE/CONDITION_FAILED 수를 구분하며, UNKNOWN을 포함한 쓰기 오류가 있으면 실패합니다. `/preferences transfer <BungeeServer>` 예제가 이 경로를 사용합니다. 외부 강제 이동이나 다른 플러그인의 메모리까지 저장하는 flush가 아닙니다.

### PlaceholderAPI

선택 JAR `varstore-placeholderapi-1.3.0.jar`는 PlaceholderAPI **2.12.3**을 대상으로 구성합니다. VarStore의 전역 캐시를 켜고 namespace를 명시적으로 허용합니다.

```yaml
# plugins/VarStore/config.yml
cache:
  enabled: true
  max-entries: 10000
  max-bytes: 16777216
shared-namespaces:
  VarStorePlaceholders: [varstorepreferences]
```

```yaml
# plugins/VarStorePlaceholders/config.yml
max-entries: 1000
max-bytes: 2097152
max-age-seconds: 5
refresh-ticks: 20
states:
  miss: '로딩 중'
  absent: '미설정'
  stale: '오래된 값'
  unavailable: '조회 불가'
mappings:
  chat:
    namespace: varstorepreferences
    key: chat-visible
    scope: network
```

`%varstore_chat%`은 접속 플레이어의 허용된 키만 표시합니다. `scope`는 `network` 또는 `server:<server-id>`입니다. 소비 플러그인이 nonsensitive·DISPLAY_ONLY 정의를 먼저 등록해야 하며 기본 mapping은 비어 있습니다. placeholder 계산은 `peekCached`만 호출하고 주기 갱신이 별도로 로딩합니다. PlaceholderAPI가 없어도 기본 VarStore는 동작합니다.

### Skript

선택 JAR `varstore-skript-1.3.0.jar`는 Skript **2.16.1**을 대상으로 합니다. 기본 namespace는 `varstoreskript`이고 다른 영역은 VarStore의 `shared-namespaces.VarStoreSkript`에 허용합니다. 기존 `{...}` 변수를 가로채지 않습니다.

```text
command /storedlevel:
    trigger:
        varstore read "LONG" key "level" in namespace "varstoreskript" scope "network" owner "player:%uuid of player%" guarded by player into {_r::*}
        if {_r::status} is "VALUE":
            send "Level: %{_r::value}%"
        else if {_r::status} is "ABSENT":
            send "아직 저장된 레벨 없음"
        else:
            send "조회 실패: %{_r::error}%"
```

scalar 4타입 read/set/delete, 원자적 LONG add, 제한된 prefix keys를 제공합니다. 수정 구문은 `operation "<영속 UUID>"`를 필수로 받습니다. 전체 구문과 타입·목록 예제는 [`scripts/fixtures/varstore-addon.sk`](scripts/fixtures/varstore-addon.sk)에 있으며 `@..._ID@`는 시험 실행기가 사건 UUID로 치환하는 토큰입니다. 운영 스크립트에서 같은 문자열을 다른 사건의 ID로 반복 사용하지 않습니다.

결과 local list는 `status/value/error/operation/cursor/keys::*`를 구분합니다. 읽기는 VALUE/ABSENT/FAILED, 쓰기는 APPLIED/NO_CHANGE/CONDITION_FAILED/FAILED이며 실패를 미설정으로 바꾸지 않습니다. 저장 후 continuation은 비동기 완료를 기다렸다가 메인 스레드에서 재개하고 오래된 플레이어 세션·unload된 스크립트에서는 중단합니다. 이미 지나간 이벤트를 continuation에서 취소할 수 없습니다. 이벤트 취소·잔액 승인처럼 즉시 결정이 필요한 로직은 따로 설계해야 합니다.

`FAILED`는 비동기 결과를 확인하지 못했다는 뜻이며 DB 롤백의 증거가 아닙니다. 특히 `UNKNOWN_COMMIT_OUTCOME`이면 원래 `operation`과 동일한 요청을 보존해 확인·재전송하고 새 ID로 바꾸지 마세요.

## 상태와 서버 이동

STARTING → READY, 장애 시 DEGRADED, 종료 시 DRAINING → CLOSED입니다. `ready()`는 최초 준비만 알려줍니다. 이후 상태는 `onStateChange` 또는 Paper의 `VarStoreStateEvent`로 감시합니다. 느린 소비자가 있는 경우 상태 통지는 최신 상태로 합쳐질 수 있으므로 `state()`가 현재 상태의 기준입니다.

이동을 제어하는 게임 플러그인은 새 업무 변경을 잠시 막고 추적 중인 저장 Future 완료 후 이동을 요청해야 합니다. 도착 서버는 읽기가 끝난 뒤 기능을 활성화합니다. 늦게 도착한 콜백은 UUID와 세션 토큰으로 걸러냅니다. VarStore가 모든 플러그인의 이동·세션·인벤토리를 자동 통제하지 않습니다.

BungeeCord 네트워크는 UUID 전달과 백엔드 직접 접속 차단을 함께 설정합니다. 운영 프록시는 인증을 켜고 올바른 IP forwarding을 사용하세요. 서버의 표시 이름을 UUID 대용으로 저장하지 않습니다. `/reload`와 플러그인 강제 재로딩은 지원하지 않습니다.

## 관리자 명령

권한은 `varstore.status`, `varstore.inspect`, `varstore.modify`, `varstore.diagnostics`로 나뉘며 기본 거부입니다. 수정은 콘솔 전용이고 권한이 있는 플레이어도 실행할 수 없습니다. `inspect`는 민감한 실제 값을 표시하므로 필요한 운영자에게만 허용합니다.

```text
varstore status
varstore diagnostics
varstore inspect production myrpg NETWORK _ SYSTEM global season LONG
varstore operation myrpg <operation-uuid>
varstore set production myrpg NETWORK _ SYSTEM global season LONG 2
varstore confirm <preview-token>
varstore delete production myrpg NETWORK _ SYSTEM global season LONG
varstore keys production myrpg NETWORK _ SYSTEM global quests/ 50
varstore describe myrpg preferences/chat-notify
varstore capacity
varstore pending
varstore events
varstore cache
varstore cache clear
```

수정 요청은 주소·변경·조회 버전·작업 ID를 먼저 보여줍니다. 60초짜리 일회용 토큰은 실행자와 불변 변경 계획에 묶입니다. 그 사이 다른 서버가 값을 바꾸면 조건 실패합니다. force 명령은 없습니다. 실행자·대상·작업·이전/이후 버전·결과는 같은 DB 트랜잭션의 감사 기록에 남습니다.

`keys/describe`는 `varstore.inspect`, `capacity/pending/events/cache`는 `varstore.diagnostics` 권한을 사용합니다. keys는 값 없이 메타데이터만 표시하며 빈 prefix는 `_`, 다음 페이지는 마지막 인자에 cursor를 붙입니다. describe는 로컬 정의가 없는 경우를 구분하고 민감한 기본값을 숨깁니다. pending은 복구 도우미가 **현재 프로세스에서 추적한 요청**만 보여줍니다. `cache clear`는 콘솔 전용이며 DB 데이터는 지우지 않습니다. events/capacity의 추정치·표본 제한·사용 불가 값은 결과에 표시됩니다.

상태·메트릭에는 값·플레이어 UUID·비밀번호를 넣지 않습니다. 메트릭은 누적 요청/성공/조건 실패/오류/재처리/불명확 결과, 제한된 지연 표본, 대기열 개수·바이트, 연결 수와 DB 크기입니다. 사용할 수 없는 측정값은 `-1`입니다. 지연 표본은 최근 최대 4,096건이며 서비스 SLO를 보장하는 수치가 아닙니다.

## 유지보수와 복구

런타임은 validate-only입니다. 스키마 이력의 버전·SQL 체크섬·실제 카탈로그 체크섬을 검사합니다. 마이그레이션은 DB advisory transaction lock으로 직렬화합니다. 업그레이드는 모든 작성자를 멈춘 뒤 백업하고 새 tools JAR로 migrate·validate를 수행합니다. 자동 역마이그레이션과 호환되지 않는 혼합 버전 운영은 지원하지 않습니다.

### 1.0.0 → 1.3.0

1. 모든 Paper 및 직접 API 작성자를 정지하고 DB와 설정을 백업합니다.
2. DDL 계정으로 **새 1.3.0 tools**의 `migrate <network-id>`를 실행합니다. 새 네트워크를 초기화할 때도 같은 명령을 사용합니다.
3. 기존 V001의 체크섬을 그대로 검증한 뒤 V002(outbox·구독·대상별 전달)를 순차 적용합니다. 과거 migration 파일을 수정하거나 이력 체크섬을 수동 변경하지 않습니다.
4. 위의 스키마 2 추가 테이블·시퀀스 권한을 런타임 계정에 부여하고 `validate`합니다.
5. 모든 서버 JAR을 함께 교체한 뒤 서비스 READY·기존 값/버전·기존 작업 ID 결과를 확인합니다. 구형 작성자는 outbox를 생성하지 않으므로 혼합 운영하지 않습니다.

정상 스키마 업그레이드는 storage epoch를 바꾸는 백업 복원이 아닙니다. 기존 변수·타입·버전·작업 표식과 결과 형식을 보존하며 새 ID를 발급해 기존 업무를 다시 실행하지 않습니다. DB 복원일 때만 아래 epoch 교체 절차를 따릅니다.

### 작업 표식·outbox 용량과 보존

```sh
# 제한된 주기 집계. 게임 요청마다 전체 테이블 COUNT를 하지 않습니다.
VARSTORE_CAPACITY_SAMPLE_SECONDS=2 \
  java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar capacity production
java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar diagnostics
# 7일 지난 전체 결과를 최대 1,000개씩 정리. 작업 ID 표식은 유지합니다.
java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar prune-results
# 이벤트 정리: 기본 24시간, 한 번에 최대 1,000개.
VARSTORE_OUTBOX_RETENTION_HOURS=24 VARSTORE_OUTBOX_PRUNE_BATCH=1000 \
  java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar prune-outbox production
```

`capacity`는 PostgreSQL 통계의 표식 증가량·행 추정치와 실제 relation/index 바이트를 구분해 보여줍니다. 테이블 통계·증가율은 **같은 스키마의 모든 network 합계**(`TABLE_GLOBAL`)이고, 전달 표본만 선택한 network 범위입니다. 전달 표본은 최대 10,000건이며 잘린 표본 여부를 표시합니다. 재전달 시도 표본은 현재 보존된 attempts 기준이라 수동 dead-letter 재시도로 초기화될 수 있습니다. 표본 시간은 1–30초, `VARSTORE_OPERATIONS_WARNING_BYTES`의 기본 경고선은 10GiB입니다. 통계 재설정·갱신 지연과 동시 부하 때문에 짧은 표본은 성장률을 정확히 대표하지 않을 수 있습니다. 하루/30일 예상치는 같은 고유 ID 유입 속도가 유지된다는 계산이며 WAL·vacuum·변수·outbox·백업 압축 비용을 제외합니다. 호스트 디스크 여유·백업 크기·복구 시간의 `-1`은 사용 불가이며 외부 모니터링으로 채워야 합니다.

`prune-outbox`의 retention은 1–720시간, batch는 1–1,000입니다. DURABLE 구독의 보존 범위 안에 있는 미처리 알림은 유지하고, 범위를 넘은 알림을 정리할 때는 해당 구독을 RESYNC_REQUIRED로 표시합니다. 이것은 미처리 업무를 성공했다고 처리하는 명령이 아닙니다. 소비자가 gap을 감지하고 자신의 상태를 재구성할 수 있어야 합니다. 결과/이벤트 정리는 운영 스케줄러에서 제한된 배치로 실행하며 자동 영구 표식 삭제는 제공하지 않습니다.

### 외부 변수의 dry-run

```sh
# DB 연결 없는 입력 검사
java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar import-dry-run export.csv
# 기존 DB의 주소·타입 충돌까지 검사하는 읽기 전용 모드
java -jar varstore-tools/build/libs/varstore-tools-1.3.0.jar import-dry-run export.csv --check-database
```

[실행 가능한 CSV 샘플](scripts/fixtures/import-example.csv)을 제공합니다. UTF-8 CSV의 정확한 헤더는 다음과 같습니다. 최대 8MiB·10,000행이며 scalar 4타입만 지원합니다.

```csv
network_id,namespace,scope_kind,scope_id,owner_type,owner_id,key,type,value
production,myrpg,NETWORK,_,SYSTEM,global,season,LONG,2
```

주소·타입·값·전체 UUID 형식, 입력 내 중복 주소와 UUID 표기 변환을 보고합니다. DB 검사 모드는 읽기 전용 REPEATABLE READ 스냅샷에서 기존 주소·tombstone 타입 충돌을 검사하고 저장값은 읽지 않습니다. 샘플은 주소 hash·타입·바이트 수만 표시하고 값을 숨깁니다. 자동 import, 기존 Skript 파일 삭제, 충돌 덮어쓰기는 수행하지 않습니다. 변환 매핑·건수·샘플과 영속 사건 ID 정책을 검토한 뒤 별도 이관 계획을 세우세요.

```sh
# 전체 DB의 일관된 논리 백업. 비밀번호는 .pgpass 또는 비밀 관리로 전달합니다.
pg_dump --format=custom --file=varstore.dump --dbname=varstore
# 별도로 준비한 빈 복구 DB로 복구합니다. 원본 DB를 덮어쓰지 않습니다.
pg_restore --exit-on-error --dbname=varstore_restored varstore.dump
```

복구 순서:

1. 게임 기능과 모든 저장 작성자를 중지하고 원본·로그를 보존합니다.
2. 변수·작업 표식·감사·네트워크 메타데이터를 동일 시점으로 복구합니다.
3. 복구 DB 환경 변수로 `validate`를 실행합니다.
4. `VARSTORE_WRITERS_STOPPED=true`를 설정하고 `rotate-epoch production`을 실행합니다.
5. 건수·샘플 값·작업 결과를 확인하고 모든 VarStore·소비 플러그인을 재시작합니다. 이전 핸들은 STALE_EPOCH로 거절됩니다.
6. 제한된 서버에서 읽기·쓰기를 확인한 뒤 전체 기능을 재개합니다. 외부 아이템·결제는 별도 업무 조정을 합니다.

더 짧은 RPO에는 base backup과 WAL 보관을 통한 PITR 또는 관리형 백업을 별도로 구성합니다. pg_dump에 WAL 파일을 붙이는 것은 PITR 구성이 아닙니다. 외부 백업 보관·마지막 백업 성공·디스크 여유·WAL 보관 실패·복구 실측 RPO/RTO를 운영 환경에서 점검하세요.

스키마 버전 2는 V001과 V002의 이력·도구가 만든 테이블·제약·인덱스·시퀀스 설정을 기준으로 검증하며 사용자 트리거·재작성 규칙·행 보안 정책은 허용하지 않습니다. 운영 테이블을 수동 확장하지 않습니다.

변수 하나를 반복 수정해도 작업 표식은 계속 늘어납니다. 초당 쓰기 10건이면 하루 864,000개 표식입니다. `diagnostics`와 부하 시험 결과로 테이블·인덱스·WAL·백업 비용을 산정하세요. 매 틱 좌표 저장이나 스코어보드 최신값 조회 용도는 권장하지 않습니다.

## 1.3.0 확장 검증

검증 환경은 Paper 1.21.11 build 132, BungeeCord 2093, Java 21, PostgreSQL 18입니다. 선택 애드온은 Skript 2.16.1과 PlaceholderAPI 2.12.3에서 실행했습니다. 루프백의 실제 서버와 봇을 이용한 기능 시험이며 접속자 규모별 TPS 보장은 아닙니다.

| 검증 | 근거 |
| --- | --- |
| 실제 DB를 포함한 전체 자동 계약 | 112개 통과, 실패·건너뜀 0: [`contracts.json`](verification/contracts.json): 테스트별 이름·결과·소스 해시 |
| 1.0 데이터 업그레이드 | 변수 300,188행·작업 20,153행·감사 27행·네트워크 67행의 전체 행 집계 해시와 기존 V001 이력 보존: [`upgrade-v1-v2.json`](verification/upgrade-v1-v2.json) |
| 실제 Paper 11개 항목 | 4타입 Skript·오류·목록, 캐시 무효화, 실제 DB 단절, 진행 중 쓰기와 서버 이동, 재접속·스크립트 unload, Codec, 애드온 없는 재시작: [`paper-extension.json`](verification/paper-extension.json) |
| 게임 스레드 I/O | 관측 구간의 메인 스레드 JDBC·애플리케이션 파일 I/O·Future 대기 0건. 최초 클래스 로딩의 JAR 읽기는 별도 기록: [`extension-io-probe.json`](verification/extension-io-probe.json) |
| 커밋 전후 연결 단절 | COMMIT 전달 전 연결 종료 및 실제 COMMIT 응답 폐기, 동일 ID 재시도와 변수·outbox 중복 방지: [`commit-response.json`](verification/commit-response.json) |
| 강제 종료·백업 복원·반복 단절 | 실제 DB SIGKILL, 전체 DB 복원과 epoch 차단, 183.217초 동안 DB 중단·복구 10회. 고유 작업 ID 3,133개와 최종 증가값 일치: [`recovery.json`](verification/recovery.json), [`outage.json`](verification/outage.json) |
| 운영 도구 | 용량·증가량 표본, 4타입 CSV dry-run, 기존 주소 충돌 거절: [`operator-tools.json`](verification/operator-tools.json) |
| outbox 비용·캐시·자원 회수 | 동일 하네스의 1.0/1.3 비교와 180초 확장 부하: [`extensions-load.json`](verification/extensions-load.json) |

정확한 실행 바이너리 해시와 기능별 근거는 [`artifacts.json`](verification/artifacts.json), [`release-audit.json`](verification/release-audit.json)에 있습니다. 기존 대규모 Paper 부하 수치는 아래의 1.0 기록에만 해당합니다. 새 비교 시험은 공유 호스트에서 단일 작업자가 순차 쓰기한 결과이며, WAL 증분은 **PostgreSQL 클러스터 전체**의 카운터라 다른 DB와 백그라운드 활동도 포함합니다. 유한한 자원 관측은 장기 누수 부재를 증명하지 않습니다.

동일 Java 하네스로 200회 예열 후 실제 값이 바뀌는 LONG 쓰기 2,000회를 순차 실행했습니다. 구독자가 없는 새 스키마를 사용하고 1.0 다음 1.3 순서로 측정했습니다. 1.3은 예열을 포함한 변경 2,200건에 outbox 이벤트 2,200개를 생성했습니다.

| 런타임 | p50 / p95 / p99 (ms) | 측정 쓰기 시간 | 클러스터 WAL 증분 |
| --- | ---: | ---: | ---: |
| 1.0.0 / 스키마 1 | 16.264 / 40.930 / 76.422 | 38.922초 | 2,339,024바이트 |
| 1.3.0 / 스키마 2 | 18.677 / 48.519 / 95.451 | 45.457초 | 3,802,344바이트 |

180.027초의 확장 부하에서는 변경 쓰기·고유 전달 이벤트가 각각 2,347건, 캐시 호출이 37,800건이었고 주입한 소비자 실패 93건을 재전달했습니다. 안정된 값 1,000회 조회는 직접 조회에서 DB 읽기 1,000회, 캐시에서 1회였습니다. 구독 생성·종료 17회와 로딩 중 무효화를 포함했으며, 종료 후 캐시 핸들·항목·바이트, 연결·대기 슬롯·보유 요청 바이트·VarStore 플랫폼 스레드는 모두 0이었습니다. 임시 구독은 0행, 재시작용 durable 구독은 의도대로 1행을 남겼습니다. 종료 후 GC 기준 힙 증가는 307,312바이트였습니다.

검증 중 발견한 실패도 보존했습니다. 최초 Skript 표현식 처리, 메인 스레드의 난수 장치 읽기, 구독 종료와 outbox 전달 생성의 경쟁 조건은 각각 수정 후 재검증했습니다. 병행 빌드 중 발생한 500ms DB 잠금 시간초과와, 캐시 첫 읽기에 항상 값이 있다고 가정했던 CI 테스트 실패도 별도 기록했습니다. CI 로그에는 캐시 상태가 남지 않아 구체적인 발생 순서는 단정하지 않습니다. 캐시 테스트는 무효화와 겹친 STALE만 제한적으로 다시 읽도록 수정했으며 운영 시간초과나 오류 의미를 바꾸지 않았습니다. [`diagnostic-extension-initial/`](verification/diagnostic-extension-initial/), [`diagnostic-extension-jfr/`](verification/diagnostic-extension-jfr/), [`diagnostic-extension-churn/`](verification/diagnostic-extension-churn/), [`diagnostic-build-contention/`](verification/diagnostic-build-contention/)에서 확인할 수 있습니다.

재현 도구: `scripts/paper-extension-smoke.py`, `scripts/commit-fault-test.py`, `scripts/recovery-test.py`, `scripts/outage-test.py`, `scripts/extensions-load.py`. 장애·JFR·성능 시험을 동시에 실행하지 마세요. 실제 기본 DB 중단까지 포함하는 Paper 시험은 전용 시험 컨테이너에서 `VARSTORE_TEST_MAIN_DB_OUTAGE=true`로 실행합니다.

## 1.0.0의 기존 실행 기록

이 절의 수치·JAR 해시·45개 시험 결과는 **확장 전 1.0.0 기록**입니다. outbox·캐시·Codec·애드온이 추가된 1.3.0의 검증이나 성능 결과로 대체해서 읽지 마세요. 기존 실행 조합은 다음과 같습니다. 테스트·부하·복구 결과는 [`verification/`](verification/)의 JSON 및 CSV로 공개합니다.

| 구성 요소 | 실행 검증 버전 |
| --- | --- |
| Paper | 1.21.11, build 132, 서버 3대 |
| BungeeCord | build 2093, 실제 서버 이동 |
| Java | OpenJDK 21.0.12 |
| PostgreSQL | 18.6, `fsync/full_page_writes/synchronous_commit=on` |

Folia, Velocity, 다른 Minecraft 버전과 DB 제품은 이 조합의 지원 범위에 포함하지 않습니다.

검증 결과:

| 검증 | 결과 및 근거 |
| --- | --- |
| 자동 계약·경계·권한·교착 재시도 | 45개 통과, 실패·건너뜀 0: [`contracts.json`](verification/v1.0.0/contracts.json) |
| 커밋 응답 차단 | 실제 COMMIT 응답을 버린 뒤 동일 ID로 원 결과 확인, 값 1 유지: [`commit-response.json`](verification/v1.0.0/commit-response.json) |
| Paper 기능·스레드 | 재시작·이동·두 소비 플러그인·관리자 CAS, 별도 JFR 관측 구간에서 메인 스레드 JDBC I/O 0건: [`paper-smoke.json`](verification/v1.0.0/paper-smoke.json), [`paper-io-probe.json`](verification/v1.0.0/paper-io-probe.json) |
| DB 강제 종료·백업 복원 | 성공한 값 복구, 작업 기록 동시 복원, 이전 epoch 거절: [`recovery.json`](verification/v1.0.0/recovery.json) |
| 반복 장애 | 180.571초 동안 10회 중단·재연결, 고유 작업 3,277개와 최종 증가값 일치: [`outage.json`](verification/v1.0.0/outage.json) |

반복 장애 시험에는 실제 UNKNOWN_COMMIT_OUTCOME 1건이 포함되며 같은 ID로 복구했습니다. 종료 후 연결·대기 요청·전달 슬롯·보유 요청 바이트·VarStore 플랫폼 스레드는 모두 0입니다. 유한한 관측 구간에서 확인한 결과이며 장기 누수 부재나 운영 RPO/RTO 보장은 아닙니다. 복구 연습의 DB 재시작은 3.508초, 백업 복원·epoch 전환은 1.427초였으며 작은 로컬 시험 DB에서 측정했습니다.

T01–T22와 추가 검증의 근거 연결은 [`1.0.0 release-audit.json`](verification/v1.0.0/release-audit.json), 당시 실행한 JAR의 해시는 [`1.0.0 artifacts.json`](verification/v1.0.0/artifacts.json)에 있습니다.

### 실제 Paper 부하 측정

Paper 3대·10만 기본 키·읽기 70%/쓰기 30%, 틱당 총 5회 접수로 100요청/초를 목표로 실행했습니다. 기본 부하는 20초 예열 후 60초, 나머지는 각각 20초입니다. 봇이 예제 플러그인을 사용한 상태에서 모든 측정 API 호출을 실제 Paper 메인 스레드에서 제출했습니다. 프로파일링과 DB 장애 시험은 이 측정 구간에 겹치지 않았습니다.

| 부하 | 요청 수 | 읽기 p95 / p99 (ms) | 쓰기 p95 / p99 (ms) | 접수 p99 (ms) | 오류 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 일반 값 ≤1KiB | 6,000 | 20.55 / 38.14 | 36.38 / 69.97 | 0.952 | 0 |
| STRING 16KiB | 2,000 | 32.35 / 69.60 | 89.52 / 207.24 | 1.754 | 0 |
| 16키·약 64KiB 트랜잭션 | 2,000 | 1576.68 / 1668.77 | 1917.71 / 2023.52 | 3.832 | 0 |
| 동일 LONG 키 증가 | 2,000 | 22.47 / 49.34 | 41.11 / 143.86 | 0.622 | 0 |

일반 단계의 쓰기 1,800건은 APPLIED 1,200건과 NO_CHANGE 600건입니다. 예열 때 사용한 값 일부를 다시 제출했기 때문이며, NO_CHANGE도 새 작업 ID의 결과 기록을 커밋합니다. 70/30은 API 읽기·쓰기 호출 비율이며 모든 쓰기가 변수 값을 변경한 부하는 아닙니다.

12,000건의 원시 CSV를 합쳐 순위 기반 백분위수를 계산했습니다. 서버별 백분위수를 평균내지 않았습니다. 일반 부하의 전체 표본은 목표 get p95/p99 25/100ms, write 50/150ms, 접수 p99 1ms를 만족했습니다. 서버별 접수 p99는 0.902–1.049ms여서 한 서버는 1ms를 소폭 넘었습니다.

최대 크기 트랜잭션은 서버마다 별도 16키를 사용했고, 동일 키 경쟁은 별도 단계로 측정했습니다. 최대 크기 단계는 마지막 응답까지 포함한 처리량이 92.24요청/초로 낮아지고 지연이 약 2초에 도달했습니다. 큰 값·최대 크기 트랜잭션에 일반 부하의 지연 수치를 적용하지 않습니다. 동일 키 단계의 최종 값은 600회 증가와 일치했습니다.

이 호스트는 다른 서비스를 함께 실행하는 6 vCPU·약 12GiB VM이며 PostgreSQL과 Paper를 같은 호스트에서 실행했습니다. 설계의 전용 DB 4 vCPU·8GiB·SSD 기준 환경과 다르며, 물리 저장장치 성능을 보증하지 않습니다. 각 Paper에는 `-Xmx640m`, `ActiveProcessorCount=2`를 적용했습니다.

추가로 같은 DB와 실행 중인 Paper 게임 기능 옆에서 별도 Java API 클라이언트 3개를 120초간 측정했습니다. 이 시험은 Paper 메인 스레드 제출 측정이 아닙니다. 새로운 값을 쓰는 일반 단계는 쓰기 1,800건 전부 APPLIED였고 전체 6,000건에서 오류 0건, 읽기 p95/p99 24.96/57.64ms, 쓰기 46.41/84.67ms, 접수 p99 1.743ms였습니다.

별도 클라이언트가 **같은 16키에 최대 크기 트랜잭션을 집중**한 단계에서는 쓰기 600건 중 525건이 오류(시간초과 195, 저장소 사용 불가 323, 커밋 결과 불명확 7)로 끝났고, 직후 동일 키 단계에서도 회복 전 24건의 오류가 기록됐습니다. 이는 측정한 처리 한계이며 성공으로 집계하지 않습니다. 고정된 처리량을 보장하지 않으며, 이런 집중 부하에는 요청량 제한과 동일 작업 ID를 통한 결과 확인이 필요합니다. 원시 오류·응답·자원 기록은 [`load-report.json`](verification/load-report.json), [`load-requests.csv`](verification/load-requests.csv), [`load-resources.csv`](verification/load-resources.csv), [`load-environment.json`](verification/load-environment.json)에 있습니다.

원시 표본과 재현 방법: [`load-paper-summary.json`](verification/load-paper-summary.json), [`paper-benchmark/`](verification/paper-benchmark/), `python3 scripts/aggregate-load.py`. 이전 SNAPSHOT의 예열 전·JFR 동시 수집·공유 트랜잭션 키 측정도 [`diagnostic-initial/`](verification/diagnostic-initial/)에 별도 보존했습니다. 코드와 측정 조건이 달라 성능 개선의 원인을 단일 요인으로 해석하지 않습니다.

1.3.0의 캐시·outbox·애드온·Codec는 위의 과거 부하 수치에 포함되지 않습니다. TTL·DB 네이티브 JSON/BYTES·프록시 JVM/Folia 어댑터는 후속 범위입니다. Redis·다른 DB 엔진·REST·웹 UI·자동 객체 수집·전체 인벤토리 동기화·네트워크 단일 작성자 소유권은 제공하지 않습니다.

## 공식 설계 근거

- [Paper 프로젝트 구성](https://docs.papermc.io/paper/dev/project-setup/) 및 [Java 지원 표](https://docs.papermc.io/paper/getting-started/)
- [Paper 스케줄러](https://docs.papermc.io/paper/dev/scheduler/)와 [플러그인 메시징](https://docs.papermc.io/paper/dev/plugin-messaging/)
- [PostgreSQL READ COMMITTED](https://www.postgresql.org/docs/18/transaction-iso.html)와 [명시적 잠금](https://www.postgresql.org/docs/18/explicit-locking.html)
- [PostgreSQL WAL 설정](https://www.postgresql.org/docs/18/runtime-config-wal.html) 및 [백업](https://www.postgresql.org/docs/18/backup.html)

MIT 라이선스. API 버전과 스키마 버전은 별도로 관리합니다. 이름은 이 저장소의 프로젝트명이며 상표 독점이나 외부 이름 충돌 부재를 보장하지 않습니다.
