package btools.router

/**
 * Test-only shim.
 *
 * `VoiceHintList.list` is declared without an access modifier (Java package-private) in
 * BRouter's unmodified, vendored source, and has no public getter. This extension function
 * lives in the same `btools.router` package so it can read `list` directly, without modifying
 * vendored BRouter source or resorting to reflection. It is used only by `EngineSmokeTest`
 * (which otherwise lives in, and should live in, this module's own `com.cowork.bikerecoder.routing`
 * package) to assert that voice hints were generated.
 */
fun VoiceHintList.hintCount(): Int = list.size
