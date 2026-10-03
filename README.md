# VarStore

**여러 Paper 서버에서 플러그인의 변수를 함께 저장하는 Java 21 플러그인입니다.**

플레이어 설정, 퀘스트 진행도, 점수 같은 값을 공용 PostgreSQL Primary에 저장합니다. 서버 A에서 저장한 값을 서버 B에서 읽을 수 있고, 플레이어가 접속하지 않은 상태에서도 저장할 수 있습니다. 모든 저장 API는 비동기이며, 쓰기 성공은 DB 커밋을 확인한 뒤 전달합니다.

현재 소스 버전은 **1.3.0**, DB 스키마는 **2**입니다. 저장 타입은 `STRING`, `LONG`, `BOOLEAN`, `UUID`이며, 조건부 변경·트랜잭션·같은 작업 ID로 재시도하는 기능을 제공합니다.

## 필요한 내용부터 보기

| 하고 싶은 일 | 읽을 문서 |
| --- | --- |
| 서버에 설치하고 DB 연결하기 | [설치와 설정](docs/setup.md) |
| 소스를 빌드하거나 기능 수정하기 | [개발과 수정 안내](docs/development.md) |
| 다른 플러그인에서 변수 읽고 쓰기 | [기본 API와 저장 계약](docs/api.md) |
| 키 목록·변경 이벤트·표시 캐시·객체 저장 쓰기 | [확장 API](docs/extensions.md) |
| Paper 콜백·PlaceholderAPI·Skript 연동하기 | [Paper 세션과 애드온](docs/integrations.md) |
| 명령어·업그레이드·백업·복구 확인하기 | [운영과 복구](docs/operations.md) |
| 시험 재현 방법과 기존 측정 결과 확인하기 | [테스트와 검증 기록](docs/testing.md) |
| HuskSync와 비슷한 프로젝트 비교하기 | [관련 프로젝트 비교](docs/comparison.md) |

## 빠른 시작

### 서버에 설치하기

