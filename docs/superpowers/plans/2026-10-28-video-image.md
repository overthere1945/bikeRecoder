# 영상과 요약 이미지 (V1.3.0) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 완료된 여행으로 휴대폰 안에서 Relive식 3D 영상(MP4)과 요약 이미지(PNG)를 만들어 갤러리에 저장하고 공유한다.

**Architecture:** 무엇을 그릴지는 `:core`의 `core.video`(순수 Kotlin, JUnit5)가 프레임마다 `FrameSpec`으로 정한다. 앱의 `video` 패키지가 `MapSnapshotter`로 3D 지도를 찍고 Canvas로 덧그린 뒤 `MediaCodec`/`MediaMuxer`로 MP4(+AAC 음악)를 만들고, 포그라운드 서비스 `VideoService`가 준비(경로 주변 지도 받기)→그리기→인코딩→저장(MediaStore)→기록(Room v5 `trip_media`)을 돈다. 화면은 옵션 시트·진행·미리보기와 진입 버튼(요약·여행 기록), 여행 기록의 "만든 영상·이미지" 줄.

**Tech Stack:** 기존(Kotlin 2.4.20, AGP 9.4.1, Compose BOM 2026.09.00, MapLibre 13.6.1, Room 2.8.5, DataStore, coroutines). 새 외부 라이브러리 없음(영상 재생은 `VideoView`, 인코딩은 플랫폼 `MediaCodec`).

**Spec:** `docs/superpowers/specs/2026-10-28-video-image-design.md`

## Global Constraints

- 버전 `1/3/0` → versionCode 10300, `1.3.0`(debug `1.3.0-dev`), 화면 `V1.3.0`. 브랜치 `feature/video-v1.3.0`. CHANGELOG `## [1.3.0] - 개발 중`.
- minSdk 30, target/compileSdk 37, JVM 17, 수동 DI(`AppContainer`), DI·이미지·영상·차트 라이브러리 금지. MapLibre는 메인 스레드(`MapSnapshotter`도 메인 스레드에서 만들고 시작).
- 수치(스펙 그대로): 24 fps; 길이 짧게 30초·보통 60초·길게 120초; 시작 3초(0~1.5초 전체 지도+제목, 1.5~3초 내려가기); 끝 4초(마지막 1초 어두워짐); 사진 2.5초(0.4·1.7·0.4); 사진 상한 4/8/15; 카메라 기울기 55°, zoom 15.5, 150 m 앞, 방향 변화 초당 최대 60°; 지나온 선 두께 6, 남은 선 두께 4·불투명도 0.35; 숫자 띠 속도는 앞뒤 10초 평균, 심박 ±30초; 여러 날 자막 1.5초; 사진 크기 화면 폭 80%; 인코딩 H.264 약 8 Mbps, 키프레임 1초, 1080×1920 / 1920×1080(실패 시 720×1280 / 1280×720); 음악 AAC 128 kbps 44.1 kHz 스테레오, 마지막 2초 페이드; 예상 시간 장당 기본 0.25초, 재사용 프레임 0.03초, 분 올림·최소 1분; 저장 공간 예상 = 8 Mbps × 길이 × 1.2; 배터리 경고 < 20% 충전 안 함; 대상 구간 = 끝난 구간 중 거리 ≥ `MIN_RIDE_DISTANCE_M`(10 m).
- 문구(스펙 그대로): "영상 만들기", "이미지 만들기", "화면 비율", "세로 9:16", "가로 16:9", "길이", "짧게(약 30초)", "보통(약 1분)", "길게(약 2분)", "사진 넣기", "숫자 표시", "경로 색", "기본", "심박", "배경음악", "없음", "음악 고르기", "약 N분 걸립니다", "만들기", "N일째 · M월 d일", "지도를 받으려면 인터넷이 필요합니다", "영상 만드는 중 N% · 약 M분 남음", "취소", "영상을 만들었습니다", "이미 영상을 만들고 있습니다", "기록 중에는 만들 수 없습니다", "기록이 시작되어 영상 만들기를 멈췄습니다", "배터리가 부족합니다(N%). 계속할까요?", "계속", "영상을 만들지 못했습니다. 다시 만들까요?", "다시 만들기", "닫기", "저장 공간이 부족합니다(약 N MB 필요)", "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?", "음악 없이 만들기", "이 휴대폰은 720p로 만들었습니다", "공유", "갤러리에서 보기", "만든 영상·이미지", "bikeRecoder". 날짜·시각 형식은 기존(`M월 d일 (E)`, "오후 8:49").
- Room: DB 5, `AutoMigration(4, 5)`(표 추가만), `5.json` 커밋. 기존 표·열 변경 금지.
- 저장: 영상 `Movies/bikeRecoder`, 이미지 `Pictures/bikeRecoder`, 이름 `bikeRecoder_yyyyMMdd_HHmm(.mp4|.png)`(여행 첫 대상 구간 시작 시각, 서울 시간), 같은 이름이면 `_2`, `_3`…, `IS_PENDING` 1로 쓰고 끝나면 0. 새 권한 없음; 포그라운드 서비스 종류 `mediaProcessing`(API 35+) / `dataSync`(그 아래) 선언과 해당 `FOREGROUND_SERVICE_*` 권한만.
- 테스트: JVM JUnit5, 기기 JUnit4 + `createComposeRule`. **기기 규칙: connected 테스트는 S22 `R5CT43TFS9A`에서만**(`ANDROID_SERIAL=R5CT43TFS9A ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN> </dev/null`, 한 번에 클래스 하나, 먼저 `adb -s R5CT43TFS9A shell input keyevent KEYCODE_WAKEUP`, 휴대폰 설정 변경 금지). **S25 `R3CY903ZYQV`에는 아무것도 하지 않는다.** 기기 테스트가 만든 갤러리 파일은 테스트가 끝나면 지운다.
- 커밋 끝 `Co-Authored-By: <커밋을 쓴 모델> <noreply@anthropic.com>`. 사용자에게 보이는 글은 한국어.

