# 활동 종류 V1.9.0 검증 기록

설계 스펙(`docs/superpowers/specs/2026-11-01-activity-types-design.md`) §7(검증)에 따른 기록 문서입니다. 활동 종류(자전거·걷기·달리기·등산), 종류별 기록 기준·숫자·음성, 등산 일몰 안내, 종류 색·배지·카드, 기록 뒤 종류 바꾸기, 통계 탭 종류 칩·목표·개인 기록, 영상·공유·GPX의 종류, DB 9판을 다룹니다.

> **경고**: `connectedDebugAndroidTest`는 끝나면 앱을 지워 그 휴대폰의 기록이 사라집니다.
> connected 테스트는 S22 `R5CT43TFS9A`에서만, S25 `R3CY903ZYQV`에는 아무것도 하지 않습니다(설치는 사용자가 요청할 때 DB 백업과 개수 비교 뒤에).

## 자동 검증

### PC 단위 테스트

실행일: 2026-11-01 (브랜치 `feature/activity-types-v1.9.0`, 구현 커밋 `f1b42c1` 위에 버전 1.9.0을 올린 상태)

명령:

```bash
./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test
```

결과: BUILD SUCCESSFUL

| 모음 | 클래스 수 | 테스트 수 | 실패 | 건너뜀 |
|---|---|---|---|---|
| `:core:test` | 69 | 659 | 0 | 0 |
| `:app:testDebugUnitTest` | 114 | 1375 | 0 | 0 |
| `:routing-brouter:test` | 4 | 19 | 0 | 0 |
| 합계 | 187 | 2053 | 0 | 0 |

수는 JUnit XML(`*/build/test-results/{test,testDebugUnitTest}/TEST-*.xml`)에서 셌습니다. `:core:test`는 core 코드가 이전 실행과 같아 Gradle이 `UP-TO-DATE`로 건너뛰었고(버전만 올렸으므로 입력이 같음), 위 수는 그 직전 실행의 XML입니다. V1.8.0 기록(core 542, app 1233)에 비해 core가 117개, app이 142개 늘었습니다.

스펙 §7.1의 PC 확인 범위:
- `ActivityProfile`과 엔진: 종류마다 자동 일시정지·재개 경계값 위·아래, 튐 거르기, GPS 끊김 규칙. 자전거 회귀(`RecordingSessionTest`, `AutoPauseDetectorTest`, `TrackFilterTest`, `RideStatsCalculatorTest` 그대로 통과, 같은 점이면 V1.8.0과 같은 합계)
- 종류 바꾸기 다시 계산(`ActivityChange`): 1.5 km/h 등산 점으로 자전거는 이동 시간이 줄고 등산은 거의 남음, 위치 점 수 불변, 최소 조건 못 넘으면 합계 그대로
- `ActivityFormat`: `5'32"`, 59.6초 → `6'00"`, 1 km/h 미만과 60분 넘음 → `--'--"`
- `SpeedBand` 종류별 경계값(달리기 60/7, 10, 12, 15 km/h 등)
- `BestEfforts`: 일정 속도 5 km의 1 km 시간, 빠른 1 km 찾기, 끝 점 넘친 거리 보간, GPS 끊김 제외, 거리 모자라면 없음, 10만 점 1초 안
- `SunTimes`: 서울(하지·동지·춘분 무렵)·제주, 극지방 → null
- `HikeAnnouncer`: 30분·60분 안내, 일몰 2시간·1시간·30분 전과 일몰 시각, 한 번만, 지난 시점 건너뛰고 시작 안내에 남은 시간, 2분 안 겹침(P13), 일시정지 중에도
- 음성 문장: 종류별 1 km 문장(P3), `평균 시속`, 등산 시간 읽기(P4)·고도 모를 때(P5), 시작 문장
- 자동 제목: 종류 낱말, 섞인 여행의 대표 종류, 여러 날 여행
- 통계·목표·기록: 종류 거르기와 [전체], 연속 주, 목표 키 옮기기, 종류별 기록과 비교 범위, 신기록 줄
- `UsualSpeed`: 걷기가 섞여도 자전거 값만
- GPX: 종류 읽기(running·walking·hiking·없음), `NotCycling` 없어짐, 내보내기 `<type>`과 `<trk>` 묶기(P8)
- 공유: 종류별 숫자 줄과 해시태그 키
- 앱 단위 시험(뷰모델): 칩 기억, 길안내 기록은 자전거(P14), 종류 바꾸기 흐름, 통계 칩
- 버전: `AppVersionTest`(`1.9.0-dev`, versionCode 10900, 표시 `V1.9.0`)

### 기기 테스트 (Galaxy S22 Ultra `R5CT43TFS9A`, SM-S908N, Android 16 / API 36)