1. [GitHub Releases](https://github.com/M-LunaFarm/VarStore/releases)에서 `varstore-paper-1.3.0.jar`와 `varstore-tools-1.3.0.jar`를 받습니다.
2. PostgreSQL 18의 전용 DB를 준비하고, 저장 작성자를 멈춘 상태에서 tools의 `migrate <network-id>`와 `validate`를 실행합니다. [설치 문서](docs/setup.md)에 계정 권한과 명령이 있습니다.
3. 각 Paper 서버의 `plugins/`에 **Paper JAR**을 넣습니다. `plugins/VarStore/config.yml`의 `network-id`는 같게, `server-id`는 서버마다 다르게 설정합니다.
4. Paper 프로세스에 `VARSTORE_JDBC_URL`, `VARSTORE_DB_USER`, `VARSTORE_DB_PASSWORD`를 전달하고 `/varstore status`가 `READY`인지 확인합니다.

원격 DB의 기본 TLS 모드는 `verify-full`입니다. 런타임 계정은 데이터 읽기·쓰기만 담당하며, 스키마 변경은 별도 DDL 계정으로 tools에서 실행합니다.

기본 설치에는 Paper JAR 하나를 사용합니다. PlaceholderAPI/Skript 기능이 필요하면 해당 애드온과 외부 플러그인을 추가하세요. API·core·postgres·cache·codec와 `-thin.jar`는 별도 Bukkit 플러그인으로 설치하지 않습니다. 프록시에 VarStore를 설치할 필요도 없습니다.

### 소스 빌드하기

[소스 저장소](https://github.com/M-LunaFarm/VarStore)를 체크아웃한 뒤 루트에서 실행합니다.

```sh
# 자신의 JDK 21 설치 경로로 바꾸세요.
export JAVA_HOME=/path/to/jdk-21
./gradlew build
```

산출물은 각 모듈의 `build/libs/`에 생성됩니다. DB 환경 변수 없이 빌드하면 DB 계약 시험은 건너뜁니다. DB까지 확인하는 방법과 모듈별 명령은 [개발 안내](docs/development.md#빌드와-테스트)에 있습니다.

## 변수 읽기 예시

소비 플러그인의 `plugin.yml`에는 `depend: [VarStore]`를 넣고 API JAR을 `compileOnly`로 참조합니다. 아래 코드는 소비 플러그인 안에서 실행하며 `playerUuid`는 조회할 플레이어의 UUID입니다.

```java
// imports: kr.lunaf.varstore.api.VarStore, kr.lunaf.varstore.api.VarKey
VarStore store = getServer().getServicesManager().load(VarStore.class);
if (store == null) throw new IllegalStateException("VarStore service missing");

var level = VarKey.longKey("level");
store.ready()
    .thenCompose(ignored -> store.namespace("myrpg").network()
        .player(playerUuid).getOrDefault(level, 0L))
    .whenComplete((value, error) -> {
        if (error != null) {
            getLogger().warning("레벨 조회 실패");
            return;
        }
        getLogger().info("저장된 레벨: " + value);
    });
```

`0L`은 값이 없을 때만 사용합니다. DB 장애는 오류로 전달됩니다. 플레이어·월드에 접근할 때는 [PaperSessions](docs/integrations.md)를 사용해 메인 스레드와 현재 접속을 확인하세요. 게임 스레드에서 `join()`이나 `get()`으로 기다리지 않습니다.

쓰기·CAS·보상 트랜잭션은 [기본 API](docs/api.md#소비-api)에, 실행 가능한 예제는 [examples/](examples/)에 있습니다. 커밋 결과가 불명확한 쓰기는 **원래 작업 ID와 같은 내용**으로 확인·재시도합니다.

## 어디를 수정하면 되나요?

| 바꿀 내용 | 시작 위치 |
| --- | --- |
| 서버 기본 설정 | [config.yml](varstore-paper/src/main/resources/config.yml) |
| 관리자 명령어 | [AdminCommand.java](varstore-paper/src/main/java/kr/lunaf/varstore/paper/AdminCommand.java) |
| 공개 저장 API | [varstore-api](varstore-api/src/main/java/kr/lunaf/varstore/api/) |
| 저장 실행·복구 로직 | [varstore-core](varstore-core/src/main/java/kr/lunaf/varstore/core/) |
| PostgreSQL 쿼리·새 마이그레이션 | [varstore-postgres](varstore-postgres/src/main/) |
| 특정 모듈의 의존성·전용 빌드 작업 | 해당 모듈의 `build.gradle.kts` |
| Paper·JDBC·JUnit 등 외부 의존성 버전 | [libs.versions.toml](gradle/libs.versions.toml) |

변경 흐름과 모듈별 테스트 위치는 [개발과 수정 안내](docs/development.md)에 정리했습니다.

## HuskSync와 어떤 차이가 있나요?

[HuskSync](https://github.com/WiIIiam278/HuskSync)는 인벤토리·체력 등 플레이어 데이터를 서버 사이에 동기화하고, DB와 Redis를 사용합니다. VarStore는 소비 플러그인이 지정한 변수와 트랜잭션을 PostgreSQL에 저장하며 Redis가 필요하지 않습니다. HuskSync의 Custom Data API도 함께 검토한 내용은 [관련 프로젝트 비교](docs/comparison.md)에 있습니다. ([공식 설치 문서](https://william278.net/docs/husksync/setup), [Custom Data API](https://william278.net/docs/husksync/custom-data-api))

검증 기록의 실행 조합은 Java 21·Paper 1.21.11 build 132·PostgreSQL 18·BungeeCord 2093입니다. VarStore는 전체 인벤토리 동기화, 프록시/Folia 어댑터, REST API를 제공하지 않습니다. 세부 검증 범위와 과거 성능 수치는 [검증 문서](docs/testing.md)에서 확인하세요.

[MIT 라이선스](LICENSE). API 버전과 DB 스키마 버전은 별도로 관리합니다. 배포 ZIP에는 `docs/`와 검증 기록이 포함됩니다. 소스 링크·Gradle·scripts 명령을 사용하려면 소스 저장소를 체크아웃하세요.