## Review Focus

- **매우 긴 여행(수백 km, 지점 수만 개)이나 아주 짧은 여행(이동 20초)**: 프레임 계산이 빠르고(1초 안), 짧은 여행은 늘이지 않고 영상이 짧아진다 → Task 1 `longTripTimelineIsFast`, `shortMoveIsNotStretched`.
- **GPS 튐·제자리 회전으로 방향이 확 바뀌는 곳**: 카메라가 흔들리지 않는다(초당 60° 상한) → Task 2 `bearingChangeIsRateLimited`.
- **만드는 도중 앱이 종료되거나 기록이 시작됨**: 반쪽 파일이 갤러리에 남지 않고, 다음 실행 때 다시 만들기를 제안한다 → Task 6 `startingARecordingStopsAndDeletes`, `unfinishedJobIsOfferedAgain`.
- **고른 음악이 영상보다 짧거나, 읽을 수 없거나, 모노/48 kHz**: 끝까지만 넣거나 음악 없이 만들기를 묻고, 형식이 달라도 AAC 44.1 kHz 스테레오로 바뀐다 → Task 4 `shortMusicEndsEarly`, `monoFortyEightKhzIsConverted`.
- **사진이 없는 여행·심박이 없는 여행·여러 날 여행**: 영상·이미지가 해당 칸을 빼고 정상으로 나온다 → Task 1·2 사례, Task 5 `imageWithoutPhotosOrHeartRate`.

## 파일 구조

- `core/.../core/video/`: `VideoOptions.kt`(옵션·길이·비율), `RideTimeline.kt`(대상 구간 → 이동 시간 축 지점), `VideoTimeline.kt`(프레임 계획·사진 고르기·자막·시간 배분), `CameraPath.kt`(카메라·평활), `Hud.kt`(숫자 띠 값), `SummaryImageLayout.kt`(이미지 배치), `VideoEstimate.kt`(예상 시간·크기), `MediaNaming.kt`(파일 이름), `AudioPlan.kt`(자르기·페이드 계산)
- `app/.../video/`: `VideoEncoder.kt`, `AudioTrackMaker.kt`, `FrameRenderer.kt`, `SummaryImageRenderer.kt`, `MediaStoreWriter.kt`, `VideoService.kt`, `VideoJob.kt`, `VideoJobStore.kt`(옵션·진행 중 작업 DataStore), `VideoInputs.kt`(여행 → 렌더 입력)
- `app/.../data/`: `Entities.kt`·`Daos.kt`·`AppDatabase.kt`(v5 `trip_media`), `RoomTripMediaStore.kt`
- `app/.../offline/`: `RegionKind.VIDEO` + `downloadForTrack(tripId, points)` / `deleteVideoRegions(tripId)`
- `app/.../ui/video/`: `VideoOptionsSheet.kt`, `VideoProgressScreen.kt`(또는 시트), `MediaPreviewScreen.kt`, `TripMediaRow.kt`; 수정 `ui/history/TripDetail*`, `ui/record/SummaryExtras.kt`(요약 버튼), `ui/BikeNavHost.kt`, `AndroidManifest.xml`, `AppContainer.kt`

