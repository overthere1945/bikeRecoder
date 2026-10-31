# Task 6 part A 보고 — 버전·문서

**상태:** DONE (part B는 컨트롤러 몫)
**커밋:** `321c2e3 docs: V1.5.0 version, changelog, README and verification` (4cae699 위, push 안 함)

## 바꾼 파일
- `app/build.gradle.kts`: verMinor 5 (versionCode 10500, debug `1.5.0-dev`)
- `app/src/test/.../AppVersionTest.kt`: 1.5.0-dev, 10500, `AppVersion.display("1.5.0-dev")` 한 줄 추가
- `app/src/androidTest/.../ui/settings/SettingsScreenTest.kt`: 기대 문구 "V1.5.0"
- `CHANGELOG.md`: `## [1.5.0] - 개발 중` (추가 / 변경 / 문서). 변경에 Room 7판(shareText/shareStamp, 자동 이전), 설정 "공유 → 기본 해시태그", `<queries>` 페이스북·인스타그램·이메일 포함
- `README.md`: 현재 버전 V1.5.0(개발 중), 로드맵 V1.5.0 / V1.6.0 GPX 가져오기(예정), 설정 탭 행, 문서 목록
- `docs/VERSIONING.md`: V1.5.0 — 개발 중, V1.6.0 GPX 가져오기 — 예정
- `docs/verification/share-field-test.md` (새 파일): JVM 결과, S22 자리 "최종 수정 뒤 기록", 이 버전의 판정, S25 점검표 15항목

## 점검표
스펙 §7.3의 10항목에 지시받은 6개를 모두 넣었다(인스타 피드·스토리가 파일을 들고 열림 = #2, 페이스북 = #1, 붙여 넣기 = #6, 만드는 중 미리보기 게이트 = #11, 나갔다 돌아오면 이어서 보내기 = #12, 안내 요약 [공유] 순서 = #13). 추가로 #3(스토리는 세로), #14(단추 세 곳·숨김), #15(앱 아이콘·숨김)를 더해 모두 15항목이다. 브리프의 "10항목"보다 많다.

## 시험
- JVM `./gradlew :core:test :app:testDebugUnitTest --rerun-tasks`: BUILD SUCCESSFUL 2m 34s. JUnit XML 집계: core 44 클래스 387, app 89 클래스 1060, 실패 0, 건너뜀 0. 합계 133 클래스 1447.
- S22 `R5CT43TFS9A` `ui.settings.SettingsScreenTest`: 16/16 통과 (화면을 깨우고 `ANDROID_SERIAL`, `</dev/null`). S25에는 아무것도 하지 않음.
- 컴파일 경고는 이전부터 있던 것뿐(GlyphIcon 1, PostProcessorTest opt-in 11).

## 메모
- `secrets.json`은 출력·커밋하지 않았다. `.superpowers/`는 커밋하지 않았다.
