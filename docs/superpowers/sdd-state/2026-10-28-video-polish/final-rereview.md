# V1.3.1 최종 수정 재검토 (2460dc2..d8c0122, FIX-NOW 1–8)

범위: final-review.md의 FIX-NOW 1–8, progress.md의 FW1·FW2·J2·J3 판정, final-fix-report.md, 수정 diff(1,195줄), 캡처 v131-photo-fixed.png.
체크아웃은 읽기만 했고, 시험은 다시 돌리지 않았습니다. 보고서의 시험 이름이 diff에 있는지, diff와 맞는지만 봤습니다. diff 밖의 현재 파일은 호출부 확인용으로만 열었습니다.

## Finding Verdicts

### 1. FW1 사진 장면의 시각 알약과 경로 흐리게 — ADDRESSED
- `FrameRenderer.kt` 경로 그리기 분기: `photoAlpha` 계산을 `when`보다 위로 올렸습니다(diff 241행). RIDE 경로는 `canvas.layered(1f - PHOTO_ROUTE_DIM * photoAlpha, routeBounds(route)) { fadedUnderBand(...) }`로 감쌌습니다. `PHOTO_ROUTE_DIM = 0.6f`입니다. 알파 1이면 `layered`가 `save`만 하므로 평소 프레임은 그대로입니다. 이 호출은 판정문과 같은 모양입니다.
- `drawPhoto`: 시각 글자 앞에 알약을 그립니다.
  - 가로 폭은 `tw/2 + 14u`입니다.
  - 위는 `baseline − 0.8·size − 7u`, 아래는 `baseline + 0.22·size + 7u`입니다. `FrameLayout.ASCENT`는 0.8, `DESCENT`는 0.22이고 FrameStyle.kt:145-146에서 확인했습니다.
  - 모서리 반지름은 높이/2, 색은 `argb(150·alpha, 0,0,0)`입니다.
  - 상수 `CAPTION_PILL_ALPHA=150`, `CAPTION_PAD_X=14`, `CAPTION_PAD_Y=7`이 판정(알약 150×photoAlpha, 14u×7u)과 일치합니다.
- 캡처: "오전 9:02"가 어두운 둥근 알약 위에 또렷하고, 경로와 점이 글자와 겹치지 않습니다. 다만 이 프레임은 경로가 거의 안 보이는 위치라 경로 흐림은 캡처로 확인되지 않습니다(보고서도 같은 말을 합니다). 경로 흐림은 기기 시험의 픽셀 수(5,230 → 0)로 확인한 값입니다.
- 시험: androidTest `FrameRendererTest.photoCaptionOnDarkPillAndRouteDimmed`가 있습니다. 밝은 offline 배경에서 알약 왼쪽 끝 띠의 평균 휘도가 100 미만인지, 카드 밖 진한 주황 픽셀이 첫 프레임의 1/4 미만인지 봅니다. S22 26/26 통과라는 보고와 시험 코드가 맞습니다.
- 문서: CHANGELOG 사진 카드 줄, video-field-test의 사진 장면 판정 줄과 S6 행이 FW1 문구로 바뀌었습니다.

### 2. FW2 처음 예상의 장당 기본값 0.25 → 0.15 — ADDRESSED
- `VideoJob.kt` `DEFAULT_SECONDS_PER_FRAME = 0.15`이고 KDoc에 근거(S22 약 0.10 s, V1.3.0 §3.6의 0.25에서 낮춤)를 적었습니다.
- 앱이 쓰는 값이 0.15인지 호출부를 모두 따라가 봤습니다. 앱 코드에서 0.25 기본값으로 떨어지는 경로는 없습니다.
  - `VideoViewModel.kt:380-381`: `secondsPerFrame() ?: VideoJob.DEFAULT_SECONDS_PER_FRAME`를 `VideoEstimate.minutes(plans, perFrame)`(391행)에 넘깁니다. 인자를 항상 명시합니다.
  - `VideoJob.kt:402-405`: `store.secondsPerFrame() ?: DEFAULT_SECONDS_PER_FRAME`를 `VideoEstimate.minutes(mapLeft, reusedLeft, stored)`에 넘깁니다. 이 오버로드는 기본 인자가 없습니다.
  - `VideoJob.kt:419`의 진행 중 계산은 측정값 `perFrame`을 씁니다.
  - app/src/main 안에서 `VideoEstimate.minutes`를 기본 인자로 부르는 곳은 없습니다. grep 결과는 위 3곳뿐입니다.
