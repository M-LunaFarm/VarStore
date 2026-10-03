# 테스트와 검증 기록

[README로 돌아가기](../README.md) · [개발과 수정 안내](development.md)

## 시험 실행

기본 빌드와 DB 계약 시험 준비는 [개발 안내](development.md#빌드와-테스트)를 따릅니다. 아래 명령은 소스 저장소 루트에서 실행합니다.

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

## 기존 검증 자료를 읽는 법

아래 수치는 저장소에 보존된 릴리스 당시 기록입니다. 현재 체크아웃을 다시 시험한 결과와 구분하세요.

## 1.3.0 확장 검증

검증 환경은 Paper 1.21.11 build 132, BungeeCord 2093, Java 21, PostgreSQL 18입니다. 선택 애드온은 Skript 2.16.1과 PlaceholderAPI 2.12.3에서 실행했습니다. 루프백의 실제 서버와 봇을 이용한 기능 시험이며 접속자 규모별 TPS 보장은 아닙니다.

| 검증 | 근거 |
| --- | --- |
| 실제 DB를 포함한 전체 자동 계약 | 112개 통과, 실패·건너뜀 0: [`contracts.json`](../verification/contracts.json): 테스트별 이름·결과·소스 해시 |
| 1.0 데이터 업그레이드 | 변수 300,188행·작업 20,153행·감사 27행·네트워크 67행의 전체 행 집계 해시와 기존 V001 이력 보존: [`upgrade-v1-v2.json`](../verification/upgrade-v1-v2.json) |
| 실제 Paper 11개 항목 | 4타입 Skript·오류·목록, 캐시 무효화, 실제 DB 단절, 진행 중 쓰기와 서버 이동, 재접속·스크립트 unload, Codec, 애드온 없는 재시작: [`paper-extension.json`](../verification/paper-extension.json) |
| 게임 스레드 I/O | 관측 구간의 메인 스레드 JDBC·애플리케이션 파일 I/O·Future 대기 0건. 최초 클래스 로딩의 JAR 읽기는 별도 기록: [`extension-io-probe.json`](../verification/extension-io-probe.json) |
| 커밋 전후 연결 단절 | COMMIT 전달 전 연결 종료 및 실제 COMMIT 응답 폐기, 동일 ID 재시도와 변수·outbox 중복 방지: [`commit-response.json`](../verification/commit-response.json) |
| 강제 종료·백업 복원·반복 단절 | 실제 DB SIGKILL, 전체 DB 복원과 epoch 차단, 183.217초 동안 DB 중단·복구 10회. 고유 작업 ID 3,133개와 최종 증가값 일치: [`recovery.json`](../verification/recovery.json), [`outage.json`](../verification/outage.json) |
| 운영 도구 | 용량·증가량 표본, 4타입 CSV dry-run, 기존 주소 충돌 거절: [`operator-tools.json`](../verification/operator-tools.json) |
| outbox 비용·캐시·자원 회수 | 동일 하네스의 1.0/1.3 비교와 180초 확장 부하: [`extensions-load.json`](../verification/extensions-load.json) |

정확한 실행 바이너리 해시와 기능별 근거는 [`artifacts.json`](../verification/artifacts.json), [`release-audit.json`](../verification/release-audit.json)에 있습니다. 기존 대규모 Paper 부하 수치는 아래의 1.0 기록에만 해당합니다. 새 비교 시험은 공유 호스트에서 단일 작업자가 순차 쓰기한 결과이며, WAL 증분은 **PostgreSQL 클러스터 전체**의 카운터라 다른 DB와 백그라운드 활동도 포함합니다. 유한한 자원 관측은 장기 누수 부재를 증명하지 않습니다.

동일 Java 하네스로 200회 예열 후 실제 값이 바뀌는 LONG 쓰기 2,000회를 순차 실행했습니다. 구독자가 없는 새 스키마를 사용하고 1.0 다음 1.3 순서로 측정했습니다. 1.3은 예열을 포함한 변경 2,200건에 outbox 이벤트 2,200개를 생성했습니다.

| 런타임 | p50 / p95 / p99 (ms) | 측정 쓰기 시간 | 클러스터 WAL 증분 |
| --- | ---: | ---: | ---: |
| 1.0.0 / 스키마 1 | 16.264 / 40.930 / 76.422 | 38.922초 | 2,339,024바이트 |
| 1.3.0 / 스키마 2 | 18.677 / 48.519 / 95.451 | 45.457초 | 3,802,344바이트 |

180.027초의 확장 부하에서는 변경 쓰기·고유 전달 이벤트가 각각 2,347건, 캐시 호출이 37,800건이었고 주입한 소비자 실패 93건을 재전달했습니다. 안정된 값 1,000회 조회는 직접 조회에서 DB 읽기 1,000회, 캐시에서 1회였습니다. 구독 생성·종료 17회와 로딩 중 무효화를 포함했으며, 종료 후 캐시 핸들·항목·바이트, 연결·대기 슬롯·보유 요청 바이트·VarStore 플랫폼 스레드는 모두 0이었습니다. 임시 구독은 0행, 재시작용 durable 구독은 의도대로 1행을 남겼습니다. 종료 후 GC 기준 힙 증가는 307,312바이트였습니다.

검증 중 발견한 실패도 보존했습니다. 최초 Skript 표현식 처리, 메인 스레드의 난수 장치 읽기, 구독 종료와 outbox 전달 생성의 경쟁 조건은 각각 수정 후 재검증했습니다. 병행 빌드 중 발생한 500ms DB 잠금 시간초과와, 캐시 첫 읽기에 항상 값이 있다고 가정했던 CI 테스트 실패도 별도 기록했습니다. CI 로그에는 캐시 상태가 남지 않아 구체적인 발생 순서는 단정하지 않습니다. 캐시 테스트는 무효화와 겹친 STALE만 제한적으로 다시 읽도록 수정했으며 운영 시간초과나 오류 의미를 바꾸지 않았습니다. [`diagnostic-extension-initial/`](../verification/diagnostic-extension-initial/), [`diagnostic-extension-jfr/`](../verification/diagnostic-extension-jfr/), [`diagnostic-extension-churn/`](../verification/diagnostic-extension-churn/), [`diagnostic-build-contention/`](../verification/diagnostic-build-contention/)에서 확인할 수 있습니다.

재현 도구: `scripts/paper-extension-smoke.py`, `scripts/commit-fault-test.py`, `scripts/recovery-test.py`, `scripts/outage-test.py`, `scripts/extensions-load.py`. 장애·JFR·성능 시험을 동시에 실행하지 마세요. 실제 기본 DB 중단까지 포함하는 Paper 시험은 전용 시험 컨테이너에서 `VARSTORE_TEST_MAIN_DB_OUTAGE=true`로 실행합니다.

## 1.0.0의 기존 실행 기록

이 절의 수치·JAR 해시·45개 시험 결과는 **확장 전 1.0.0 기록**입니다. outbox·캐시·Codec·애드온이 추가된 1.3.0의 검증이나 성능 결과로 대체해서 읽지 마세요. 기존 실행 조합은 다음과 같습니다. 테스트·부하·복구 결과는 [`verification/`](../verification/)의 JSON 및 CSV로 공개합니다.

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
| 자동 계약·경계·권한·교착 재시도 | 45개 통과, 실패·건너뜀 0: [`contracts.json`](../verification/v1.0.0/contracts.json) |
| 커밋 응답 차단 | 실제 COMMIT 응답을 버린 뒤 동일 ID로 원 결과 확인, 값 1 유지: [`commit-response.json`](../verification/v1.0.0/commit-response.json) |
| Paper 기능·스레드 | 재시작·이동·두 소비 플러그인·관리자 CAS, 별도 JFR 관측 구간에서 메인 스레드 JDBC I/O 0건: [`paper-smoke.json`](../verification/v1.0.0/paper-smoke.json), [`paper-io-probe.json`](../verification/v1.0.0/paper-io-probe.json) |
| DB 강제 종료·백업 복원 | 성공한 값 복구, 작업 기록 동시 복원, 이전 epoch 거절: [`recovery.json`](../verification/v1.0.0/recovery.json) |
| 반복 장애 | 180.571초 동안 10회 중단·재연결, 고유 작업 3,277개와 최종 증가값 일치: [`outage.json`](../verification/v1.0.0/outage.json) |

반복 장애 시험에는 실제 UNKNOWN_COMMIT_OUTCOME 1건이 포함되며 같은 ID로 복구했습니다. 종료 후 연결·대기 요청·전달 슬롯·보유 요청 바이트·VarStore 플랫폼 스레드는 모두 0입니다. 유한한 관측 구간에서 확인한 결과이며 장기 누수 부재나 운영 RPO/RTO 보장은 아닙니다. 복구 연습의 DB 재시작은 3.508초, 백업 복원·epoch 전환은 1.427초였으며 작은 로컬 시험 DB에서 측정했습니다.

T01–T22와 추가 검증의 근거 연결은 [`1.0.0 release-audit.json`](../verification/v1.0.0/release-audit.json), 당시 실행한 JAR의 해시는 [`1.0.0 artifacts.json`](../verification/v1.0.0/artifacts.json)에 있습니다.

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

별도 클라이언트가 **같은 16키에 최대 크기 트랜잭션을 집중**한 단계에서는 쓰기 600건 중 525건이 오류(시간초과 195, 저장소 사용 불가 323, 커밋 결과 불명확 7)로 끝났고, 직후 동일 키 단계에서도 회복 전 24건의 오류가 기록됐습니다. 이는 측정한 처리 한계이며 성공으로 집계하지 않습니다. 고정된 처리량을 보장하지 않으며, 이런 집중 부하에는 요청량 제한과 동일 작업 ID를 통한 결과 확인이 필요합니다. 원시 오류·응답·자원 기록은 [`load-report.json`](../verification/load-report.json), [`load-requests.csv`](../verification/load-requests.csv), [`load-resources.csv`](../verification/load-resources.csv), [`load-environment.json`](../verification/load-environment.json)에 있습니다.

원시 표본과 재현 방법: [`load-paper-summary.json`](../verification/load-paper-summary.json), [`paper-benchmark/`](../verification/paper-benchmark/), `python3 scripts/aggregate-load.py`. 이전 SNAPSHOT의 예열 전·JFR 동시 수집·공유 트랜잭션 키 측정도 [`diagnostic-initial/`](../verification/diagnostic-initial/)에 별도 보존했습니다. 코드와 측정 조건이 달라 성능 개선의 원인을 단일 요인으로 해석하지 않습니다.

1.3.0의 캐시·outbox·애드온·Codec는 위의 과거 부하 수치에 포함되지 않습니다. TTL·DB 네이티브 JSON/BYTES·프록시 JVM/Folia 어댑터는 후속 범위입니다. Redis·다른 DB 엔진·REST·웹 UI·자동 객체 수집·전체 인벤토리 동기화·네트워크 단일 작성자 소유권은 제공하지 않습니다.

## 공식 설계 근거

- [Paper 프로젝트 구성](https://docs.papermc.io/paper/dev/project-setup/) 및 [Java 지원 표](https://docs.papermc.io/paper/getting-started/)
- [Paper 스케줄러](https://docs.papermc.io/paper/dev/scheduler/)와 [플러그인 메시징](https://docs.papermc.io/paper/dev/plugin-messaging/)
- [PostgreSQL READ COMMITTED](https://www.postgresql.org/docs/18/transaction-iso.html)와 [명시적 잠금](https://www.postgresql.org/docs/18/explicit-locking.html)
- [PostgreSQL WAL 설정](https://www.postgresql.org/docs/18/runtime-config-wal.html) 및 [백업](https://www.postgresql.org/docs/18/backup.html)

MIT 라이선스. API 버전과 스키마 버전은 별도로 관리합니다. 이름은 이 저장소의 프로젝트명이며 상표 독점이나 외부 이름 충돌 부재를 보장하지 않습니다.