---

### Task 1: 영상 시간 계획 (core, 스펙 §2.1–2.5, §3.6)

**Files:** Create `core/.../core/video/VideoOptions.kt`, `RideTimeline.kt`, `VideoTimeline.kt`, `VideoEstimate.kt`; Test 같은 이름 `*Test.kt`.

**Interfaces — Produces:**
```kotlin
enum class Aspect(val width: Int, val height: Int) { PORTRAIT(1080, 1920), LANDSCAPE(1920, 1080) }
enum class VideoLength(val seconds: Int, val photoCap: Int) { SHORT(30, 4), NORMAL(60, 8), LONG(120, 15) }
enum class RouteColoring { DEFAULT, HEART_RATE }
data class VideoOptions(val aspect: Aspect = Aspect.PORTRAIT, val length: VideoLength = VideoLength.NORMAL,
    val photos: Boolean = true, val hud: Boolean = true, val coloring: RouteColoring = RouteColoring.DEFAULT, val musicUri: String? = null)
const val FPS = 24
// 대상 구간들을 "이동 시간" 축 하나로 잇는다(일시정지·구간 사이 제외).
data class RidePoint(val moveMs: Long, val point: GeoPoint, val altitudeM: Double?, val timeMillis: Long, val segmentIndex: Int, val dayIndex: Int)
class RideTimeline(points: List<RidePoint>) { val totalMoveMs: Long; fun at(moveMs: Long): Located  /* 보간 위치·고도·지나온 인덱스 */ ; fun moveMsAtTime(timeMillis: Long): Long? }
object RideTimelineBuilder { fun build(segments: List<Pair<RideSegment, List<TrackPoint>>>, zone: ZoneId): RideTimeline }  // 일시정지 = 점 사이 간격 > 10초는 이동 시간에 넣지 않음; dayIndex는 localDate 순서
data class PhotoCue(val mediaId: Long, val moveMs: Long, val takenAt: Long)
enum class SceneKind { INTRO_OVERVIEW, INTRO_DESCENT, RIDE, PHOTO, OUTRO }
data class FramePlan(val index: Int, val kind: SceneKind, val moveMs: Long, val sceneProgress: Float, val photo: PhotoCue?, val caption: String?, val fade: Float, val reuseMap: Boolean)
object VideoTimeline {
    fun choosePhotos(cues: List<PhotoCue>, cap: Int, totalMoveMs: Long): List<PhotoCue>   // 이동 시간 축 cap칸, 칸 가운데에 가장 가까운 것, 시간순
    fun plan(timeline: RideTimeline, photos: List<PhotoCue>, options: VideoOptions, dayStarts: List<Pair<Long, String>>): List<FramePlan>
}
object VideoEstimate { fun minutes(frames: List<FramePlan>, secondsPerFrame: Double = 0.25): Int; fun bytes(lengthSeconds: Double): Long /* 8 Mbps × 1.2 */ }
```
- 주행 장면 R = T − 3 − 4 − 2.5 × 사진 수; `timeline.totalMoveMs < R`이면 R = totalMoveMs(늘이지 않음). 프레임 수 = round((3 + R + 2.5·n + 4) × 24).
- 주행 프레임 i의 moveMs = i/(R·24) × totalMoveMs. 사진 장면은 그 사진 moveMs에서 멈춘 채 2.5초(`reuseMap = true` 처음 프레임 빼고). 같은 moveMs(±1초) 사진은 연속. 시작 0~1.5초와 끝 4초는 `reuseMap` 처음 빼고 true. 여러 날 자막 "N일째 · M월 d일"은 그날 첫 moveMs부터 1.5초(36프레임) 동안 RIDE 프레임에 `caption`. 끝 마지막 24프레임 `fade` 0→1.
- [ ] Step 1 실패하는 테스트: `frameCountPerLength`(사진 0장 보통 = 60×24=1440), `photosShortenRide`(보통 8장 → 주행 33초 = 792프레임), `shortMoveIsNotStretched`(이동 20초 보통 사진 0 → 3+20+4초), `speedProportional`(앞 절반 이동 시간 10분·뒤 절반 10분이면 주행 프레임 절반 지점 moveMs = 전체 절반), `pausesAndGapsExcluded`(점 간격 60초 → 이동 시간에 안 들어감, 구간 사이 시간 제외), `choosePhotosEvenly`(20장 cap 8 → 8장 고르게, 시간순), `samePlacePhotosConsecutive`, `multiDayCaption`(둘째 날 첫 지점에 "2일째 · 10월 18일" 36프레임), `introAndOutroReuseMap`, `fadeOnLastSecond`, `estimateMinutes`(1440프레임 중 재사용 200 → ⌈(1240×0.25+200×0.03)/60⌉ = 6), `estimateBytes`(60초 → 72,000,000), `longTripTimelineIsFast`(지점 50,000개 plan < 1초).
- [ ] Step 2 `./gradlew :core:test --tests 'com.cowork.bikerecoder.core.video.*'` → FAIL → 구현 → PASS.
- [ ] Step 3 Commit `core: video timeline — timing, speed-proportional mapping, photo cues, captions, estimate`