- core `VideoEstimate.minutes(frames, secondsPerFrame = 0.25)`가 그대로 남은 것은 결함이 아닙니다. 앱이 이 기본값에 기대는 곳이 없고, core 시험(`VideoEstimateTest`:15, 29, 30)은 0.25 기준 값에 묶여 있습니다. 판정(FW2)이 `VideoJob`의 값만 지목했으므로 범위에 맞습니다. 위험은 뒷날 누군가 인자 없이 부르면 0.25로 돌아간다는 점입니다. Out-of-Scope에 적었습니다.
- 시험: `VideoViewModelTest`의 이름과 값이 `VideoJob.DEFAULT_SECONDS_PER_FRAME`로 바뀌었고 `assertEquals(0.15, DEFAULT_SECONDS_PER_FRAME)`가 들어갔습니다. `VideoJobTest.progressAndMinutesLeft`는 기본값을 명시해 넘깁니다. 앱 시험에서 0.25를 암묵 기본값으로 쓰는 곳도 없습니다.
- 문서: CHANGELOG `### 변경` 줄, video-field-test의 "약 3분" 줄, 판정 줄이 고쳐졌습니다. V1.3.0 기록은 그대로 뒀습니다.

### 3. 키 가리기를 하나로 합치기 (K1, cause 사슬) — ADDRESSED
- 가리는 길이 하나가 됐습니다. `VideoMapStyle.redactedError`가 유일한 구현입니다. 키가 비었거나 `stackTraceToString()`(cause 사슬 포함)에 키가 없으면 원래 객체를 돌려주고, 있으면 cause까지 복사하며 가립니다(VideoMapStyle.kt:64-68).
- 호출부:
  - `redacting`이 `redactedError`를 씁니다(VideoMapStyle.kt:55-56).
  - `MapSnapshots`의 `log`는 `VideoMapStyle.redacting(log, secret)`입니다(MapSnapshots.kt:47, `secret` 다음에 선언). 호출부 3곳(115, 120, 153행)은 `log.warn`입니다. 생성자 인자와 필드의 이름 가림은 초기화식에서 인자가 우선하므로 올바릅니다.
  - `VideoJob`의 log는 이미 `redacting`을 씁니다(AppContainer.kt:280).
  - video scope 핸들러는 `VideoMapStyle.redactedError(e, BuildConfig.VWORLD_API_KEY)`로 남깁니다(AppContainer.kt:250). `internal`이라 같은 모듈에서 보입니다.
- 중복 제거: `MapSnapshots.warn`과 `MapSnapshots.Redacted`가 지워졌습니다. 앱 코드에서 `Redacted`는 VideoMapStyle의 private 클래스 하나뿐입니다(grep).
- 시험: `VideoMapStyleTest.redactedErrorKeepsCleanErrorsAndHidesCauseOnlyKeys`가 세 가지를 봅니다. 깨끗한 예외는 같은 인스턴스(assertSame)이고, 빈 키도 같은 인스턴스이며, 키가 cause에만 있으면 결과의 `stackTraceToString()`에 TESTKEY가 없고 스택 트레이스는 같습니다. 이전 `redacting` 시험도 그대로 유효합니다.

### 4. I2 / J2 이어진 긴 경로에서 평활 유지 — ADDRESSED
- `CameraPath.kt` RIDE 분기: `along = route.at(located)`, `travelled = (along − lastRideAlong).coerceAtLeast(0.0)`, `jumped = previous != null && distanceM(previous, located.point) > JUMP_M + travelled`. 끝에서 `lastRideAlong = along`을 갱신합니다. 판정 J2와 같습니다(직선거리 > JUMP_M + 경로로 지나온 거리).
- 구간 사이는 0으로 셉니다. `RouteDistance.cumulative`가 `segmentIndex`가 다른 인접 점 사이 step을 0으로 두기 때문입니다(Hud.kt:53). `route.at`은 이 누적값에서 나옵니다. 따라서 구간(날) 사이를 건너뛰면 travelled는 0에 가깝고, 직선거리 500 m 초과이면 평활이 다시 시작됩니다.
- 구간 안에서는 직선거리가 경로 길이보다 길 수 없습니다(삼각부등식). 그래서 이어진 주행은 프레임당 이동이 아무리 커도 건너뜀이 되지 않습니다.
- 날 바뀜 유지: 기존 `jumpsRestartSmoothing`은 구간 0 → 구간 1(`segmentIndex` 1, 동쪽으로 600 m)을 쓰므로 새 판정에서도 건너뜀입니다. 그 시험은 수정되지 않았고, 단언(확대가 목표값으로 튐, 방향이 동쪽 + 흔들기)은 그대로 유효합니다.
- 새 시험 `longContinuousRideKeepsSmoothing`: 520 km 지그재그를 한 구간으로 만들어 짧은 영상으로 뽑습니다.
  - 먼저 연속한 모든 주행 프레임의 직선거리가 `JUMP_M`을 넘는지 단언합니다. 시험이 의미를 가지려면 필요한 전제입니다.
  - 그 뒤 모든 연속 프레임의 방향 변화가 `MAX_TURN_DEG_PER_S/FPS` 이하이고 확대 변화가 `ZOOM_RATE_PER_S/FPS` 이하인지 봅니다.
  - RED에서 `Δzoom 0.31998…`로 실패한 기록이 있어 시험이 결함을 실제로 잡습니다.
