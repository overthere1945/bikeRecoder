package com.cowork.bikerecoder.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag

/** The tabs of the main screen (V1.4.0 spec §1.1), with their names on the tab bar. */
enum class MainTab(val label: String) {
    MAP("지도"),
    FEED("요약"),
    STATS("통계"),
    SETTINGS("설정"),
}

/** The tab's icon on the tab bar: filled while [selected], outlined otherwise (they take the bar's tint). */
fun MainTab.icon(selected: Boolean): ImageVector = when (this) {
    MainTab.MAP -> if (selected) Icons.Filled.Map else Icons.Outlined.Map
    MainTab.FEED -> if (selected) Icons.Filled.DynamicFeed else Icons.Outlined.DynamicFeed
    MainTab.STATS -> if (selected) Icons.Filled.BarChart else Icons.Outlined.BarChart
    MainTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}

/** Test tags of the tab frame. */
object MainTabsTags {
    const val BAND = "main-band"
    fun tab(tab: MainTab) = "main-tab-${tab.name}"
}

/** Back on [selected]: [지도] lets the app go (null), every other tab goes to [지도] (spec §1.1). */
fun backTab(selected: MainTab): MainTab? = if (selected == MainTab.MAP) null else MainTab.MAP

/** How many times the open summary and stats tabs were tapped again: each new value scrolls that list to the top. */
data class ScrollToTop(val feed: Int = 0, val stats: Int = 0) {
    operator fun get(tab: MainTab): Int = when (tab) {
        MainTab.FEED -> feed
        MainTab.STATS -> stats
        MainTab.MAP, MainTab.SETTINGS -> 0
    }
}

/**
 * A tap on [tapped] while [selected] is open: another tab opens; the open tab again scrolls the summary or the stats
 * to the top and does nothing on the map or the settings (spec §1.1).
 */
fun tapTab(selected: MainTab, tapped: MainTab, scroll: ScrollToTop): Pair<MainTab, ScrollToTop> = when {
    tapped != selected -> tapped to scroll
    tapped == MainTab.FEED -> selected to scroll.copy(feed = scroll.feed + 1)
    tapped == MainTab.STATS -> selected to scroll.copy(stats = scroll.stats + 1)
    else -> selected to scroll
}

/**
 * The main screen's tabs: the tab chosen survives rotation and other screens on top (a new main screen starts on
 * [지도]); back on another tab goes to [지도]; each tab keeps its own saved state (e.g. how far its list was scrolled)
 * while another one shows. [content] draws [MainTab] with its scroll-to-top count and can switch tabs itself.
 */
@Composable
fun MainTabs(
    band: String?,
    onBand: () -> Unit,
    content: @Composable (tab: MainTab, scrollToTop: Int, select: (MainTab) -> Unit) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(MainTab.MAP) }
    var scroll by remember { mutableStateOf(ScrollToTop()) }
    BackHandler(enabled = backTab(selected) != null) { backTab(selected)?.let { selected = it } }
    val tabStates = rememberSaveableStateHolder()
    val select: (MainTab) -> Unit = { selected = it }
    MainTabsScaffold(
        selected = selected,
        onSelect = { tapped ->
            val (next, nextScroll) = tapTab(selected, tapped, scroll)
            selected = next
            scroll = nextScroll
        },
        band = band,
        onBand = onBand,
    ) { tab ->
        tabStates.SaveableStateProvider(tab.name) { content(tab, scroll[tab], select) }
    }
}

/**
 * The frame: [content] of [selected] above the tab bar [지도] [요약] [통계] [설정], and on every tab but [지도] the
 * [band] (spec §1.4) right above the tab bar while there is one. The tabs other than the map start below the status
 * bar; the map draws under it as before (its controls keep their own status bar padding). Nothing on a tab reaches
 * under the tab bar.
 */
@Composable
fun MainTabsScaffold(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    band: String?,
    onBand: () -> Unit,
    content: @Composable (MainTab) -> Unit,
) {
    Scaffold(
        bottomBar = {
            Column {
                if (selected != MainTab.MAP && band != null) {
                    RideBand(band, onBand, Modifier.testTag(MainTabsTags.BAND))
                }
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            icon = { Icon(tab.icon(tab == selected), contentDescription = null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(MainTabsTags.tab(tab)),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val inner = if (selected == MainTab.MAP) PaddingValues(bottom = padding.calculateBottomPadding()) else padding
        Box(modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) { content(selected) }
    }
}
