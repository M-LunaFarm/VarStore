# 설치와 설정

[README로 돌아가기](../README.md) · [개발과 수정 안내](development.md)

처음 설치할 때는 **배포 파일 → 설치 → 설정 확인** 순서로 진행하세요. 개발용 로컬 DB는 [개발 안내](development.md#로컬-db로-계약-시험하기)를 참고하세요.

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

## 설정 확인

실제 설정은 `plugins/VarStore/config.yml`, 배포 기본값은 [소스 config.yml](../varstore-paper/src/main/resources/config.yml)에 있습니다. 기본 설정을 바꿀 때는 [PaperConfiguration](../varstore-paper/src/main/java/kr/lunaf/varstore/paper/PaperConfiguration.java)의 검사도 함께 확인합니다.

| 설정 | 언제 바꾸나요? |
| --- | --- |
| `network-id` | DB를 공유하는 논리 네트워크를 정할 때. 같은 네트워크의 서버들은 같은 값 |
| `server-id` | 개별 서버를 구분할 때. 같은 네트워크에서 서버마다 다른 값 |
| `storage.*-env` | JDBC URL·사용자·비밀번호를 읽을 **환경 변수 이름**을 바꿀 때 |
| `storage.tls-mode` | 원격은 기본 `verify-full`, 개발용 비TLS DB만 `disable` |
| `storage.maximum-pool-size`, `execution.*` | 연결·실행·대기 한도를 조정할 때. timeout은 양의 정수 + `ms`/`s`/`m` |
| `cache.enabled` | 명시적인 표시 캐시 서비스를 사용할 때. 기본은 `false` |
| `shared-namespaces` | 특정 플러그인이 자기 이름 외의 namespace를 공유할 때 |

`storage.driver/schema-mode/durability-check`, `limits.*`, `operations.*`는 현재 릴리스의 고정 계약입니다. 값만 바꿔 DB 종류·크기 한도·보존 정책을 확장할 수 없으며 시작 시 검사가 거절합니다. 설정을 바꾼 뒤 서버를 재시작하고 `/varstore status`와 로그를 확인하세요. `/reload`는 지원하지 않습니다.
