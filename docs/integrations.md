# Paper 세션과 애드온

[README로 돌아가기](../README.md) · [개발과 수정 안내](development.md)

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

scalar 4타입 read/set/delete, 원자적 LONG add, 제한된 prefix keys를 제공합니다. 수정 구문은 `operation "<영속 UUID>"`를 필수로 받습니다. 전체 구문과 타입·목록 예제는 [`scripts/fixtures/varstore-addon.sk`](../scripts/fixtures/varstore-addon.sk)에 있으며 `@..._ID@`는 시험 실행기가 사건 UUID로 치환하는 토큰입니다. 운영 스크립트에서 같은 문자열을 다른 사건의 ID로 반복 사용하지 않습니다.

결과 local list는 `status/value/error/operation/cursor/keys::*`를 구분합니다. 읽기는 VALUE/ABSENT/FAILED, 쓰기는 APPLIED/NO_CHANGE/CONDITION_FAILED/FAILED이며 실패를 미설정으로 바꾸지 않습니다. 저장 후 continuation은 비동기 완료를 기다렸다가 메인 스레드에서 재개하고 오래된 플레이어 세션·unload된 스크립트에서는 중단합니다. 이미 지나간 이벤트를 continuation에서 취소할 수 없습니다. 이벤트 취소·잔액 승인처럼 즉시 결정이 필요한 로직은 따로 설계해야 합니다.

`FAILED`는 비동기 결과를 확인하지 못했다는 뜻이며 DB 롤백의 증거가 아닙니다. 특히 `UNKNOWN_COMMIT_OUTCOME`이면 원래 `operation`과 동일한 요청을 보존해 확인·재전송하고 새 ID로 바꾸지 마세요.
