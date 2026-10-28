## Global Constraints

- 버전 `1/3/0` → versionCode 10300, `1.3.0`(debug `1.3.0-dev`), 화면 `V1.3.0`. 브랜치 `feature/video-v1.3.0`. CHANGELOG `## [1.3.0] - 개발 중`.
- minSdk 30, target/compileSdk 37, JVM 17, 수동 DI(`AppContainer`), DI·이미지·영상·차트 라이브러리 금지. MapLibre는 메인 스레드(`MapSnapshotter`도 메인 스레드에서 만들고 시작).
- 수치(스펙 그대로): 24 fps; 길이 짧게 30초·보통 60초·길게 120초; 시작 3초(0~1.5초 전체 지도+제목, 1.5~3초 내려가기); 끝 4초(마지막 1초 어두워짐); 사진 2.5초(0.4·1.7·0.4); 사진 상한 4/8/15; 카메라 기울기 55°, zoom 15.5, 150 m 앞, 방향 변화 초당 최대 60°; 지나온 선 두께 6, 남은 선 두께 4·불투명도 0.35; 숫자 띠 속도는 앞뒤 10초 평균, 심박 ±30초; 여러 날 자막 1.5초; 사진 크기 화면 폭 80%; 인코딩 H.264 약 8 Mbps, 키프레임 1초, 1080×1920 / 1920×1080(실패 시 720×1280 / 1280×720); 음악 AAC 128 kbps 44.1 kHz 스테레오, 마지막 2초 페이드; 예상 시간 장당 기본 0.25초, 재사용 프레임 0.03초, 분 올림·최소 1분; 저장 공간 예상 = 8 Mbps × 길이 × 1.2; 배터리 경고 < 20% 충전 안 함; 대상 구간 = 끝난 구간 중 거리 ≥ `MIN_RIDE_DISTANCE_M`(10 m).
- 문구(스펙 그대로): "영상 만들기", "이미지 만들기", "화면 비율", "세로 9:16", "가로 16:9", "길이", "짧게(약 30초)", "보통(약 1분)", "길게(약 2분)", "사진 넣기", "숫자 표시", "경로 색", "기본", "심박", "배경음악", "없음", "음악 고르기", "약 N분 걸립니다", "만들기", "N일째 · M월 d일", "지도를 받으려면 인터넷이 필요합니다", "영상 만드는 중 N% · 약 M분 남음", "취소", "영상을 만들었습니다", "이미 영상을 만들고 있습니다", "기록 중에는 만들 수 없습니다", "기록이 시작되어 영상 만들기를 멈췄습니다", "배터리가 부족합니다(N%). 계속할까요?", "계속", "영상을 만들지 못했습니다. 다시 만들까요?", "다시 만들기", "닫기", "저장 공간이 부족합니다(약 N MB 필요)", "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?", "음악 없이 만들기", "이 휴대폰은 720p로 만들었습니다", "공유", "갤러리에서 보기", "만든 영상·이미지", "bikeRecoder". 날짜·시각 형식은 기존(`M월 d일 (E)`, "오후 8:49").
- Room: DB 5, `AutoMigration(4, 5)`(표 추가만), `5.json` 커밋. 기존 표·열 변경 금지.
- 저장: 영상 `Movies/bikeRecoder`, 이미지 `Pictures/bikeRecoder`, 이름 `bikeRecoder_yyyyMMdd_HHmm(.mp4|.png)`(여행 첫 대상 구간 시작 시각, 서울 시간), 같은 이름이면 `_2`, `_3`…, `IS_PENDING` 1로 쓰고 끝나면 0. 새 권한 없음; 포그라운드 서비스 종류 `mediaProcessing`(API 35+) / `dataSync`(그 아래) 선언과 해당 `FOREGROUND_SERVICE_*` 권한만.
- 테스트: JVM JUnit5, 기기 JUnit4 + `createComposeRule`. **기기 규칙: connected 테스트는 S22 `R5CT43TFS9A`에서만**(`ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN> </dev/null`, 한 번에 클래스 하나, 먼저 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`, 휴대폰 설정 변경 금지). **S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.** 기기 테스트가 만든 갤러리 파일은 테스트가 끝나면 지운다.
- 커밋 끝 `Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>`. 사용자에게 보이는 글은 한국어.


# Controller rulings in force
- V1.2.x rulings (R1–R21, P1–P2) remain; see docs/superpowers/sdd-state/*/progress.md.
- User-facing text Korean only. Commit trailer names the model that wrote the commit.
- V1: TripTotals defined in Task 5 (video/VideoInputs.kt): distanceM, movingTimeMs, avgSpeedMps, maxSpeedMps, ascentM, avgBpm?, calories?; reuse existing trip totals/heart-rate helpers.
- V3: photos with moveMsAtTime == null attach to moveMs 0 (before first point) / totalMoveMs (after last point). V4: 'N일째' counts ride days.
- Carry (Task 5): MapSnapshotter pixelRatio must be 1 at output size (CameraPath.overview assumes 512-px tiles, ratio 1), or lower overview zoom by log2(ratio).
- V5: CameraPath.overview(points, widthPx, heightPx) exists; renderers fit to the actual box/encoder size (map box in summary image, 720p fallback).
- V6: PendingJob gets outputUri (Task 6) so a forced-kill pending gallery item is deleted on retry/dismiss. Task 7 prunes trip_media only when exists()==false (never on exception).
- V7/carry: frame Canvas is software → Task 5 draws only software Bitmaps (ImageDecoder.ALLOCATOR_SOFTWARE); MapSnapshotter bitmaps must be copied to ARGB_8888 software if hardware. Task 6 calls AudioTrackMaker.make + VideoEncoder.addAudio BEFORE the first drawFrame.
- Carry (Task 6): call VideoEncoder.close() only from the job coroutine's finally (never concurrently with drawFrame); after an encoder failure check free space to show '저장 공간이 부족합니다(약 N MB 필요)'.
- Carry (Task 6): MUST call CameraPath.cameras(plans, timeline, CameraPath.overview(points, w, h), FrameRenderer.outroCamera(inputs, w, h)) with the encoder's actual w/h (incl. 720 fallback) — the default outro=overview compiles but silently restores the hidden-route bug. Use VideoMaps (container.offlineMaps); deleteVideoRegions in finally+NonCancellable; loadPhoto = PhotoThumbnails.full(id, 2048, software = true); downloadForTrack false → NO_INTERNET.
