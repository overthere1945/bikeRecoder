package btools.router

// Deliberately declared in BRouter's own `btools.router` package (even though the file lives
// under this module's usual `src/test/kotlin` tree, not a `btools/router` directory) so that it
// can read `OsmTrack.voiceHints.list`, which BRouter's (unmodified, vendored) VoiceHintList
// declares without an access modifier, i.e. package-private. `errorMessage`/`foundTrack` on
// RoutingEngine are `protected`, which also resolves via same-package access from here.

import com.cowork.bikerecoder.core.model.RouteProfile
import com.cowork.bikerecoder.routing.ProfileInstaller
import com.cowork.bikerecoder.routing.SegmentFixture
import java.io.File
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("integration")
class EngineSmokeTest {

    private fun node(lat: Double, lon: Double, name: String) = OsmNodeNamed().apply {
        ilat = ((lat + 90) * 1e6).toInt()
        ilon = ((lon + 180) * 1e6).toInt()
        this.name = name
    }

    @Test
    fun `routes yeouido to banpo with every profile`(@TempDir profilesDir: File) {
        val segDir = SegmentFixture.ensure("E125_N35.rd5")
        val profiles = ProfileInstaller.install(profilesDir)

        for (p in RouteProfile.entries) {
            val e = RoutingEngine(
                null,
                null,
                segDir,
                listOf(
                    node(37.5284, 126.9327, "from"),
                    node(37.5100, 126.9960, "to"),
                ),
                RoutingContext().apply {
                    localFunction = File(profiles, "${p.fileName}.brf").path
                    // The profile sets turnInstructionMode = 1 ("auto-choose"), which BRouter's
                    // RoutingContext.readGlobalConfig() deliberately leaves unresolved (it is
                    // normally picked by RoutingParamCollector from the HTTP request, which we
                    // don't vendor/use here). Calling RoutingEngine directly, as this test (and
                    // the Task 11 Router) does, must pick a concrete style itself, or
                    // turnInstructionMode stays 0 ("none") and BRouter never registers detours
                    // for voice hints (see RoutingEngine#findTrack's guideTrack handling), so
                    // foundTrack.voiceHints.list stays empty even though a track was found.
                    turnInstructionMode = 2 // locus-style; any concrete (non-0, non-1) mode works
                },
            )
            e.quite = true
            e.doRun(60_000L)

            assertNull(e.errorMessage, p.name)
            assertTrue(e.foundTrack.distance in 5_000..12_000, "${p.name}: ${e.foundTrack.distance} m")
            assertTrue(e.foundTrack.voiceHints.list.isNotEmpty(), p.name)
        }
    }
}