- KDoc(JUMP_M, `cameras`)과 CHANGELOG 카메라 줄("구간 사이에서 500 m 넘게…")이 새 규칙으로 고쳐졌습니다.
- 사진 장면 뒤에는 `lastRidePoint`와 `lastRideAlong`이 둘 다 직전 주행 프레임 값으로 남습니다. 사진 장면의 moveMs는 그 주행 프레임의 위치를 쓰므로 짝이 맞습니다. 이전 동작과 같은 구조입니다.

### 5. I3 / J3 캐시 때문에 작게 잰 바이트는 기록하지 않기 — ADDRESSED
- `VideoJobStore.recordBytesPerFrame`: `if (!v.isFinite() || v < MIN_BYTES_PER_FRAME) return`, `MIN_BYTES_PER_FRAME = 8_000.0`. 판정 J3(8,000 B/프레임 미만은 기록하지 않음)과 같습니다. 경계는 8,000이 기록되고 7,999는 기록되지 않습니다.
- 사용처 확인: 이 const를 쓰려고 companion을 public으로 바꾸고, 나머지 멤버는 하나씩 `private`로 두었습니다. 같은 클래스 안에서만 쓰이므로 동작은 같습니다.
- 시험 `bytesPerFrameRoundTrip`: 500과 `MIN−1`(= 7,999)을 더해도 값이 50,000으로 남는지, 다시 열어 `MIN_BYTES_PER_FRAME`(8,000)이 기록되는지 봅니다. 양쪽 경계를 모두 덮습니다.
- 문서: KDoc, CHANGELOG 데이터 안내 줄, video-field-test의 데이터 안내 줄(J3 한 문장)이 고쳐졌습니다.

### 6. 위성 확인·음악 뒤에 기록 중인지 다시 확인 — ADDRESSED (영상·이미지 모두)
- `startVideo`: 음악 로드 뒤, `launch` 직전에 `if (recording.first()) return StartResult.RECORDING`(VideoJob.kt:485). 위성 확인과 음악 로드가 모두 이 확인보다 앞에 있습니다.
- `startImage`: 위성 확인 뒤, `launch` 직전에 같은 확인(VideoJob.kt:506).
- 시작 전에 돌려주므로 `finally`가 claim을 풉니다. `launch`는 일반 함수라 이 확인과 launch 사이에 중단점이 없어, 남는 틈은 감시자 코루틴이 막습니다.
- 시험 `recordingThatStartsDuringTheProbeRefuses`: `onProbe`로 위성 확인 중에 기록을 켭니다. 영상과 이미지 모두 `RECORDING`과 `assertNothingStarted()`를 보고, 위성 확인이 2번 불렸는지 봅니다. 그 뒤 기록을 끄고 다시 시작하면 STARTED → Done이라 claim 해제도 확인합니다.
- 음악 로드 도중 기록이 시작되는 경우는 코드가 같은 확인으로 덮지만, 시험은 위성 확인 중 시작만 만듭니다. 같은 확인 한 줄이라 막을 문제는 아닙니다.

### 7. 지역 조회 중 취소 시험 — ADDRESSED
- `VideoJobTest.cancelDuringTheRegionLookupLeavesNothing`가 있습니다. `regionOf`를 `awaitCancellation()`으로 걸어 조회 중에 `cancel()`합니다.
- Cancelled 상태, `store.pending() == null`, `backend.created`와 `backend.sinks`가 빈 목록, `maps.deleted == [TRIP]`, `runningTripId == null`을 봅니다. 다시 시작해 Done이 되는지도 봅니다.
- 보고서가 처음부터 통과했다고 밝혔고, 코드 변경 없이 고정 시험만 더한 것은 항목 요구와 맞습니다.