### Task 2: 카메라·숫자 띠·이미지 배치·이름·음악 계산 (core, 스펙 §2.2, §2.4, §3.1⑤, §3.5, §4)

**Files:** Create `core/.../core/video/CameraPath.kt`, `Hud.kt`, `SummaryImageLayout.kt`, `MediaNaming.kt`, `AudioPlan.kt`; Tests.

**Interfaces — Consumes:** Task 1. **Produces:**
```kotlin
data class Camera(val center: GeoPoint, val zoom: Double, val bearing: Double, val tilt: Double)
object CameraPath {
    const val TILT = 55.0; const val ZOOM = 15.5; const val LOOK_AHEAD_M = 150.0; const val MAX_TURN_DEG_PER_S = 60.0
    fun cameras(plans: List<FramePlan>, timeline: RideTimeline, overview: Camera): List<Camera>   // INTRO_OVERVIEW = overview(tilt 0); INTRO_DESCENT 보간(overview→첫 주행 카메라); RIDE 평활; PHOTO 직전 고정; OUTRO = overview
    fun overview(points: List<GeoPoint>, aspect: Aspect): Camera   // 전체 경로가 들어오는 zoom(여백 10%), tilt 0, bearing 0
}
data class HudValues(val distanceKm: Double, val speedKmh: Double, val altitudeM: Double?, val heartBpm: Int?)
object Hud { fun at(moveMs: Long, timeline: RideTimeline, heartRate: List<HeartRateSample>): HudValues }  // 속도 = 앞뒤 10초(이동 시간) 거리/시간; 심박 ±30초 가장 가까운(HeartRateColor 규칙 재사용)
data class Box(val x: Float, val y: Float, val w: Float, val h: Float)
data class ImageLayout(val header: Box, val map: Box, val stats: List<Box>, val photos: List<Box>, val footer: Box)
object SummaryImageLayout { fun layout(aspect: Aspect, photoCount: Int): ImageLayout }   // 세로: 머리·지도(≈절반)·2×3 숫자·사진 줄(0~3)·바닥; 가로: 왼쪽 60% 지도, 오른쪽 40%
object MediaNaming { fun baseName(firstStartMillis: Long, zone: ZoneId): String /* "bikeRecoder_20261025_2047" */; fun unique(base: String, ext: String, existing: Set<String>): String }
data class AudioPlan(val takeUs: Long, val fadeStartUs: Long)
object AudioPlanner { fun plan(musicDurationUs: Long, videoDurationUs: Long): AudioPlan }  // take = min(music, video); fadeStart = take − 2초(0 미만이면 0)
```
- [ ] Step 1 실패하는 테스트: `rideCameraLooksAheadOneFiftyMetres`, `bearingChangeIsRateLimited`(경로가 180° 꺾여도 프레임당 ≤ 60/24°), `descentInterpolates`, `photoFreezesCamera`, `overviewFitsAllPoints`(세로·가로), `hudSpeedTenSecondAverage`, `hudHeartWithinThirtySeconds`(31초 → null), `hudAltitudeNullWhenMissing`, `layoutPortraitAndLandscape`(박스 겹침 없음·화면 안·사진 0~3), `nameAndUnique`(같은 이름 있으면 `_2`), `audioShortMusicEndsEarly`, `audioFadeTwoSeconds`.
- [ ] Step 2 `./gradlew :core:test` → FAIL → 구현 → PASS.
- [ ] Step 3 Commit `core: camera path, HUD values, summary image layout, naming and audio plan`

### Task 3: 저장소 v5·만든 파일 기록·옵션·갤러리 저장·버전 1.3.0 (스펙 §1.2, §3.3, §3.5, §5.1)