connected 테스트는 S22 `R5CT43TFS9A`에서만 실행합니다. 시리얼 없이 실행하면 연결된 모든 휴대폰에서 돌고 앱을 지우므로 항상 `ANDROID_SERIAL=R5CT43TFS9A`를 붙입니다. 실행 전 `adb devices`로 확인하고

```bash
adb devices
adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP
ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<클래스> </dev/null
```

**컴파일 확인** (2026-11-01): `./gradlew :app:assembleDebugAndroidTest` 성공(androidTest 코드가 컴파일됨).

**버전 올린 직후 확인**: `ui.settings.SettingsScreenTest`는 이 시점에 S22가 연결되어 있지 않아(`adb devices`에 기기 없음) 돌리지 못했습니다. 최종 수정 뒤 전체 시험 때 `V1.9.0` 표시와 함께 확인합니다.

#### 최종 수정 뒤 기록

(최종 검토와 수정이 끝난 뒤 S22에서 한 클래스씩 돌려 여기에 적습니다. 날짜, 커밋, 통과 수를 적을 자리입니다.)

- 실행일 / 커밋:
- `app/src/androidTest` 전체(클래스 수 / 테스트 수 / 통과 / 건너뜀):
- 이번 버전에서 더하거나 고친 클래스(스펙 §7.2):
  - `data.RideDatabaseMigrationTest` (`migrate8To9`: 기존 구간이 모두 `BIKE`, 데이터 그대로, 빈 `segment_best_effort` 표)
  - `data.RoomImportStoreTest`
  - `data.RoomRideStoreTest`
  - `data.SettingsRepositoryGoalsTest`
  - `data.SettingsRepositoryShareHashtagsTest`
  - `ride.RideScenarioTest`
  - `ui.main.ActivityChipsTest` (지도 화면 종류 칩: 고른 칩 색, 기억, 낭독)
  - `ui.record.RideNumbersPageTest` (달리기 페이스 칸, 등산 큰 숫자 2개와 일몰 줄, 경고 색)
  - `ui.record.RecordScreenTest`
  - `ui.feed.FeedCardActivityTest` (카드 배지와 종류 색, 섞인 여행의 겹친 배지)
  - `ui.feed.FeedScreenTest`
  - `ui.history.ChangeActivityTest` ([⋮] 종류 바꾸기 창과 안내 문구, 바꾼 뒤 합계)
  - `ui.history.HistoryFixtures` (공용 시험 자료)
  - `ui.stats.StatsActivityTest` (칩, 종류별 칸, 목표 줄, 기록 소제목)
  - `ui.stats.StatsScreenTest`
  - `ui.importing.ImportScreenTest` (GPX 확인 목록 종류 배지와 바꾸기)
  - `ui.settings.SettingsScreenTest` (`V1.9.0`, 종류별 기본 해시태그 줄)
  - `video.FrameRendererTest`
- 이전 버전 클래스 전체 회귀: 모든 androidTest 클래스를 하나씩
- 스크린샷(`Download/bikeRecoderTest/`)

| 파일 | 만드는 시험 | 확인 |
|---|---|---|
| `v190-chips.png` | (지도 화면 종류 칩, 최종 수정 뒤 정함) | |
| `v190-hike-numbers.png` | `RideNumbersPageTest` | |
| `v190-run-numbers.png` | `RideNumbersPageTest` | |
| `v190-feed-cards.png` | `FeedCardActivityTest` | |
| `v190-stats-hike.png` | `StatsActivityTest` | |
| `v190-change-activity.png` | `ChangeActivityTest` | |

- 시험용 기록 사본(`s22-seed2/bikerecoder.db`)을 다시 넣어 DB가 9판으로 옮겨지는지, 지도 칩·피드 카드·구간 화면 종류 바꾸기·통계 칩이 보이는지:

## 알려진 한계

- 걷기·등산 길안내와 경로 탐색은 없습니다(길안내는 자전거 전용, V1.12.0의 GPX 따라가기로 다룸).
- 일몰 시각은 NOAA 식으로 휴대폰 안에서 계산합니다(고도 보정 없음). 시험의 2026-03-20 서울·제주 값은 미국 해군천문대(USNO) 값과 맞춰 본 것이고 한국천문연구원(KASI) 출몰시각과는 아직 대조하지 못했습니다. 실제 산에서 일몰 줄이 맞는지는 점검표 3번에서 봅니다.
- 기록을 마친 뒤 종류를 바꿀 때 일시정지 중에 저장되지 않은 위치는 되살릴 수 없습니다. 이미 만든 영상·이미지 파일은 그대로 둡니다.
- 기록 중에는 종류를 바꿀 수 없습니다.
- 앱 이름·아이콘·첫 화면과 앱 곳곳의 "주행" 문구는 그대로입니다(V2.0.0).
- 공유 카드 고르기·투명 스티커·산 이름 해시태그(V1.10.0), 영상 카메라·지명 팻말(V1.11.0)은 이 버전에 없습니다.
- 기기 시험의 큰 지도 스크린샷에는 바탕 지도 타일이 없을 수 있습니다(V1.7.0과 같음).