### 8. secrets.example.json 자리표시자 — ADDRESSED
- `"vworldApiKey": ""`로 바뀌었고 카카오 줄과 같습니다. 예시 파일을 그대로 복사하면 키 없음(조용한 일반 지도)이 됩니다.

## New Breakage in the Fix Diff
Critical·Important 수준의 새로운 문제는 찾지 못했습니다. 확인한 것은 다음과 같습니다.
- 컴파일 면: `MapSnapshots`의 `log` 생성자 인자와 같은 이름의 private 필드는 초기화식에서 인자가 먼저 잡히므로 문제없습니다. `FrameLayout.ASCENT/DESCENT`, `RectF`, `fill()`은 모두 존재합니다. `VideoJobStore` companion의 가시성 변경은 외부 사용처 영향이 없습니다.
- 동작 면:
  - `layered`로 감싼 경로는 알파가 1이면 `save`만 합니다. 평상시 프레임 비용과 모양이 그대로입니다.
  - 알약은 `photoAlpha > 0`일 때만 그려지고, 알약 알파가 `alpha`에 곱해져 사진이 사라질 때 함께 사라집니다.
  - J2: 구간 안에서 한 번에 크게 튀는 GPS 오차는 이제 경로 길이로 세어져 평활됩니다. 건너뜀으로 끊기지 않습니다. 이는 판정이 받아들인 비용입니다.
- 보고서의 시험 수(core 330, app 825)는 diff의 새 시험 개수와 맞습니다. core +1(`longContinuousRideKeepsSmoothing`), app +3(`redactedError…`, `recordingThatStartsDuring…`, `cancelDuringTheRegionLookup…`). `bytesPerFrameRoundTrip`과 VM 시험은 기존 시험의 수정입니다.

## Out-of-Scope Observations (non-blocking)
1. core `VideoEstimate.minutes(frames, secondsPerFrame = 0.25)`의 기본 인자와 KDoc("기본 0.25초")가 남아 있습니다. 앱은 이 기본값을 쓰지 않으므로 지금은 문제가 없습니다. 앞으로 인자 없이 부르는 호출이 생기면 0.25가 되살아납니다. 기본 인자를 지우거나 앱 상수와 맞추면 함정이 없어지지만, V1.3.0 §3.6 값과 core 시험(`VideoEstimateTest`:15, 29, 30)이 걸려 있어 이번 범위 밖으로 뒀다는 판단은 타당합니다.
2. `VideoMapStyle.copyRedacted`는 cause가 순환하면 끝없이 재귀합니다. `stackTraceToString()`은 순환을 "CIRCULAR REFERENCE"로 끊지만 복사는 끊지 못합니다. 이전 구현에도 같은 성질이 있었고 실제로 일어날 가능성이 거의 없습니다. 복사본에는 suppressed 예외가 빠지는데, 키가 그 안에만 있어도 숨겨지므로 안전한 쪽입니다.
3. video-field-test.md의 JVM 시험 수(329/822)가 이번 실행(330/825)과 다릅니다. SummaryImageRendererTest 행의 8도 실제 @Test 9개와 다릅니다. 보고서가 이미 밝힌 사전 차이입니다. 문서에 수를 적는다면 합치기 전에 맞추는 편이 낫습니다.
4. `/sdcard/Download/bikeRecoderTest/`에 10-28 날짜의 이전 캡처 PNG 8개가 남아 있습니다(보고서 기록). 이번 수정이 만든 것이 아닙니다.
5. S22에는 지금 앱이 설치되어 있지 않습니다(마지막 connected 실행 뒤 AGP가 제거). 실주행 확인 전에 설치가 필요합니다.

## Verdict
**All findings addressed, no new Critical/Important breakage.**
항목 1–8이 모두 해결됐습니다. 지정한 확인점 결과는 다음과 같습니다.
- J2: 직선거리 > JUMP_M + 경로로 지나온 거리, 구간 사이 0, 날 바뀜은 여전히 초기화입니다.
- 키 가리기: `redactedError` 하나로 합쳐졌고 cause 사슬과 AppContainer 핸들러까지 닿습니다.
- 기록 중 재확인: launch 직전이며 영상·이미지 모두입니다.
- J3: 8,000 B/프레임 미만은 기록하지 않습니다.
- FW2: 앱의 모든 경로가 0.15를 씁니다. 코어 기본 인자 0.25는 앱이 기대지 않는 잔여 값입니다.
