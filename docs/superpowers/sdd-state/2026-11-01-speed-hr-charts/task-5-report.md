# Task 5 part A report

Status: DONE. Commit: see `git log -1` on feature/speed-hr-charts-v1.8.0 (message "docs: V1.8.0 version, changelog, README and verification").

## Changes
- app/build.gradle.kts: verMinor 8 (versionCode 10800, debug 1.8.0-dev).
- AppVersionTest: 1.8.0-dev, 10800, added V1.8.0 display line (kept V1.7.0 line).
- SettingsScreenTest (androidTest): V1.8.0.
- CHANGELOG.md: new `## [1.8.0] - 개발 중` section. V1.7.0 section already had the "x (1).gpx" naming note, so it was not repeated.
- README.md: current version V1.8.0(개발 중); roadmap V1.8.0 속도·심박 그래프 (개발 중), V1.9.0 GPX 경로 따라가기, V1.10.0 경로 주변 장소; two doc links.
- docs/VERSIONING.md: roadmap table shifted.
- docs/verification/speed-hr-charts-field-test.md: new; JVM results, S22 slots "최종 수정 뒤 기록", §5.3 four-item checklist, decisions, known limits.
- Spec §1.2 corrected to full-width window (5,000 ms = 2.5 s each side; 10,000 ms = 5 s each side) plus two notes (bucket null only if more than half null; series with no neighbouring non-null pair is not made, e.g. sparse all-day HR). Also fixed the same wrong wording in §5.1 ("평균 창은 속도 ±5초, 심박 ±10초").

## Commands / results
- `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test`: BUILD SUCCESSFUL 1m55s. core 61 classes/539 tests, app 107/1228, brouter 4/19, 0 failures (:core:test was UP-TO-DATE, so its counts come from the previous run's XML for unchanged inputs).
- S22 (R5CT43TFS9A) woken, `SettingsScreenTest` only: BUILD SUCCESSFUL, 20 tests, 0 failures.
- S25 untouched; secrets.json not read.

## Concerns
- None blocking. Verification doc JVM numbers should be refreshed after final fixes (it says so).
