# VarStore

Paper 서버들이 공용 PostgreSQL Primary에 영속 변수를 저장하는 Java 21 플러그인입니다. 모든 저장 API는 비동기이며 쓰기 성공은 DB 커밋 응답을 확인한 뒤 전달합니다. BungeeCord는 접속·이동만 담당하고 저장 요청을 중계하지 않습니다. 접속자가 없어도 저장할 수 있습니다.

**현재 버전: 1.0.0.** 아래 검증 기록에 없는 환경은 지원을 확인하지 않았습니다. 실측 결과를 성능 보장으로 해석하지 마세요.

## 구성과 빌드

| 모듈 | 내용 |
| --- | --- |
| `varstore-api` | 타입 안전한 주소, Future 기반 API, 결과·오류·버전, 선언형 트랜잭션 |
| `varstore-core` | 제한된 비동기 실행기, 대기열·바이트 한도, 상태·종료 처리 |
| `varstore-postgres` | HikariCP, JDBC, 행 잠금, 작업 ID, 스키마 및 복구 세대 검사 |
| `varstore-paper` | Bukkit 서비스, 설정, 관리자 명령, 권한, 감사 |
| `varstore-tools` | 별도 계정으로 수행하는 마이그레이션·검증·복구 도구 |
| `varstore-testkit` | 소비 구현에서도 실행할 수 있는 실제 저장 계약 시험 |
| `varstore-testkit-paper` | 시험 전용 Paper 메인 스레드 부하 측정 플러그인 |
| `examples/preferences` | 플레이어 알림 설정 저장 예제 |
| `examples/rewards` | 날짜 조건과 점수를 함께 변경하는 일일 보상 예제 |

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

산출물은 각 모듈의 `build/libs/`에 있습니다. 서버에는 `varstore-paper-1.0.0.jar`만 설치합니다. `-thin.jar`는 배포용이 아닙니다. API·sources·Javadoc JAR와 실행 가능한 tools JAR도 생성합니다. JDBC와 HikariCP는 서버 JAR 안에서 별도 패키지로 재배치합니다.

## 배포 파일

