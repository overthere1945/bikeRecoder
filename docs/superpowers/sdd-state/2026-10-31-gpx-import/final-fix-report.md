# V1.6.0 최종 수정 보고 (final-fix-wave 1–9)

브랜치 `feature/gpx-import-v1.6.0`, 시작 01e91e4. 푸시하지 않음.

| 커밋 | 내용 |
|---|---|
| `4a5ed69` | app: import — final review fixes (OOM guards, picked-file grants, safe delete-all, tests) |
| `a874dbe` | core: import — RideReplay keeps a ride of 10 m or more (boundary test) |
| `39ce061` | docs: V1.6.0 — GpxReader changelog line, known limits, field-test item 3 note |

## 1. I1: OOM과 스택 넘침 막기
- `ImportScanner.kt:63`: 생성자에 `parse: (ByteArray) -> GpxTrack = GpxReader::read`를 더함. 시험에서 가짜 파서를 넣기 위한 것이고 기본값은 그대로임.
- `ImportScanner.kt:122-142` `summarizeGpx`:
  - `parse`에서 `OutOfMemoryError`가 나면 TOO_LARGE, `StackOverflowError`가 나면 UNREADABLE (`:131`, `:133`).
  - `RideReplay.replay`도 같은 방식으로 감쌈 (`:137-142`).
  - 다른 예외 처리(GpxReadException, CancellationException 다시 던지기, Exception은 UNREADABLE)는 그대로임.
- `GpxImportJob.kt:62`: 같은 `parse` 매개변수를 더함.
- `GpxImportJob.kt:199-213` `replay()`: OOM이나 SOE가 나면 경고 로그를 남기고 null을 돌려줌. 그 주행은 건너뛰고 작업은 계속됨.
- `ImportReader.kt:224-228` `walk`: 읽기 자체에서 난 `OutOfMemoryError`는 `Unreadable(TOO_LARGE)` 하나가 됨. visitor 안에서 난 것(`inVisitor`)은 그대로 다시 던짐.
- KDoc을 고침: `ImportScanner.kt:48-55`, `ImportReader.kt:188-194`.
- `Throwable`을 넓게 잡지 않음. `CancellationException`은 지금처럼 다시 던짐.
- 시험:
  - `ImportFakes.kt:71`의 `FakeImportReader.readFailures`: 정한 URI의 스트림이 읽을 때 지정한 오류를 던짐.
  - `ImportScannerTest.outOfMemoryIsTooLargeAndScanGoesOn` (`:147`): 파서 OOM은 TOO_LARGE, 파서 SOE는 UNREADABLE, 스트림 OOM은 TOO_LARGE가 됨. 다음 파일 `next.gpx`는 New 줄로 읽힘.
  - `GpxImportJobTest.outOfMemoryRideIsSkippedAndRunContinues` (`:202`): 파서 OOM, 파서 SOE, 스트림 OOM 주행은 건너뛰고 마지막 주행은 저장됨. 결과는 `Finished(1, 3, false)`이고 뒷정리는 1회.
  - `ImportReaderTest.walkReadOutOfMemoryIsOneTooLargeItemButVisitorOutOfMemoryPropagates` (`:168`).
  - 고치기 전에는 `parse` 매개변수가 없어 컴파일이 실패함(red).

## 2. M1: 체크한 줄이 있는 파일만 붙들기
- `GpxImportJob.kt:104-106`의 `pickedFiles()`는 `picks.map { it.key.sourceIndex }.distinct().mapNotNull(sources::getOrNull)`임.
- 이것을 hold(`:84`)와 release(`:131`)에 씀. 클래스 KDoc도 고침.
- 시험:
  - `GpxImportJobTest.holdsAndReleasesUris` (`:184`): picks가 c.gpx와 b.zip이면 `[sources[2], sources[1]]`만 hold하고 release함. a.gpx는 붙들지 않음. 예전 코드라면 실패함.
  - 이 변경 때문에 `ImportViewModelTest.importCheckedStartsJobWithPicks` (`:199`)의 기대값을 `listOf(listOf(b, a))`로 바꿈. 체크하지 않은 run.gpx는 붙들지 않고, 순서는 최근 순임.

## 3. M2: 더 안전한 모두 지우기
- 실제 열을 확인함:
  - `TripEntity.status`는 enum name 문자열(`'COMPLETED'`)임.
  - `RideSegmentEntity.endedAt`은 `Long?`이고 null이면 열린 구간임.
  - `trip_import.tripId`는 CASCADE임.
- `Daos.kt:376-385`에 `ImportDao.deleteCompletedImportedTrips()`를 새로 둠:
  `DELETE FROM trip WHERE id IN (SELECT tripId FROM trip_import) AND status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM ride_segment WHERE ride_segment.tripId = trip.id AND ride_segment.endedAt IS NULL)`
  - 지운 행 수를 돌려줌. 자식 행은 CASCADE로 지워짐.
  - `trip_import` 행이 없는 여행은 건드릴 수 없음.
