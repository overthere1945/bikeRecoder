package com.cowork.bikerecoder.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The back button and taps on the tab bar (V1.4.0 spec §1.1). */
class MainTabsBackTest {

    @Test
    fun backOnAnotherTabGoesToTheMap() {
        assertEquals(MainTab.MAP, backTab(MainTab.FEED))
        assertEquals(MainTab.MAP, backTab(MainTab.STATS))
        assertEquals(MainTab.MAP, backTab(MainTab.SETTINGS))
    }

    @Test
    fun backOnTheMapLeavesTheApp() {
        assertNull(backTab(MainTab.MAP))
    }

    @Test
    fun tabNames() {
        assertEquals(listOf("지도", "요약", "통계", "설정"), MainTab.entries.map { it.label })
    }

    @Test
    fun tappingAnotherTabOpensItWithoutScrolling() {
        val (selected, scroll) = tapTab(MainTab.MAP, MainTab.FEED, ScrollToTop())

        assertEquals(MainTab.FEED, selected)
        assertEquals(ScrollToTop(), scroll)
    }

    @Test
    fun tappingTheOpenSummaryOrStatsTabScrollsItUp() {
        val (feed, afterFeed) = tapTab(MainTab.FEED, MainTab.FEED, ScrollToTop())
        assertEquals(MainTab.FEED, feed)
        assertEquals(1, afterFeed[MainTab.FEED])
        assertEquals(0, afterFeed[MainTab.STATS])

        val (stats, afterStats) = tapTab(MainTab.STATS, MainTab.STATS, tapTab(MainTab.STATS, MainTab.STATS, afterFeed).second)
        assertEquals(MainTab.STATS, stats)
        assertEquals(1, afterStats[MainTab.FEED])
        assertEquals(2, afterStats[MainTab.STATS])
    }

    @Test
    fun tappingTheOpenMapOrSettingsTabDoesNothing() {
        val start = ScrollToTop(feed = 3, stats = 1)

        assertEquals(MainTab.MAP to start, tapTab(MainTab.MAP, MainTab.MAP, start))
        assertEquals(MainTab.SETTINGS to start, tapTab(MainTab.SETTINGS, MainTab.SETTINGS, start))
        assertEquals(0, start[MainTab.MAP])
        assertEquals(0, start[MainTab.SETTINGS])
    }
}