[GitHub Releases](https://github.com/M-LunaFarm/VarStore/releases)에서 서버 JAR, API·sources·Javadoc, tools, 예제 JAR, 전체 ZIP과 `SHA256SUMS`를 받습니다. ZIP의 JAR은 `jars/`에 있습니다. ZIP을 푼 위치에서 스키마 도구는 `java -jar jars/varstore-tools-1.0.0.jar migrate production`으로 실행합니다. 아래 모듈 경로·`./gradlew`·`scripts/` 명령은 [소스 저장소](https://github.com/M-LunaFarm/VarStore)를 체크아웃한 경우의 경로입니다. ZIP의 예제 소스는 참고용이며 실행 가능한 Gradle 예제 프로젝트는 소스 저장소에 있습니다.

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
java -jar varstore-tools/build/libs/varstore-tools-1.0.0.jar migrate production
java -jar varstore-tools/build/libs/varstore-tools-1.0.0.jar validate
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
    compileOnly(files("libs/varstore-api-1.0.0.jar"))
    // PaperVarStore 등록 서비스를 사용할 때만 추가합니다.
    compileOnly(files("libs/varstore-paper-1.0.0.jar"))
}
```

소비 프로젝트에는 Java 21과 Paper API 의존성도 필요합니다. 전체 구성은 [`examples/`](examples/) 및 루트 `build.gradle.kts`를 참고하세요.

```java
VarStore store = getServer().getServicesManager().load(VarStore.class);
if (store == null) throw new IllegalStateException("VarStore service missing");
var data = store.namespace("myrpg").network().player(playerUuid);
var level = VarKey.longKey("level");
store.ready()
    .thenCompose(ignored -> data.set(level, 10L))
    .thenCompose(receipt -> data.get(level))
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
    if (current.isEmpty()) return data.setIfAbsent(level, 1L, UUID.randomUUID());
    return data.compareAndSet(level, current.get().version(), 20L, UUID.randomUUID());
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
```

수정 요청은 주소·변경·조회 버전·작업 ID를 먼저 보여줍니다. 60초짜리 일회용 토큰은 실행자와 불변 변경 계획에 묶입니다. 그 사이 다른 서버가 값을 바꾸면 조건 실패합니다. force 명령은 없습니다. 실행자·대상·작업·이전/이후 버전·결과는 같은 DB 트랜잭션의 감사 기록에 남습니다.

상태·메트릭에는 값·플레이어 UUID·비밀번호를 넣지 않습니다. 메트릭은 누적 요청/성공/조건 실패/오류/재처리/불명확 결과, 제한된 지연 표본, 대기열 개수·바이트, 연결 수와 DB 크기입니다. 사용할 수 없는 측정값은 `-1`입니다. 지연 표본은 최근 최대 4,096건이며 서비스 SLO를 보장하는 수치가 아닙니다.

## 유지보수와 복구

런타임은 validate-only입니다. 스키마 이력의 버전·SQL 체크섬·실제 카탈로그 체크섬을 검사합니다. 마이그레이션은 DB advisory transaction lock으로 직렬화합니다. 업그레이드는 모든 작성자를 멈춘 뒤 백업하고 새 tools JAR로 migrate·validate를 수행합니다. 자동 역마이그레이션과 호환되지 않는 혼합 버전 운영은 지원하지 않습니다.

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

스키마 버전 1은 도구가 만든 테이블·제약·인덱스를 기준으로 검증하며 사용자 트리거·재작성 규칙·행 보안 정책은 허용하지 않습니다. 운영 테이블을 수동 확장하지 않습니다.

변수 하나를 반복 수정해도 작업 표식은 계속 늘어납니다. 초당 쓰기 10건이면 하루 864,000개 표식입니다. `diagnostics`와 부하 시험 결과로 테이블·인덱스·WAL·백업 비용을 산정하세요. 매 틱 좌표 저장이나 스코어보드 최신값 조회 용도는 권장하지 않습니다.

## 검증 기록과 출시 기준

실행 검증 조합은 다음과 같습니다. 테스트·부하·복구 결과는 [`verification/`](verification/)의 JSON 및 CSV로 공개합니다.

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
| 자동 계약·경계·권한·교착 재시도 | 45개 통과, 실패·건너뜀 0: [`contracts.json`](verification/contracts.json) |
| 커밋 응답 차단 | 실제 COMMIT 응답을 버린 뒤 동일 ID로 원 결과 확인, 값 1 유지: [`commit-response.json`](verification/commit-response.json) |
| Paper 기능·스레드 | 재시작·이동·두 소비 플러그인·관리자 CAS, 별도 JFR 관측 구간에서 메인 스레드 JDBC I/O 0건: [`paper-smoke.json`](verification/paper-smoke.json), [`paper-io-probe.json`](verification/paper-io-probe.json) |
| DB 강제 종료·백업 복원 | 성공한 값 복구, 작업 기록 동시 복원, 이전 epoch 거절: [`recovery.json`](verification/recovery.json) |
| 반복 장애 | 180.571초 동안 10회 중단·재연결, 고유 작업 3,277개와 최종 증가값 일치: [`outage.json`](verification/outage.json) |

반복 장애 시험에는 실제 UNKNOWN_COMMIT_OUTCOME 1건이 포함되며 같은 ID로 복구했습니다. 종료 후 연결·대기 요청·전달 슬롯·보유 요청 바이트·VarStore 플랫폼 스레드는 모두 0입니다. 유한한 관측 구간에서 확인한 결과이며 장기 누수 부재나 운영 RPO/RTO 보장은 아닙니다. 복구 연습의 DB 재시작은 3.508초, 백업 복원·epoch 전환은 1.427초였으며 작은 로컬 시험 DB에서 측정했습니다.

T01–T22와 추가 검증의 근거 연결은 [`release-audit.json`](verification/release-audit.json), 실제 실행한 JAR의 해시는 [`artifacts.json`](verification/artifacts.json)에 있습니다.

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

후속 범위는 별도 캐시 API, 알림/outbox, Skript 애드온, Codec·JSON·TTL·프록시/Folia 어댑터입니다. 1.0은 캐시·Redis·SQLite/MySQL·REST·웹 UI·자동 객체 수집·전체 인벤토리·세션 소유권을 포함하지 않습니다.

## 공식 설계 근거

- [Paper 프로젝트 구성](https://docs.papermc.io/paper/dev/project-setup/) 및 [Java 지원 표](https://docs.papermc.io/paper/getting-started/)
- [Paper 스케줄러](https://docs.papermc.io/paper/dev/scheduler/)와 [플러그인 메시징](https://docs.papermc.io/paper/dev/plugin-messaging/)
- [PostgreSQL READ COMMITTED](https://www.postgresql.org/docs/18/transaction-iso.html)와 [명시적 잠금](https://www.postgresql.org/docs/18/explicit-locking.html)
- [PostgreSQL WAL 설정](https://www.postgresql.org/docs/18/runtime-config-wal.html) 및 [백업](https://www.postgresql.org/docs/18/backup.html)

MIT 라이선스. API 버전과 스키마 버전은 별도로 관리합니다. 이름은 이 저장소의 프로젝트명이며 상표 독점이나 외부 이름 충돌 부재를 보장하지 않습니다.
