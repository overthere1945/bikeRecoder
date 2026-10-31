## Global Constraints

- **문구:** 사용자에게 보이는 글은 모두 한국어이고 스펙 §5 문구를 그대로 쓴다. 앱 화면 문구는 `app/.../ui/elevation/ElevationText.kt`(고도·띠·경로 미리보기)와 `app/.../export/ExportText.kt`(내보내기) 두 곳, 음성 문구는 core `KoreanPhrases`, GPX·KML 안의 글은 core 작성기 안 상수.
- **경사 7단계:** 스펙 §1.3 표의 경계·색·범례 글 그대로(`GradeBand`). 경계: `< -6` STEEP_DOWN, `-6 ≤ g ≤ 2` FLAT, `2 < g ≤ 4` UP_2, `4 < g ≤ 6` UP_4, `6 < g ≤ 8` UP_6, `8 < g ≤ 10` UP_8, `> 10` UP_10.
- **상수:** 다시 고르기 10 m(전체 50 km 이하)/20 m, 다듬기 50 m, 경사 폭 100 m, 단면 최소 50 m·고도 점 2개, 오르막 묶음 3%·끼는 평지 50 m 미만·최소 200 m·평균 4.0%, 평소 속도 최근 10구간·1 km 이상·기본 15 km/h, 보정 0.12/0.03·하한 6 km/h·상한 1.3배, 앞길 2,000 m, 음성 300 m(250~350 m), 심박 ±5초.
- **시각:** 파일 이름·KML 날짜는 Asia/Seoul, GPX `<time>`은 UTC `yyyy-MM-dd'T'HH:mm:ss'Z'`.
- **바꾸지 않는 것:** DB 스키마(8판), 기록 엔진 `RecordingSession`·`AscentCounter`, 회전 안내·1 km 안내 규칙, 영상·공유·가져오기.
- **권한:** 새 권한 없음(MediaStore Downloads, minSdk 30).
- **기기 시험:** S22 `R5CT43TFS9A`에서만, 클래스 하나씩, 화면을 먼저 깨우고 `ANDROID_SERIAL=R5CT43TFS9A`와 `</dev/null`. S25 `R3CY903ZYQV`에는 아무것도 하지 않는다. 휴대폰 설정을 바꾸지 않는다. 시험이 만든 다운로드 파일은 시험이 지운다.
- **비밀:** `secrets.json` 출력·커밋 금지.
- **커밋:** 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **스타일:** 주석 언어는 파일마다 기존을 따른다(core는 한국어 KDoc, app `ui/`는 영어 KDoc). core 시험은 `org.junit.jupiter` + `kotlin.test`.

