package com.cowork.bikerecoder.ui.record

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a segment summary shows besides its totals (spec §2.3, §3.3). */
data class SummaryExtrasState(
    /** "사진 N장": the segment's photos that are not hidden. */
    val photoCount: Int = 0,
    /** The segment's heart rate as stored; null while there is no summary or nothing stored yet (read as PENDING). */
    val heartRate: SegmentExtras? = null,
    /** "거의 움직이지 않은 기록입니다. 저장할까요?" (spec §6.10): a ride under 10 m, not answered yet. */
    val askShort: Boolean = false,
)

/**
 * The extras of every segment summary (ruling R9: the record screen's and the guided ride's), for the segment whose
 * summary is on screen ([summarySegment], null while none is). Each segment that appears asks for one post-processing
 * pass (spec §1.1 ①), so its photos and heart rate come in while the summary shows.
 *
 * While the summary is also really on screen ([onScreen]), its heart rate is read again every minute, at most
 * [REFETCH_TIMES] times per segment (spec §3.2: "1분마다(최대 15분)"), until it is READY and detailed. Closing the summary or
 * leaving the screen stops that; coming back goes on with the reads left, at the time the next one was due.
 *
 * A segment shorter than 10 m is asked about once ([SummaryExtrasState.askShort], spec §6.10): [discardShort] deletes it
 * (and its trip when nothing else is left of it, [ShortRides.discard]), [keepShort] keeps it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryExtras(
    summarySegment: Flow<Long?>,
    private val postStore: PostStore,
    private val postProcessor: PostProcessor,
    private val shortRides: ShortRides,
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
                    askShort(id),
                ) { photoCount, heartRate, askShort -> SummaryExtrasState(photoCount, heartRate, askShort) }
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

    /**
     * [버리기]: deletes the summary's short segment ([ShortRides.discard]), then [close]s the summary. Once per segment:
     * a second tap before the dialog goes does nothing.
     */
    fun discardShort(close: () -> Unit) {
        val id = segment.value ?: return
        if (id in shortRides.answered.value) return
        shortRides.answer(id)
        scope.launch {
            // Once started, the deletion finishes even if the screen goes away; the summary is closed only if it stays.
            withContext(NonCancellable) { shortRides.discard(id) }
            close()
        }
    }

    /** [저장], or the dialog dismissed: the segment stays and is not asked about again. */
    fun keepShort() {
        segment.value?.let(shortRides::answer)
    }

    private fun askShort(id: Long): Flow<Boolean> = combine(
        flow { emit(shortRides.isShort(id)) }.catch { emit(false) },
        shortRides.answered,
    ) { short, answered -> short && id !in answered }

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
 * started screen (it drives [SummaryExtras.onScreen]). A short ride ([SummaryExtrasState.askShort]) is asked about
 * over the summary: [onDiscard] = [버리기], [onKeep] = [저장] or the dialog dismissed (spec §6.10).
 */
@Composable
fun SummaryExtrasContent(
    state: SummaryExtrasState,
    photoPrompt: @Composable () -> Unit,
    heartRateGate: HeartRateGate? = null,
    onRefetch: () -> Unit = {},
    onScreen: (Boolean) -> Unit = {},
    onDiscard: () -> Unit = {},
    onKeep: () -> Unit = {},
) {
    val currentOnScreen by rememberUpdatedState(onScreen)
    LifecycleStartEffect(Unit) {
        currentOnScreen(true)
        onStopOrDispose { currentOnScreen(false) }
    }
    SummaryPhotoCount(state.photoCount)
    photoPrompt()
    HeartRateSection(state.heartRate, onRefetch, heartRateGate)
    if (state.askShort) ShortRideDialog(onDiscard, onKeep)
}

/** "거의 움직이지 않은 기록입니다. 저장할까요?" with [버리기] (emphasized, the default) and [저장]. */
@Composable
private fun ShortRideDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        text = { Text("거의 움직이지 않은 기록입니다. 저장할까요?") },
        confirmButton = { Button(onClick = onDiscard) { Text("버리기") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("저장") } },
    )
}

/** "사진 N장" under the summary's map; nothing without photos. */
@Composable
internal fun SummaryPhotoCount(count: Int) {
    if (count > 0) Text("사진 ${count}장", style = MaterialTheme.typography.titleMedium)
}
