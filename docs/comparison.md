# HuskSync와 관련 프로젝트 비교

[README로 돌아가기](../README.md) · [개발 안내](development.md)

**인벤토리·체력 등 Minecraft 플레이어 데이터를 자동으로 옮기는 목적과, 플러그인의 변수를 공유 저장하는 목적을 구분하면 선택이 쉬워집니다.** 아래는 2026-10-03에 공식 설명·설치 문서·공개 소스를 확인한 기능 비교입니다. 동일 환경에서 성능이나 장애 내성을 비교 시험한 결과는 아닙니다.

## 기능과 저장 방식

| 항목 | VarStore | HuskSync | MySqlPlayerBridge |
| --- | --- | --- | --- |
| 주된 용도 | 플러그인의 타입 변수·조건부 변경·트랜잭션 | 서버 간 플레이어 데이터 동기화 | 모듈별 플레이어 데이터 동기화 |
| 기본 데이터 | STRING / LONG / BOOLEAN / UUID, 명시적 Codec 객체 | 인벤토리·엔더 상자·체력 등 플레이어 snapshot | 인벤토리·경험치·위치 등 선택 모듈 |
| 저장 인프라 | PostgreSQL Primary | DB + Redis | 공유 MySQL |
| 커스텀 데이터 경로 | 소비 플러그인이 key와 owner를 지정해 비동기 API 호출 | Custom Data API로 serializer를 등록해 snapshot에 포함 | 공개 저장소의 모듈 구조 확인 |
| 이 저장소에서 구현한 쓰기 처리 | 커밋 확인·작업 ID 보존·CAS·bounded transaction | snapshot 저장·동기화 중심의 API | 플레이어 데이터 저장·동기화 모듈 중심 |

HuskSync의 데이터 범위는 [공식 저장소](https://github.com/WiIIiam278/HuskSync), DB와 Redis 요구사항은 [설치 문서](https://william278.net/docs/husksync/setup), 커스텀 저장은 [Custom Data API](https://william278.net/docs/husksync/custom-data-api)에서 확인했습니다. MySqlPlayerBridge의 MySQL·선택 모듈·관리 기능은 [개발자 저장소](https://github.com/Lostes-Burger/MySqlPlayerBridge)를 기준으로 작성했습니다. 이 프로젝트는 brunyman의 `MySQL Player Data Bridge`와 이름이 비슷한 별도 프로젝트입니다.

다른 프로젝트의 작업 ID·트랜잭션 보장이 VarStore와 같다고 가정하지 않습니다. 필요한 데이터의 저장 시점과 실패 처리는 각 프로젝트의 API·구현에서 따로 확인해야 합니다.

## HuskSync Custom Data API도 확인한 이유

HuskSync는 기본 인벤토리 데이터 외에도 serializer를 등록해 커스텀 데이터를 `DataSnapshot`에 저장하고 동기화할 수 있습니다. 따라서 커스텀 데이터가 플레이어 snapshot과 함께 이동해야 하는 경우 검토할 수 있습니다. ([공식 Custom Data API](https://william278.net/docs/husksync/custom-data-api))

VarStore는 플레이어뿐 아니라 system owner와 network/server scope를 지정해 데이터를 읽고 씁니다. 플레이어 접속이나 이동을 기다리지 않고 퀘스트 진행도를 증가시키거나 여러 변수의 조건을 하나의 트랜잭션으로 검사할 수 있습니다. API의 주소·버전·작업 ID 계약은 [기본 API](api.md)에 있습니다.

이 차이를 기준으로 보면 인벤토리·체력 자동 동기화에는 HuskSync 같은 플레이어 동기화 도구가, 플러그인의 점수·설정·조건부 변경에는 VarStore API가 용도에 맞는 후보입니다. 이는 공개된 기능과 현재 소스를 바탕으로 한 판단입니다. 두 도구를 함께 쓸 경우 각 데이터의 저장 책임과 서버 이동 시 처리를 정해야 하며, 이 저장소에는 HuskSync 연동 어댑터나 함께 운영한 검증 기록이 없습니다.

## 이번 수정에 반영한 구성

HuskSync의 README는 기능·설치·개발을 짧게 소개하고 별도 문서에서 API와 설정을 설명합니다. 이 구성을 참고해 VarStore도 시작 문서와 상세 참조를 분리했습니다. ([HuskSync README](https://github.com/WiIIiam278/HuskSync))

- README에서 설치·빌드·짧은 API 예시와 목적별 문서 링크를 먼저 보여줍니다.
- 개발 문서에 수정할 기능 → 모듈 → 주요 클래스 → 확인할 시험을 연결합니다.
- 각 모듈의 빌드 설정을 해당 폴더에 두고 외부 의존성 버전은 Gradle version catalog에 모읍니다.
- 기존 저장 계약·복구 절차·성능 근거는 상세 문서에 보존합니다.

## 공식 자료

- [HuskSync 소스와 README](https://github.com/WiIIiam278/HuskSync)
- [HuskSync 설치](https://william278.net/docs/husksync/setup)
- [HuskSync API](https://william278.net/docs/husksync/api)
- [HuskSync Custom Data API](https://william278.net/docs/husksync/custom-data-api)
- [MySqlPlayerBridge 소스와 README](https://github.com/Lostes-Burger/MySqlPlayerBridge)

Minecraft별 배포 파일과 지원 버전은 각 프로젝트의 현재 호환성 표에서 확인하세요.
