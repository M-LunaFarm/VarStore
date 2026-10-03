# 운영과 복구

[README로 돌아가기](../README.md) · [개발과 수정 안내](development.md)

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
4. [설치 문서의 스키마 2 테이블·시퀀스 권한](setup.md#설치)을 런타임 계정에 부여하고 `validate`합니다.
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

[실행 가능한 CSV 샘플](../scripts/fixtures/import-example.csv)을 제공합니다. UTF-8 CSV의 정확한 헤더는 다음과 같습니다. 최대 8MiB·10,000행이며 scalar 4타입만 지원합니다.

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
