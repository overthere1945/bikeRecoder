# BRouter (vendored)

- Source: https://github.com/abrensch/brouter
- Tag: `v1.7.10`
- Commit: `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`
- License: MIT (see `LICENSE` in this directory)

Only `src/main/java` of the `brouter-util`, `brouter-codec`, `brouter-expressions`,
`brouter-mapaccess`, and `brouter-core` modules is vendored here, **unmodified**.
No build tooling (checkstyle/PMD/buildSrc conventions), tests, or other modules
(`brouter-server`, `brouter-routing-app`, `brouter-map-creator`, ...) were copied.
Each module gets its own minimal `java-library` Gradle build file
(`options.release = 11`) under this project instead of BRouter's own build setup.

The bike routing profiles used by this app (`routing-brouter/src/main/resources/brouter/profiles/`)
are derived from BRouter's `misc/profiles2/trekking.brf` (same tag/commit) — see the
header comment in each `.brf` file for what was changed.