**Files:** Modify `data/Entities.kt`, `Daos.kt`, `AppDatabase.kt`(v5), `app/build.gradle.kts`(1.3.0, 버전 테스트 `AppVersionTest`·`SettingsScreenTest` 갱신), `AppContainer.kt`; Create `data/RoomTripMediaStore.kt`, `video/VideoJobStore.kt`, `video/MediaStoreWriter.kt`, `app/schemas/.../5.json`; Tests JVM `TripMediaMappingTest`, `VideoJobStoreTest`, 기기 `RoomTripMediaStoreTest`, `RideDatabaseMigrationTest.v4DataSurvivesMigrationTo5`, `MediaStoreWriterTest`.

**Interfaces — Produces:**
```kotlin
enum class MediaKind { VIDEO, IMAGE }
data class TripMedia(val id: Long, val tripId: Long, val kind: MediaKind, val uri: String, val createdAt: Long, val optionsJson: String)
interface TripMediaStore { fun observe(tripId: Long): Flow<List<TripMedia>>; suspend fun add(m: TripMedia): Long; suspend fun remove(ids: Collection<Long>) }
class VideoJobStore(context) { val lastOptions: Flow<VideoOptions>; suspend fun saveOptions(o: VideoOptions) /* musicUri 저장 안 함 */; suspend fun secondsPerFrame(): Double?; suspend fun recordSpeed(s: Double); suspend fun setPending(p: PendingJob?); suspend fun pending(): PendingJob? }
data class PendingJob(val tripId: Long, val kind: MediaKind, val optionsJson: String, val startedAt: Long)
class MediaStoreWriter(resolver) { fun createPending(kind: MediaKind, displayName: String): Uri; fun publish(uri: Uri); fun delete(uri: Uri); fun exists(uri: Uri): Boolean; fun existingNames(kind: MediaKind): Set<String> }
```
- `trip_media.tripId` FK → trip `onDelete = CASCADE`, 인덱스 tripId. 여행 삭제 시 갤러리 파일은 지우지 않는다(기록만 사라짐).
- [ ] Step 1 테스트 먼저(JVM: 매핑·옵션 왕복·musicUri 저장 안 함·pending 왕복; 기기: CASCADE, 4→5 기존 데이터 유지·새 표 비어 있음, MediaStore 만들기→publish→exists→delete(테스트가 만든 파일만)).
- [ ] Step 2 구현, `5.json` 확인. `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`; 기기 규칙대로 세 클래스.
- [ ] Step 3 Commit `data: Room v5 trip_media, video options/job store, MediaStore writer; version 1.3.0`

### Task 4: 인코더와 음악 트랙 (스펙 §3.1④⑤, §3.4)

**Files:** Create `video/VideoEncoder.kt`, `video/AudioTrackMaker.kt`; Test 기기 `VideoEncoderTest`, `AudioTrackMakerTest`(테스트용 짧은 음원은 테스트가 PCM 사인파를 AAC/M4A로 만들어 씀 — 저작권 파일 넣지 않음).

**Interfaces — Consumes:** Task 2 `AudioPlanner`. **Produces:**
```kotlin
class VideoEncoder(output: FileDescriptor, width: Int, height: Int, fps: Int = FPS, bitRate: Int = 8_000_000) : AutoCloseable {
    companion object { fun open(fd: FileDescriptor, aspect: Aspect): Pair<VideoEncoder, Boolean> /* 1080 실패 시 720으로, second=true면 720 */ }
    val width: Int; val height: Int
    fun drawFrame(draw: (Canvas) -> Unit)          // inputSurface.lockHardwareCanvas, 프레임 시각 = index/fps
    fun addAudio(samples: AudioTrackMaker.Encoded?) // muxer에 AAC 트랙
    fun finish()
}
class AudioTrackMaker(context) { class Encoded(...); class Unreadable : Exception(); suspend fun make(uri: Uri, videoDurationUs: Long): Encoded }  // decode→PCM→(리샘플 44.1 kHz·스테레오)→자르기·2초 페이드→AAC 128 kbps
```
- 인코더 출력 대기·EOS·muxer 시작 순서(비디오+오디오 트랙 모두 추가 후 start) 처리. 오디오는 비디오 인코딩 후 같은 muxer에 써도 되도록 `VideoEncoder`가 순서를 관리(먼저 오디오 인코딩 결과를 메모리/임시 파일로 받아 두고 muxer 시작 시 두 트랙 추가).
- [ ] Step 1 기기 테스트 먼저: `twoSecondPortraitVideoOpens`(48프레임 단색 → `MediaExtractor`로 1080×1920·약 2초·48프레임±1), `landscapeVideoOpens`, `fallsBackTo720WhenAsked`(강제 플래그로 720 경로), `musicTrackTrimmedAndFaded`(5초 사인파 → 2초 영상에 AAC 트랙 길이 ≈ 2초, 마지막 구간 진폭 감소), `shortMusicEndsEarly`(1초 음원 → 오디오 1초), `monoFortyEightKhzIsConverted`(44.1 kHz 스테레오로), `unreadableMusicThrows`(텍스트 파일 → `Unreadable`).
- [ ] Step 2 구현 → 기기 규칙대로 두 클래스 → PASS. `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`.
- [ ] Step 3 Commit `video: H.264 encoder with 720p fallback and trimmed, faded AAC music track`

