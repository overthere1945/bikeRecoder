# SDD ledger — plan: docs/superpowers/plans/2026-10-06-navigation.md

Spec: docs/superpowers/specs/2026-10-05-navigation-design.md
Branch: feature/navigation-v1.0.0 (from master fa66d4a)
Ruling: work on in-place branch `feature/navigation-v1.0.0` instead of a separate worktree — user explicitly wants code visible in /home/hyungchul/AndroidStudioProjects/bikeRecoder; master untouched — cost if wrong: none, branch can be moved.

## Pre-flight scan

| Pair / task | Produced vs consumed | Finding |
|---|---|---|
| T1 ↔ T10 settings.gradle.kts | T1 adds :core; T10 adds :routing-brouter + :third_party:brouter-* | consistent |
| T1 ↔ T17 app deps | T1 app depends only on :core; T17 AppContainer builds BRouterRouter | gap: app needs :routing-brouter → R2 |
| T2 → T3/T6/T9/T11 | GeoPoint, LocationFix, Route(cumulativeM, stopPointIndices), Instruction, Router | consistent |
| T3 → T6/T9 | Progress(distanceAlongM, lateralOffsetM, remainingM, nextInstruction, distanceToNextInstructionM) | consistent |
| T3 ↔ T9 ArrivalDetector | needs stopDistancesAlongM = route.cumulativeM[stopPointIndices] | consistent (T9 computes) |
| T4 → T9 | EtaEstimator, Odometer | consistent |
| T5 → T6/T9 | KoreanPhrases API | consistent |
| T6 → T9 | VoiceScheduler.onUpdate/enqueueEvent/replaceRoute, Utterance | consistent |
| T7 → T20 | CorridorPlanner.plan, CorridorPlan.truncated | consistent |
| T8 → T12/T17/T19/T20 | TripStore, OfflineMapController, TripManager | consistent |
| T9 → T16 | GpxParser.parse → List<LocationFix> | consistent |
| T9 → T19 | NavigationSession.updates/onFix/onTick | consistent |
| T10 → T11 | ProfileInstaller, BRouter modules | consistent |
| T11 → T17/T19 | BRouterRouter, BRouterGeoJsonParser (AssetRouter) | consistent |
| T12 ↔ T21 | T12 instrumented tests need device; AVD setup is T21 Step 1 | ordering gap → R3 |
| T12 → T20 | OfflineRegionRefDao | consistent |
| T13 → T14 | mockwebserver dependency added in T13 | consistent |
| T15 → T18/T19 | BikeMap, MapOverlay, CameraMode | consistent |
| T16 → T19 | LocationSource, VoiceOutput | consistent |
| T17 → T19/T20 | AppContainer w/o navigation, Noop offline | consistent (plan text says so) |
| T1 self | test `kakao key field exists` asserts only non-null of a String constant (asserts nothing) | plan-mandated defect → R4 |
| T1 self | JUnit5 on Gradle 9 requires junit-platform-launcher at test runtime | missing → R5 |
| T2 self | test expects 8_860±60 m; true haversine = 8_778 m | wrong value → R6 |
| T3 self | "진행 거리는 뒤로 가지 않는다(최소 직전값 − 20m 허용 없이 단조 증가)" ambiguous | → R7 |
| T4–T5, T7–T8 self | expected values recomputed (796→800미터, 42,080→42.1, 87.4km→약 5시간 50분, day number 3) | consistent |
| T6 self | `far and near prompt once each`: along 790 means 210 m to turn (> 200) → no far prompt | wrong value → R8 |
| T9–T21 self | tests vs specified code | consistent |

## Rulings
- R2 Ruling: T17 adds `implementation(project(":routing-brouter"))` to app — AppContainer needs BRouterRouter — cost if wrong: none.
- R3 Ruling: AVD/device setup (T21 Step 1) runs in T12 before first instrumented test — T12 cannot verify otherwise — cost if wrong: ~1.5GB system-image download earlier.
- R4 Ruling: T1 test asserts `BuildConfig.KAKAO_REST_API_KEY` equals `kakaoRestApiKey` from root secrets.json if present, else "" — a non-null check on a String constant asserts nothing — cost if wrong: trivial.
- R5 Ruling: every JUnit5 module adds `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` — Gradle 9 requirement — cost if wrong: none.
- R6 Ruling: T2 distance expectation = 8_778 ± 20 m — computed haversine with R=6_371_008.8 — cost if wrong: none.
- R7 Ruling: T3 distanceAlongM is monotonic non-decreasing (never goes backward) — simplest reading consistent with out-and-back protection — cost if wrong: small jitter handling change.
- R8 Ruling: T6 test uses along 800 → far prompt, 805 → [], 975 → near prompt, 980 → [] — 200 m threshold inclusive — cost if wrong: none.

