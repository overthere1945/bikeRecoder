## Global Constraints

- 버전: `verMajor/verMinor/verPatch = 1/2/0` → versionCode 10200, versionName `1.2.0`(debug `1.2.0-dev`), 화면 `V1.2.0`. 브랜치 `feature/trips-v1.2.0`.
- minSdk 30, target/compileSdk 37, JVM 17. 수동 DI(`AppContainer`), DI·이미지 로딩·차트 라이브러리 금지(썸네일은 `ContentResolver.loadThumbnail`). MapLibre는 `AndroidView`·메인 스레드에서만.
- 스레드: `RideController`·`NavigationController`와 그 세션은 `rideScope` 한 스레드(R12) 그대로. 후처리는 새 `postScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`에서 돌고 Room·MediaStore·Health Connect만 만진다. 기록 중인 구간(`endedAt == null`)은 절대 처리하지 않는다.
- Room: DB 버전 4, `exportSchema = true`, `autoMigrations = [1→2, 2→3, 3→4]`(3→4는 표 추가만), `app/schemas/.../4.json` 커밋.
- 수치(스펙 그대로): 사진 창 = [구간 시작 − 5분, 구간 끝 + 5분], 카메라 폴더 상대 경로 `DCIM/Camera/`, 사진 다시 찾기·심박 다시 읽기 한도 = 구간 끝 + 24시간, 보간 간격 한도 30초, 심박 자세함 = 샘플 수 ≥ 이동 시간(분), 심박 색 연결 ±30초, 요약 화면 심박 다시 읽기 1분마다 최대 15분, 단순화 허용 오차 10 m, 누적 선 불투명도 0.35, 미리보기 지름 40 dp.
- 심박 색(스펙 §3.3): `< 100` 파랑 `#1E88E5`, `100–119` 초록 `#43A047`, `120–139` 노랑 `#FDD835`, `140–159` 주황 `#FB8C00`, `≥ 160` 빨강 `#E53935`, 샘플 없음 회색 `#9E9E9E`.
- Health Connect: 출처 패키지 `com.sec.android.app.shealth`, 권한 `READ_HEART_RATE`, `READ_EXERCISE`, `READ_TOTAL_CALORIES_BURNED`, 운동 종류 BIKING. 사진 권한 `READ_MEDIA_IMAGES`, `READ_MEDIA_VISUAL_USER_SELECTED`, `ACCESS_MEDIA_LOCATION`.
- 문구(스펙 그대로): "사진 N장" / "숨긴 사진 N장" / "숨기기" / "다시 보이기" / "주행 중 찍은 사진을 경로에 넣으려면 사진 접근을 허용해 주세요" / "선택한 사진만 찾을 수 있습니다. 모든 사진을 허용하면 자동으로 찾습니다" / "허용" / "나중에" / "설정 열기" / "닫기" / "심박 가져오는 중" / "심박 기록 없음" / "심박을 가져오지 못했습니다" / "다시 가져오기" / "평균 심박 {avg} · 최고 {max} bpm · {kcal} kcal"(칼로리 없으면 "· – kcal") / "워치에서 자전거 운동을 시작하면 자세한 심박이 기록됩니다" / "워치 심박을 가져오려면 Health Connect 접근을 허용해 주세요" / "Health Connect를 사용할 수 없습니다" / "심박은 주행 기록에만 쓰고 휴대폰 밖으로 보내지 않습니다" / "기본" / "심박" / "누적 지도" / "총 거리 · 주행 {n}회 · 총 이동 시간"(숫자 채움) / "전체" / "아직 기록이 없습니다. 주행을 기록하면 여기에 쌓입니다" / "사진·심박 연결" / "가져오지 못했습니다" / "다시 시도". 날짜 표시 "10월 18일 오후 2:31"(`M월 d일 a h:mm`, 한국어).
- 테스트: core·app JVM은 JUnit5(`org.junit.jupiter`), 기기는 JUnit4 + `androidx.compose.ui.test.junit4.v2.createComposeRule`. core `./gradlew :core:test --tests '<FQCN>'`, app `./gradlew :app:testDebugUnitTest --tests '<FQCN>'`.
- **기기 규칙**: connected 테스트는 앱을 지워 그 휴대폰의 기록이 사라진다 → **S22 `R5CT43TFS9A`에서만**, `ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN> </dev/null`(한 번에 클래스 하나), 실행 전 `adb devices` 확인과 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`, 휴대폰 설정 변경 금지. **S25 `R3CY903ZYQV`에는 아무것도 하지 않는다**(설치도 컨트롤러가 사용자 요청 때만). S22가 없거나 잠겨 있으면 `:app:assembleDebugAndroidTest`로 컴파일만 하고 "기기 검증 대기". 기기 테스트는 진짜 갤러리·Health Connect 대신 가짜 `PhotoSource`·`HeartRateSource`.
- 커밋 끝에 `Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>`. 기존 테스트는 모두 그대로 통과(동작이 바뀌어 고치는 테스트는 Task가 이름을 밝힌다).
- 스펙 보정(컨트롤러 판정 P2): 사진 찾기 실패는 화면에 오류 줄을 두지 않고 다음 실행 때 조용히 다시 찾는다(`photoScanDone`을 false로 둠). "가져오지 못했습니다"는 심박(FAILED)에만 쓴다(스펙 §5.2 보정).
- 스펙 보정(컨트롤러 판정 P1): 심박 그래프는 기존 속도·고도 그래프와 같은 **거리 축**(`ChartPoint(distanceKm, value)`)을 쓴다(스펙 §3.3 "시간 축"을 대신함 — 세 그래프를 같은 축으로 비교).


# Controller rulings in force
- R1: `PostStore.segmentsNeedingWork(now: Long)` — hr PENDING always counts; hr FAILED only while now <= endedAt + HrRetryPolicy.GIVE_UP_MS (applies to Task 3 signature and Task 5 caller).
- R2: `HeartRateSource.read` returns `HrRead.Empty` (never `Samples(emptyList())`) when there are no samples.
- User-facing text Korean only. Commit trailer names the model that wrote the commit.
- R3: `HrRetryPolicy.shouldTry`: PENDING → always true (even past 24 h, so Empty can become NONE); FAILED → only while now <= endedAt + GIVE_UP_MS; READY/NONE → false.
- R4: `segmentsNeedingWork` returns newest first (startedAt DESC).
- R5: manifest gets `READ_EXTERNAL_STORAGE` with `android:maxSdkVersion="32"`; on API 30–32 granted READ_EXTERNAL_STORAGE = PhotoAccess.FULL (and the permission card requests it there instead of READ_MEDIA_*).
- R6: heart rate requires only READ_HEART_RATE (hasPermissions() checks that one); calories are read only if READ_EXERCISE and READ_TOTAL_CALORIES_BURNED are granted, else null. The permission request still asks all three.
- R7: [다시 가져오기] that fails or reads Empty on a READY segment keeps the stored heart rate; only hrLastTriedAt changes.
- R8: Health Connect not AVAILABLE or READ_HEART_RATE missing → PostProcessor leaves hrStatus PENDING (updates hrLastTriedAt only); FAILED only for an actual read error (HrRead.Failed from a permitted, available source). UI (Task 7): when permission is missing or HC unavailable, the heart-rate row shows the permission card / "Health Connect를 사용할 수 없습니다" instead of "심박 가져오는 중".
- R9: "구간 요약" means BOTH the record-screen summary and the guided-ride summary in NavigateScreen (both render SegmentSummaryContent). Both show photo count + photo card + request() on appear (Task 6), and the heart-rate row/card + 1-min refetch up to 15 min (Task 7). Prefer one shared summary helper over duplicating logic in two VMs.
- R10: trip-detail map draws each segment as a separate line (no connector between segments). In HEART_RATE mode each segment is coloured from its own samples.
- R13: a photo belongs to exactly one segment of its trip (containing segment, else nearest boundary, tie → earlier).
- R14: FAILED is retried until max(endedAt, hrFirstTriedAt) + 24 h (shouldTry and segmentsNeedingWork).
- R15: photoScanDone is set only when photo access is FULL.