### Task 5: 프레임 그리기와 요약 이미지 그리기 (스펙 §2.2–2.4, §3.1③, §4)

**Files:** Modify `offline/OfflineRegionMeta.kt`(`RegionKind.VIDEO`), `offline/MapLibreOfflineController.kt`·`OfflineMapController.kt`(`downloadForTrack(tripId, points)` — 점들로 `Route`를 만들어 기존 경로 둘레 계획 재사용, 확대 13~16, kind VIDEO; `deleteVideoRegions(tripId)` — VIDEO만 지움, 기존 CORRIDOR/OVERVIEW 건드리지 않음); Create `video/VideoInputs.kt`, `video/FrameRenderer.kt`, `video/SummaryImageRenderer.kt`; Test JVM `VideoInputsTest`, 기기 `FrameRendererTest`, `SummaryImageRendererTest`.

**Interfaces — Consumes:** Task 1·2, `RideColors`, `HeartRateColor`, `PostStore`(사진·심박), `PhotoThumbnails.full`. **Produces:**
```kotlin
data class VideoInputs(val tripId: Long, val timeline: RideTimeline, val photos: List<PhotoCue>, val heartRate: List<HeartRateSample>, val dayStarts: List<Pair<Long, String>>,
    val segmentColors: List<String>, val title: String /* "10월 25일 (일)" / "10월 17일 ~ 18일" */, val badge: String /* "당일" / "여러 날 · N일" */, val totals: TripTotals /* 거리·이동 시간·평균/최고·상승·평균 심박·칼로리 */, val canColorByHeart: Boolean)
class VideoInputsLoader(rideStore, postStore, tripStore) { suspend fun load(tripId: Long): VideoInputs? }   // 끝난 ≥10 m 구간 없으면 null; 숨긴 사진 제외
class FrameRenderer(context, tileSource, inputs: VideoInputs, options: VideoOptions, width: Int, height: Int) {
    suspend fun render(plan: FramePlan, camera: Camera, canvas: Canvas)   // reuseMap이면 직전 스냅샷 재사용; 스냅샷은 Main에서 MapSnapshotter(크기 width×height, 카메라, 스타일 = tileSource)
}
class SummaryImageRenderer(context, tileSource) { suspend fun render(inputs: VideoInputs, aspect: Aspect, photos: List<Bitmap>): Bitmap }
```
- 덧그리기: 지나온 경로(주행별 색 또는 심박 색, 두께 6) / 남은 경로(두께 4·0.35), 현재 위치 흰 테두리 점, 숫자 띠(옵션 hud), 사진 카드(80% 폭, 둥근 모서리, 흰 테두리, 나타남/사라짐 알파·크기, 아래 "오후 8:49"), 자막, 시작 제목(날짜·배지·총 거리), 끝 요약 카드 + "bikeRecoder", 어두워짐. 좌표 → 픽셀은 스냅샷의 `pixelForLatLng`.
- [ ] Step 1 테스트 먼저: JVM `VideoInputsTest`(10 m 미만 구간 제외·숨긴 사진 제외·여러 날 dayStarts·제목/배지·심박 가능 여부·대상 없음 null). 기기: `FrameRendererTest`(1080×1920 Bitmap에 RIDE 프레임 그리기 — 지나온 경로 색 픽셀이 현재 위치 근처에 있음, 사진 프레임 가운데에 사진 색, 숫자 띠 영역이 비어 있지 않음, reuseMap 시 스냅샷 1회), `SummaryImageRendererTest`(세로·가로 크기, 사진 0장·심박 없음 `imageWithoutPhotosOrHeartRate` 정상). 지도 타일은 실제 인터넷(S22) 사용 가능, 실패해도 덧그림 단언은 성립하도록.
- [ ] Step 2 구현 → `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest`; 기기 규칙대로. S22에서 실제 여행 대신 테스트 경로로 RIDE·PHOTO·OUTRO 프레임과 요약 이미지 PNG를 scratchpad(`/tmp/claude-1000/-home-hyungchul-AndroidStudioProjects-bikeRecoder/f3dfc1e3-699a-43d7-b639-aa80f8f077d5/scratchpad/frame-ride.png`, `frame-photo.png`, `frame-outro.png`, `image-portrait.png`, `image-landscape.png`)로 꺼내 보고서에(임시 androidTest는 커밋하지 않음).
- [ ] Step 3 Commit `video: frame renderer over MapLibre snapshots, summary image renderer, track tile download`