- `RoomImportStore.kt:55-61` `deleteAllImported()`는 이 쿼리 하나만 부름. 이제 쓰이지 않는 `importedTripIds()`는 지움.
- 시험: `RoomImportStoreTest.deleteAllImportedSkipsImportedTripsThatAreNotCompletedOrAreRecording` (androidTest `:136`).
  - 상황: 끝난 여행, status를 ACTIVE로 바꾼 여행, 열린 구간이 있는 여행을 하나씩 가져옴.
  - 기대: 1개만 지워지고, 나머지 둘과 그 구간, import 행은 남음.
  - red: 예전 코드로 S22에서 돌리면 `expected:<1> but was:<3>`로 실패함.
  - green: 고친 뒤 8/8 통과함. 기존 `deleteAllImportedKeepsRecordedTrips`도 통과함.

## 4. M4: 짧은 주행 경계
- `RideReplayTest.tenMetresOrMoreIsKept` (core `:104`): 두 점 10.2 m짜리 주행은 null이 아님. 점은 2개이고 거리는 `MIN_RIDE_DISTANCE_M` 이상, +1 m 미만임.
- 고치기 전부터 맞는 동작을 확인하는 시험이라 처음부터 통과함.

## 5. 문서 (한국어)
- `CHANGELOG.md:22`: "가져오기용 GPX 읽기 `GpxReader`를 새로 더함 … `GpxParser`와는 `secureDocumentBuilderFactory`만 같이 쓰고, `GpxParser.parse`는 바꾸지 않음"으로 고침.
- 알려진 한계 두 줄을 더함. 너무 큰 GPX는 "너무 큰 파일입니다"로 나올 수 있다는 것과, [종료]하지 않은 기록이 있으면 그 뒤가 모두 "이미 있는 주행과 겹칩니다"로 나오므로 먼저 [종료]하라는 것임.
  - `CHANGELOG.md:29-30`
  - `docs/verification/gpx-import-field-test.md:75-76`
  - 문구는 `ImportFormat.kt`의 `PROBLEM_TOO_LARGE`, `IMPORT_OVERLAPS_EXISTING`과 같음.
- 점검표 3번(`gpx-import-field-test.md:96`) 메모 칸: 파일 앱 위에 따로 창으로 열리는지와 최근 앱 목록에서 어떻게 보이는지 적는 칸을 더함.

## 6. KDoc
- `ImportViewModel.kt:80`: "up to 128 grant calls and 256 file opens"로 고침.
- `ImportViewModel.kt:77-78`: 클래스 KDoc에 "files가 넘어왔으면 `uiState`는 읽기로 시작하고 `Closed`로 시작하지 않는다"는 문장을 더함.
- `ImportScanner.kt:48-55`: 긴 줄을 120자 안으로 줄바꿈함.

## 7. `MainActivityImportIntentTest`
- `tearDown` (`:69-83`): 네 단계를 단계마다 `runCatching`으로 돌림. 네 단계는 scenario 닫기, handoff 비우기, 파일 지우기, 온보딩 설정 되돌리기임. 앞 단계가 실패해도 나머지가 돌고, 첫 오류를 던지며 나머지 오류는 suppressed로 붙임.
- `gpxAndOctetStreamResolveToMainActivity` (`:155`):
  - 액션 SEND와 VIEW, 타입 `application/gpx+xml`과 `application/octet-stream`의 네 조합을 시험함.
  - data는 content URI이고, SEND에는 EXTRA_STREAM도 넣음.
  - `PackageManager.queryIntentActivities(…, MATCH_DEFAULT_ONLY)` 결과에 MainActivity가 있는지 확인함.
- S22에서 4/4 통과함.

## 8. `ImportHandoff`
- `ImportIntentTest.handoffGivesTheListOnceThenNull` (`:60`): put하기 전에는 null, put한 뒤 첫 take는 목록, 두 번째 take는 null임.

## 9. 다시 검증
- `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL. 결과 XML에서 센 수는 다음과 같음.
  - core: 48개 클래스, 443개 시험(+1), 실패 0
  - app: 96개 클래스, 1127개 시험(+4), 실패 0
- S22 `R5CT43TFS9A`에서만 돌림. 클래스 하나씩, 매번 WAKEUP과 `ANDROID_SERIAL`, `</dev/null`을 씀.
  - `com.cowork.bikerecoder.data.RoomImportStoreTest`: 8/8 통과
  - `com.cowork.bikerecoder.importing.MainActivityImportIntentTest`: 4/4 통과
- S25는 건드리지 않음. 휴대폰 설정은 바꾸지 않음. `secrets.json`은 출력하지도 커밋하지도 않음.

## 우려·메모
- 시험에서 OOM을 일으키려고 `ImportScanner`와 `GpxImportJob`에 `parse` 생성자 매개변수(기본 `GpxReader::read`)를 더함. 실제 앱 생성 코드(`AppContainer`, `ImportViewModel` factory)는 바뀌지 않음.
- 저장 단계(`store.saveImported`)에서 나는 OOM은 항목 1의 범위가 아니어서 잡지 않음.
- 모두 지우기는 이제 ACTIVE 상태이거나 기록 중인 가져온 여행을 남기고, 지운 개수만 돌려줌. 설정의 "가져온 기록 N개"는 남은 여행 수만큼 계속 보임. 이 경우는 정상 흐름에서는 생기지 않음.
- 도움말과 CHANGELOG에 이 조건을 따로 적지는 않음. 항목 5의 범위가 아니어서임.
- 문서 `gpx-import-field-test.md`의 PC 시험 수 표(442/1123)는 그대로 둠. 항목 범위 밖이고, 최종 S22 전체 시험 때 함께 갱신할 것.
