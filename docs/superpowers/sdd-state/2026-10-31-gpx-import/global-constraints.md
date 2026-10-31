## Global Constraints

- **문구:** 사용자에게 보이는 글은 모두 한국어이고 스펙 §5 문구를 그대로 쓴다. 문구 상수는 app `importing/ImportFormat.kt` 한 곳에 둔다.
- **시각:** 날짜·시각·"아침/저녁" 구분은 Asia/Seoul. GPX 시간은 ISO-8601(`Z`, `+09:00` 모두)을 `OffsetDateTime`/`Instant`로 읽는다.
- **주행 기준:** 10 m 이상(`MIN_RIDE_DISTANCE_M`), 채택 점 2개 이상.
- **제한 값:** 파일·항목 하나 50,000,000 바이트, ZIP 항목 2,000개, 한 번에 주행 500개(`ImportLimits` 기본값).
- **다시 계산:** `RecordingSession(startMillis = 첫 점 시간, autoPause = true)`, `onFix(fix, nowMillis = fix.timeMillis)`, `stop(마지막 점 시간)`. 사용자 자동 일시정지 설정은 보지 않는다.
- **바꾸지 않는 것:** `GpxParser.parse`의 동작(기존 시험·재생 호출), `RecordingSession`, `PostProcessor`의 규칙, 영상·공유 기능.
- **권한:** 새 권한 없음. 파일은 SAF URI와 공유 grant로만 읽는다.
- **기기 시험:** S22 `R5CT43TFS9A`에서만, 클래스 하나씩, 화면을 먼저 깨우고 `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`. S25 `R3CY903ZYQV`에는 아무것도 하지 않는다. 휴대폰 설정을 바꾸지 않는다.
- **비밀:** `secrets.json` 출력·커밋 금지.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **스타일:** 주석 언어는 파일마다 기존을 따른다(core는 한국어 KDoc, app `ui/`는 영어 KDoc). core 시험은 `org.junit.jupiter` + `kotlin.test`.