### Task 6: 영상 작업과 포그라운드 서비스 (스펙 §3.1–3.4, §5.2)

**Files:** Create `video/VideoJob.kt`, `video/VideoService.kt`; Modify `AndroidManifest.xml`(서비스 `foregroundServiceType="mediaProcessing|dataSync"` + 권한 2개), `AppContainer.kt`, `ride/RideController.kt`(기록 시작 시 작업 멈춤 신호 — 최소 변경: `rideUi`를 서비스가 관찰해도 됨, 그러면 RideController 무변경); Test JVM `VideoJobTest`(가짜 렌더러·인코더·저장소·지도).

**Interfaces — Consumes:** Task 1–5. **Produces:**
```kotlin
sealed interface VideoJobState { data object Idle; data class Preparing(val kind: MediaKind); data class Rendering(val percent: Int, val minutesLeft: Int); data object Encoding;
    data class Done(val media: TripMedia, val downscaled: Boolean); data class Failed(val message: String); data object Cancelled }
class VideoJob(...) { val state: StateFlow<VideoJobState>; fun startVideo(tripId: Long, options: VideoOptions): StartResult; fun startImage(tripId: Long, aspect: Aspect): StartResult; fun cancel() }
enum class StartResult { STARTED, BUSY /* "이미 영상을 만들고 있습니다" */, RECORDING /* "기록 중에는 만들 수 없습니다" */, NO_INTERNET /* "지도를 받으려면 인터넷이 필요합니다" */, NO_SPACE /* "저장 공간이 부족합니다(약 N MB 필요)" */, MUSIC_UNREADABLE /* "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?" */, NOTHING_TO_MAKE }
```
- 순서: 시작 검사(기록 중·바쁨·저장 공간 = `VideoEstimate.bytes` vs 여유) → `setPending` → 지도 받기(`downloadForTrack`, 인터넷 없고 받아 둔 것 없음 → NO_INTERNET) → 프레임 계획·카메라 → `MediaStoreWriter.createPending` → 프레임 루프(진행률·남은 시간 = 실측 장당 시간 × 남은 비재사용 프레임 + 재사용 × 0.03) → 음악(있으면; 시작 검사 단계에서 미리 열어 보고 못 읽으면 `MUSIC_UNREADABLE`로 시작하지 않음 — UI가 "음악 없이 만들기"를 묻는다) → finish → publish → `TripMediaStore.add` → `recordSpeed` → `setPending(null)` → `deleteVideoRegions`. 취소·실패·기록 시작(`rideUi`가 Starting/Active) → 갤러리 임시 항목 delete, pending 지움, 지도 지움; 기록 때문이면 알림 "기록이 시작되어 영상 만들기를 멈췄습니다".
- 서비스: 시작 시 `startForeground`(API 35+ `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`, 아래 `DATA_SYNC`), 알림 "영상 만드는 중 N% · 약 M분 남음" + [취소] 액션, 끝 "영상을 만들었습니다"(누르면 미리보기 딥링크), 작업 끝나면 stopSelf. 렌더 루프는 `Dispatchers.Default` + 스냅샷만 Main.
- 앱 시작 시 `pending()`이 있고 작업이 돌고 있지 않으면 UI가 "영상을 만들지 못했습니다. 다시 만들까요?"를 띄울 수 있게 `VideoJob.unfinished(): PendingJob?` 제공(UI는 Task 7).
- [ ] Step 1 실패하는 JVM 테스트: `happyPathSavesAndRecords`, `busyRefuses`, `recordingRefuses`, `startingARecordingStopsAndDeletes`, `cancelDeletesPartial`, `failureDeletesPartialAndClearsPending`, `noInternetRefusesBeforeWork`, `noSpaceRefuses`, `musicUnreadableAskedBeforeStart`, `unfinishedJobIsOfferedAgain`, `videoRegionsDeletedAfterwards`, `progressAndMinutesLeft`, `speedRecorded`, `downscaledReported`.
- [ ] Step 2 구현 → `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- [ ] Step 3 Commit `video: video job and media-processing foreground service`

### Task 7: 화면 — 진입 버튼, 옵션 시트, 진행·미리보기, 만든 영상·이미지 줄, 다시 만들기 제안 (스펙 §1, §3.2–3.5, §5.1)

**Files:** Create `ui/video/VideoOptionsSheet.kt`, `ui/video/VideoProgressCard.kt`, `ui/video/MediaPreviewScreen.kt`, `ui/video/TripMediaRow.kt`, `ui/video/VideoViewModel.kt`; Modify `ui/history/TripDetailScreen.kt`·`TripDetailViewModel.kt`, `ui/record/SummaryExtras.kt`(+ 두 요약), `ui/BikeNavHost.kt`(미리보기 경로), `MainActivity`(다시 만들기 제안); Test JVM `VideoViewModelTest`, 기기 `VideoOptionsSheetTest`, `TripMediaRowTest`, `MediaPreviewScreenTest`.

- 진입: 요약(여행이 완료된 경우만 — 당일 끝, 여러 날 [여행 완료]) 아래 [영상 만들기] [이미지 만들기]; 여행 기록(COMPLETED) 위쪽 같은 두 버튼; 대상 없음이면 숨김.
- 옵션 시트: 표의 항목·기본값(마지막 옵션), [심박]은 `canColorByHeart`일 때만, [음악 고르기] = `ACTION_OPEN_DOCUMENT audio/*`(+ persistable 읽기 권한 요청, 실패해도 이번 작업 동안만 읽으면 됨), 고른 파일 이름 표시와 [없음]으로 되돌리기, "약 N분 걸립니다", [만들기]. 배터리 < 20% 충전 아님 → 확인 대화상자. `StartResult` 문구 표시, `MUSIC_UNREADABLE` → "음악 파일을 읽을 수 없습니다. 음악 없이 만들까요?" [음악 없이 만들기] [취소].
- 진행: 만드는 동안 여행 기록·요약에 진행 카드("영상 만드는 중 N% · 약 M분 남음", [취소]); 끝나면 미리보기 화면(영상 `VideoView` 재생 / 이미지 보기, [공유] = `ACTION_SEND` 해당 MIME, [갤러리에서 보기] = `ACTION_VIEW`), `downscaled`면 "이 휴대폰은 720p로 만들었습니다".
- 여행 기록 "만든 영상·이미지" 줄: 가로 목록(이미지 썸네일·영상 썸네일 + ▶), 누르면 `ACTION_VIEW`; 화면 열 때 `exists()` 없는 항목은 `remove`.
- 다시 만들기: 앱 시작 시 `unfinished()` 있고 작업 없음 → "영상을 만들지 못했습니다. 다시 만들까요?" [다시 만들기](같은 옵션, 음악 없이) [닫기](pending 지움).
- [ ] Step 1 테스트 먼저(JVM: 기본값/마지막 옵션, [심박] 조건, 진입 조건(완료·대상·기록 중), StartResult별 메시지, 다시 만들기; 기기: 시트 항목·기본값·문구, 줄 표시·누르기 콜백, 미리보기 버튼).
- [ ] Step 2 구현 → 빌드·JVM·기기(S22만). S22에서 **실제로 짧은 영상 1개(테스트 경로, 짧게·세로)와 이미지 1장을 앱 흐름으로 만들어** 미리보기 화면 캡처 2장을 scratchpad(`video-preview.png`, `image-preview.png`)에, 만든 MP4를 `adb pull`로 scratchpad에 꺼내 `ffprobe`가 있으면 길이·해상도 확인(없으면 MediaExtractor 기기 테스트 결과로 대신) — 끝나면 S22 갤러리의 시험 파일 지움.
- [ ] Step 3 Commit `app: make video/image from summary and trip detail; options, progress, preview, media row`

### Task 8: 문서

**Files:** `CHANGELOG.md`(`## [1.3.0] - 개발 중` 추가 항목), `README.md`(로드맵 V1.3.0 "개발 중", 버전 줄), 새 `docs/verification/video-field-test.md`(PC 표·기기 표(S22)·실주행 체크리스트 = 스펙 §5.3 실주행 항목 + 걸린 시간 기록 칸·경고 "connected 테스트는 S22 `R5CT43TFS9A`에서만, S25 `R3CY903ZYQV`에는 `installDebug`만").
- [ ] Step 1 `./gradlew :core:test :routing-brouter:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` → 수(XML).
- [ ] Step 2 문서 작성 → Commit `docs: V1.3.0 video and image verification and changelog`