## Progress
Task 1: complete (commits fa66d4a..5663cf1, review clean) — Kotlin 2.4.20 used, no fallback
Task 1: minor (deferred): dead catalog aliases junit/junitVersion/espressoCore/androidx-junit/androidx-espresso-core in libs.versions.toml
Task 1: minor (deferred): KAKAO_REST_API_KEY buildConfigField not escaped for quotes/backslashes
Task 2: complete (commits 5663cf1..386aef4, review clean)
Task 2: minor (deferred): no tests for GeoMath.project degenerate segment and cumulativeDistances empty/single list
Task 3: complete (commits 386aef4..6a4f91c, review clean)
Task 3: minor (deferred): RouteProgressTracker unmatched-fallback branch unreachable by construction (dead code); require(points>=2) untested; ArrivalDetector stores stopDistancesAlongM only for size validation
Task 4: complete (commits 6a4f91c..2387d7b, review clean)
Task 4: minor (deferred): accuracy filter duplicated in EtaEstimator/Odometer; test helper pointAt() (equirectangular) differs ~0.11% from GeoMath.distanceM
Task 5: fix round 1/5 (1 addressed, 0 open — km HALF_UP binary-float tie; commits 7f94bf7..86bc6ff)
Task 5: complete (commits 2387d7b..86bc6ff, review clean)
Task 6: complete (commits 86bc6ff..a7d938d, review clean)
Task 6: minor (deferred): VoiceScheduler.mergeThenType mutates state while named like a query; indexOfInstruction scanned twice per update
Ruling R9: TestRoutes.pointAt uses 111_320 m/deg while GeoMath uses R=6_371_008.8 (111_195 m/deg) → ~0.11% test-geometry mismatch; Task 7+ test authors must derive expected distances via GeoMath (not nominal) or new helpers consistent with GeoMath; do not rewrite pointAt now (would ripple into Tasks 3/4/6 tests) — cost if wrong: slightly awkward test helpers.
Task 7: complete (commits a7d938d..7bdca82, review clean)
Task 7: minor (deferred): CorridorPlanner.selectPoints fallback takeLast(max(2,size)) is dead/misleading; no test for non-zero fromDistanceAlongM without truncation
Ruling R10: subagents' Co-Authored-By trailer naming their own model (e.g. Sonnet) is accepted instead of the contract's literal Opus line — the trailer should name the model that wrote the code — cost if wrong: cosmetic commit trailers.
Task 8: fix round 1/5 (3 addressed, 0 open — duplicated Trip literal; convertToMultiDay precondition; changeStops fail-fast; commits 713cc86..5b2a285)
Task 8: complete (commits 7bdca82..5b2a285, review clean)
Ruling R11: NavigationSession GPS watchdog runs purely on tick time — onFix only marks "fix seen"; onTick(now) records now as last activity if a fix arrived since the previous tick, and flags weak when now − lastActivity ≥ gpsTimeoutMs; tick-update ETA reuses the last fix-based ETA. Brief test adjusted to: fix, onTick(t0), onTick(t0+10_001) → weak. — mixing GNSS fix time with System clock would make GPX replay (T16 keeps original timestamps) announce GPS-weak every second — cost if wrong: watchdog fires up to one tick later.
Ruling R12: keep updates = MutableSharedFlow(extraBufferCapacity = 64) with tryEmit; T19 must collect session.updates inside the foreground service (never a UI-lifecycle collector) so updates are not dropped — cost if wrong: lost utterances when collector lags.
Task 9: fix round 1/5 (5 addressed, 0 open — retry-wait back-on-route; tick-time watchdog R11; rerouteJob finally + unusable route; inaccurate-fix speed; waypoint-during-reroute test; commits d564aed..5ac8778)
Task 9: complete (commits 5b2a285..5ac8778, review clean)
Task 9: minor (deferred): Rerouted/RerouteFailed/StopReached events delivered with the next update (≤1 fix late); onTick does not drain scheduler utterances (rerouteFailed phrase may split from its event); rerouteJob finally untested
Task 10: fix round 1/5 (2 addressed, 0 open — EngineSmokeTest package + VoiceHintList shim; ProfileInstaller overwrite test; commits ec6893e..2240aeb)
Task 10: complete (commits 5ac8778..2240aeb, review clean) — vendored Java verified byte-identical to BRouter v1.7.10 via diff -rq
Task 10: minor (deferred): unclassified/default costfactor left as trekking's isbike-conditional values (spec §6.1 table silent)
Ruling R13: BRouter leaves turnInstructionMode=1 ("auto") unresolved without RoutingParamCollector → zero voice hints. Task 11 must set a concrete mode on RoutingContext before doRun (use 3 = osmand-style so EL/ER map to JSON codes 8/9 = KEEP_LEFT/KEEP_RIGHT, matching the parser table); verify hints appear in integration tests — cost if wrong: different hint granularity, fixable by changing one constant.
PAUSED 2026-10-06 at user request (leaving for work). Resume at Task 11. HEAD 2240aeb on feature/navigation-v1.0.0.
RESUMED 2026-10-06 evening at home; no remote changes. HEAD ee7e889 (includes merge of GitHub initial commit).
Task 11: fix round 1/5 (4 addressed, 0 open — roundabout exit abs; error-path tests incl. TIMEOUT mapping fix "timeout after"; catch Exception; terminate() on cancel; commits 11fdb0d..888478b)
Task 11: complete (commits ee7e889..888478b, review clean) — measured Yeouido→Banpo: CYCLEWAY_FIRST 7536.7 m/0.918, BALANCED 7536.7 m/0.918, SHORTEST 7502.5 m/0.937; Seoul→Yeoju 3.7 s, 89.8 km, 0.968
Task 11: minor (deferred): profile-ordering test is near-vacuous on the Han-river route (profiles pick ~same path); NO_ROUTE mapping untested; unused import kotlinx.coroutines.isActive in BRouterRouter; T17 should pass an IO dispatcher to BRouterRouter
Ruling R14: instrumented tests run on the USB-connected phone SM-S938N (Galaxy S25 Ultra, Android 16 / API 36, serial R3CY903ZYQV) instead of creating an AVD (R3 superseded) — user connected it; avoids 1.5 GB image download — cost if wrong: test APKs installed/uninstalled on the user's phone; Android 17-specific behaviour not covered until field test.
Task 12: complete (commits 888478b..7f35f3d, review clean) — connected tests 6/6 on SM-S938N
Task 12: minor (deferred): activeTrip lacks id tie-break; enum valueOf throws on unknown names (inconsistent with Settings fallback); fixtures don't round-trip all enum values; replaceStops "ignore incoming ids" untested; AppDatabase.create() must be called once (T17 AppContainer)
Note: connected tests use -Pandroid.testInstrumentationRunnerArguments.class=<FQCN> (no --tests on connected task)
Task 13: fix round 1/5 (3 addressed + watcher-simplification declined with valid rationale, 0 open — cancellation test; atomic Files.move; hasUpdate edge tests; commits 815e247..7d75e7e)
Task 13: complete (commits 7f35f3d..7d75e7e, review clean)
Task 13: minor (deferred): concurrent downloads of the same segment name share one .part (callers must serialize per name); mockwebserver3-junit5 dependency unused; OkHttp "Log.isLoggable not mocked" stderr noise in app JVM tests (accepted)
Task 14: fix round 1/5 (4 addressed, 0 open — invalid-header key → INVALID_KEY; call cancel on cancellation; tolerant x/y parsing; addressOf 401/403 + missing documents tests; commits a9c493a..4ff4316)
Task 14: complete (commits 7d75e7e..4ff4316, review clean)
Task 14: minor (deferred): Double.toString for x/y could emit scientific notation for tiny values (irrelevant for Korea)
Task 15: complete (commits 4ff4316..de57bd9, review clean) — device screenshot verified: Korean labels, route line, attribution on SM-S938N
Task 15: minor (deferred): OpenFreeMapSource blocking fetch not aborted on cancellation + no unit tests; FOLLOW snaps back after pinch/rotate (only onMoveBegin breaks follow) — T19 should also hook scale/rotate; MapView onSaveInstanceState/onLowMemory not forwarded; no retry after style fetch failure (T17/T18 should offer retry); overlay layers drawn above base labels; route shields show "48;50" style refs
Ruling R15: phone R3CY903ZYQV disconnected (adb shows no device) during Task 16. Connected tests are deferred to a 'device verification batch' run once the user reconnects; tasks proceed on JVM tests + assembleDebug meanwhile. Pending device runs: T16 GpxLocationSourceTest; T16 TTS engine check — cost if wrong: device-only defects surface later in the batch.
Task 16: fix round 1/5 (5 addressed, 0 open — TTS <queries>; re-entrancy-safe TtsEngineSelector + JVM tests; Fused addOnFailureListener; GPX bound 5 s; ordered pre-init flush; commits 698f8d3..c6c7acd)
Device batch (R15): phone reconnected; controller ran connected GpxLocationSourceTest on SM-S938N → 1/1 pass. Phone has com.samsung.SMT + lang_ko_kr_r00 and com.google.android.tts.
Task 16: complete (commits de57bd9..c6c7acd, review clean)
Task 16: minor (deferred): TTS speak() binder call under lock (latency only); selection continues briefly after shutdown(); engine death without onDone keeps focus until shutdown; muted does not stop already-queued utterances
Device batch pending (R15): phone absent from adb and lsusb again during Task 17. Pending: T17 onboarding screenshot.
Ruling R16: onboarding skip choices are persisted (AppSettings gets skippedOnboardingSteps); effective step = first step from nextOnboardingStep order that is neither satisfied nor skipped; start destination main iff effective step == DONE. Fine location stays mandatory; BACKGROUND_LOCATION, NOTIFICATIONS, BATTERY and SEGMENTS each offer [나중에]. Downloading segments clears the SEGMENTS skip. — brief's literal 'start = main iff DONE' re-showed onboarding on every cold start and hard-gated optional permissions, contradicting spec §4.1 (map usable without route data) — cost if wrong: users may skip background location and later find screen-off guidance missing (T19 should warn when background location is not granted).
Task 17: fix round 1/5 (3 addressed, 0 open — R16 persisted skips + effectiveOnboardingStep; LazyRouter failure mapping; progress throttle; commits f0e031b..d07070f)
Task 17: complete (commits c6c7acd..d07070f, review clean) — device screenshot still pending (phone unplugged)
Task 17: minor (deferred): settings.first() at startup has no catch (corrupt DataStore crashes); data package imports ui.onboarding.OnboardingStep (layering); LazyRouter catches Exception not Error; battery-exemption denial gives no hint; per-file download progress; T19 should warn when background location not granted (R16 cost)
Device batch pending: T18 PlanScreenTest (compiled, not run).
Ruling R17: the 50 m 'destination too close' rule applies only when the plan has no waypoints (stops.size == 1); loop routes with waypoints are allowed (ArrivalDetector's along-route gating prevents instant arrival) — plan Review Focus #2 names loop routes as supported; the brief's literal rule made them unplannable — cost if wrong: a 2-stop plan with waypoint+destination both near start could arrive quickly at the waypoint (harmless).
Task 18: fix round 1/5 (10 addressed, 0 open — location crash; R17 loop plans; change-counter trigger; 30 s freshness; no polling; segmentsReady once; clear(); destination labels; coordinate fallback title; lambda dedupe; commits 7e1e080..2751c8b)
Task 18: complete (commits d07070f..2751c8b, review clean) — PlanScreenTest pending device batch
Task 18: minor (deferred): after a location source failure currentLocation stays null until all subscribers detach >5 s; GPX replay fixes look stale (>30 s) to PlanViewModel; device clock skew >30 s blocks routing; MainViewModel untested; main screen re-centres after rotation; fit.maxZoom ignored for bounds; completeStaleTrip/router errors silent
T19 notes: call planViewModel.clear() when navigation starts; warn when background location not granted (R16); collect session.updates in the service (R12); FOLLOW should also break on scale/rotate (T15 minor)
SESSION INTERRUPTED during Task 19 dispatch. Found commit cf37f6c "app: navigation service, screen and trip flow" (31 files) but no task-19-report.md. Controller verification: :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest green; connected NavigationScenarioTest on SM-S938N → only waypointScenarioMarksVisited reported, FAILED (empty failure message). Task 19 not complete — needs a fresh implementer to diagnose, write the report, then review.
