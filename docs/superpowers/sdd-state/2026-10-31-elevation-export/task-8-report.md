# Task 8 part A report

Status: DONE. Commit 212e877 `docs: V1.7.0 version, changelog, README and verification`.

Changes:
- app/build.gradle.kts verMinor 7 (versionCode 10700, 1.7.0-dev); AppVersionTest (1.7.0-dev, 10700, V1.7.0); SettingsScreenTest expects V1.7.0.
- CHANGELOG `## [1.7.0] - 개발 중` (added/changed/known limits/docs); README current version, roadmap rows V1.7.0 (dev) / V1.8.0 / V1.9.0 (planned), doc list; docs/VERSIONING.md table.
- docs/verification/elevation-export-field-test.md: JVM results, S22 placeholders "최종 수정 뒤 기록", spec 7.3 six-item S25 table (empty), known limits, decisions.

Tests:
- `./gradlew :core:test :app:testDebugUnitTest :routing-brouter:test --rerun-tasks`: BUILD SUCCESSFUL 2m51s.
  core 58 classes / 521 tests, app 103 / 1189, routing-brouter 4 / 19. Total 165 / 1729, 0 failures, 0 skipped.
  Warnings: only the pre-existing GlyphIcon.kt and PostProcessorTest.kt ones.
- S22 R5CT43TFS9A SettingsScreenTest: 20 tests, 0 failures. S25 untouched. secrets.json not printed or committed.
