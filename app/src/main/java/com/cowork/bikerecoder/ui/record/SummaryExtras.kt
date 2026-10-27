package com.cowork.bikerecoder.ui.record

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect
import com.cowork.bikerecoder.core.post.HrStatus
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import com.cowork.bikerecoder.post.SegmentExtras
import com.cowork.bikerecoder.ui.health.HeartRateGate
import com.cowork.bikerecoder.ui.health.HeartRateSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a segment summary shows besides its totals (spec §2.3, §3.3). */
data class SummaryExtrasState(
    /** "사진 N장": the segment's photos that are not hidden. */
    val photoCount: Int = 0,
    /** The segment's heart rate as stored; null while there is no summary or nothing stored yet (read as PENDING). */
    val heartRate: SegmentExtras? = null,
)

/**
 * The extras of every segment summary (ruling R9: the record screen's and the guided ride's), for the segment whose
 * summary is on screen ([summarySegment], null while none is). Each segment that appears asks for one post-processing
 * pass (spec §1.1 ①), so its photos and heart rate come in while the summary shows.
 *
 * While the summary is also really on screen ([onScreen]), its heart rate is read again every minute, at most
 * [REFETCH_TIMES] times per segment (spec §3.2: "1분마다(최대 15분)"), until it is READY and detailed. Closing the summary or
 * leaving the screen stops that; coming back goes on with the reads left, at the time the next one was due.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryExtras(
    summarySegment: Flow<Long?>,
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val scope: CoroutineScope,
) {
    private val segment = summarySegment.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    private val onScreen = MutableStateFlow(false)

    /** Reads done by the one-minute loop, per segment (touched only from [scope]'s loop). */
    private val refetched = HashMap<Long, Int>()

    /**
     * When the next read of each segment is due ([PostProcessor.clock] time). Kept while the screen is left, so a
     * screen stopped and started more often than once a minute still reads every minute.
     */
    private val nextDue = HashMap<Long, Long>()

    val state: StateFlow<SummaryExtrasState> = segment
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(SummaryExtrasState())
            } else {
                combine(
                    postStore.observePhotos(listOf(id)).map { photos -> photos.count { !it.hidden } }.catch { emit(0) },
                    postStore.observeExtras(listOf(id)).map { it.firstOrNull() }.catch { emit(null) },
                ) { photoCount, heartRate -> SummaryExtrasState(photoCount, heartRate) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, SummaryExtrasState())

    init {
        scope.launch { segment.filterNotNull().collect { postProcessor.request() } }
        scope.launch {
            combine(segment, onScreen) { id, shown -> id.takeIf { shown } }
                .distinctUntilChanged()
                .collectLatest { id -> if (id != null) refetchWhileShown(id) }
        }
    }

    /** The summary is (true) or is no longer (false) on a started screen. */
    fun onScreen(shown: Boolean) {
        onScreen.value = shown
    }

    /** [다시 가져오기]: reads the summary's heart rate now. */
    fun refetchNow() {
        val id = segment.value ?: return
        scope.launch { postProcessor.refetchHeartRate(id) }
    }

    private suspend fun refetchWhileShown(id: Long) {
        while ((refetched[id] ?: 0) < REFETCH_TIMES) {
            val due = nextDue.getOrPut(id) { postProcessor.clock() + REFETCH_EVERY_MS }
            // A clock set back or forward never waits longer than one period.
            delay((due - postProcessor.clock()).coerceIn(0L, REFETCH_EVERY_MS))
            if (isDetailedReady(id)) return
            refetched[id] = (refetched[id] ?: 0) + 1
            nextDue[id] = postProcessor.clock() + REFETCH_EVERY_MS
            postProcessor.refetchHeartRate(id)
        }
    }

    /**
     * READY from a few all-day samples (not [SegmentExtras.hrDetailed]) is not the end: the watch workout's detailed
     * heart rate can still arrive minutes later (I5), and [PostProcessor.refetchHeartRate] keeps the stored heart rate
     * when a read brings nothing or no more samples (R7, spec §6.1). The same stop as
     * [com.cowork.bikerecoder.core.post.HrRetryPolicy.shouldTry] within the ride's day, the only time a summary shows;
     * after these reads the post-processing pass goes on reading a sparse READY until the ride's end + 24 h.
     */
    private suspend fun isDetailedReady(id: Long): Boolean = try {
        postStore.extras(id)?.let { it.hrStatus == HrStatus.READY && it.hrDetailed } == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        const val REFETCH_EVERY_MS = 60_000L
        const val REFETCH_TIMES = 15
    }
}

/**
 * The summary's extras above its other footer lines: "사진 N장" (nothing without photos), [photoPrompt], and the heart
 * rate ([HeartRateSection] with [heartRateGate]; [onRefetch] = [다시 가져오기]). [onScreen] is told while this is on a
 * started screen (it drives [SummaryExtras.onScreen]).
 */
@Composable
fun SummaryExtrasContent(
    state: SummaryExtrasState,
    photoPrompt: @Composable () -> Unit,
    heartRateGate: HeartRateGate? = null,
    onRefetch: () -> Unit = {},
    onScreen: (Boolean) -> Unit = {},
) {
    val currentOnScreen by rememberUpdatedState(onScreen)
    LifecycleStartEffect(Unit) {
        currentOnScreen(true)
        onStopOrDispose { currentOnScreen(false) }
    }
    SummaryPhotoCount(state.photoCount)
    photoPrompt()
    HeartRateSection(state.heartRate, onRefetch, heartRateGate)
}

/** "사진 N장" under the summary's map; nothing without photos. */
@Composable
internal fun SummaryPhotoCount(count: Int) {
    if (count > 0) Text("사진 ${count}장", style = MaterialTheme.typography.titleMedium)
}