## 이 버전에서 정한 판정

화면이나 동작이 달라지는 규칙 가운데 실사용 때 기준으로 삼을 것입니다. 계획의 P1~P16과 구현 중 정한 판정을 포함합니다.

- **요약 피드 카드 3번째 칸**: 자전거는 `평균 속도`입니다(이전 `상승`). 스펙 §2.2.
- **카드 지도 경로 색**: 활동 종류 색을 따릅니다. 자전거 선 색이 `#D85A30`으로 바뀌고, 업데이트 뒤 피드 카드 그림이 한 번 다시 그려집니다.
- **자전거 1 km 음성**: `평균 시속`이라고 말합니다. "그날 합계"는 그날 그 여행의 BIKE 구간만 더합니다(P2).
- **기록을 마친 요약의 위 3칸**: 자전거를 포함해 모든 종류가 스펙 §2.2를 따릅니다.
- **영상 제목 카드**: 자전거를 포함해 모든 종류에 종류 배지가 보입니다.
- **영상 마지막 카드의 횟수 칸**: 자전거가 아닌 여행은 `활동 N회`입니다.
- **GPX 가져오기**: 달리기·걷기·등산 파일은 막지 않고 확인 목록에서 처음부터 체크됩니다. 목록에서 종류를 바꿔도 줄의 거리·시간은 읽을 때의 값이고, 가져오기 작업이 고른 종류로 다시 계산합니다(P9).
- **걷기 페이스 그래프 범례**: km/h 구간 이름을 그대로 둡니다.
- **그래프 이름**: 모든 구간이 `RUN`이거나 모두 `WALK`이면 `페이스`, 두 종류 이상이 섞이면 `속도`(km/h)이고 범례 자리에 `종류마다 색 기준이 다릅니다`(P7).
- **배지 순서**: 대표 종류가 먼저이고 나머지는 종류별 이동 시간 합이 큰 순서, 최대 3개(P6).
- **길안내 중 시작한 기록**: 넘겨받은 종류와 관계없이 `BIKE`입니다(P14).
- **피드 이번 주 줄의 목표 막대**: 자전거 목표와 자전거 거리를 씁니다(P10).
- **기록을 마친 요약의 목표 줄**: 그 구간 종류의 목표와 그 종류의 구간만 씁니다(P16).
- **GPX 내보내기**: 이웃한 같은 종류 구간을 한 `<trk>`로 묶고, 종류가 바뀔 때마다 새 `<trk>`입니다(P8).
- **DB 9판**: `AutoMigration` 8→9. 기존 구간은 모두 `BIKE`가 됩니다.
- **하프 기록**: 저장 키 `distanceM = 21098`, 계산 목표 21,097.5 m입니다(P12).

## 실사용 점검표 (V1.9.0, 스펙 §7.3)

기기: Galaxy S25 `R3CY903ZYQV` (Android 17). 설치 전 DB 백업과 기록 개수 비교를 합니다.

| # | 항목 | 합격 기준 | 결과 | 메모 |
|---|---|---|---|---|
| 1 | 기존 기록 | 10/18·10/25 기록이 자전거 배지로 보이고 숫자·제목·통계가 업데이트 전과 같음 | | |
| 2 | 걷기 | 실제 걷기 한 번: 신호에 멈추면 자동 일시정지, 다시 걸으면 재개, 페이스와 1 km 음성이 자연스러움 | | |
| 3 | 등산 | 짧은 등산(또는 오르막 걷기) 한 번: 산행 시간·고도 큰 숫자, 30분 안내, 일몰 줄이 실제 일몰 시각과 맞음 | | |
| 4 | 종류 바꾸기 | 걷기 기록을 달리기로 바꾸면 합계가 다시 계산되고 제목·카드가 바뀜 | | |
| 5 | 통계 | 칩별로 숫자가 나뉘고, 자전거 목표·기록이 업데이트 전과 같음 | | |

## 판정

- PC 테스트와 기기 테스트(최종 수정 후 전체)가 통과하고 위 5개 항목이 모두 합격이면 `CHANGELOG.md`의 V1.9.0을 확정하고(「개발 중」 표기 제거) 태그를 붙입니다.
- 불합격 항목은 원인 수정과 회귀 테스트 추가 뒤 그 항목만 다시 확인하고, 여기에 재검증 결과를 덧붙입니다.
- S25는 사용자가 요청할 때 DB 백업과 기록 개수 비교 뒤에 설치합니다.
