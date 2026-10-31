## Global Constraints

- **문구:** 사용자에게 보이는 글은 모두 한국어다. 스펙 §6 문구는 그대로 쓴다.
- **시각과 숫자:**
  - 날짜·시각은 Asia/Seoul 기준이다.
  - 주행은 "10 m 이상 끝난 구간"이다(`StatsCalculator.rides`, `MIN_RIDE_DISTANCE_M`).
  - 숫자 형식은 기존 함수를 쓴다: `kmText`, `meterText`, `RideFormat.duration`, `groupedInt`.
- **메타 연동 금지:** 메타(페이스북) SDK·앱 ID를 넣지 않는다. `com.instagram.share.ADD_TO_STORY`도 쓰지 않는다.
- **권한:** 새 권한을 추가하지 않는다. 패키지 보기는 `<queries>`만 쓴다.
- **바꾸지 않는 것:** `VideoJob`의 규칙·모양, 미리보기 화면의 [공유].
- **기기 시험:**
  - S22 `R5CT43TFS9A`에서만 돌린다.
  - 클래스 하나씩, 화면을 먼저 깨우고, `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`을 붙인다.
  - S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.
  - 휴대폰 설정을 바꾸지 않는다.
  - 실제 SNS에 올리지 않는다(인텐트 내용만 확인).
- **비밀:** `secrets.json`은 출력·커밋 금지다.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`을 붙인다.
- **스타일:** 주석 언어는 파일마다 기존을 따른다(app `ui/`는 영어 KDoc, core는 한국어).

