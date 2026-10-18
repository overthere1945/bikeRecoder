package com.cowork.bikerecoder.ui.record

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.cowork.bikerecoder.post.PostProcessor
import com.cowork.bikerecoder.post.PostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a segment summary shows besides its totals (spec §2.3; Task 7 adds the heart rate here). */
data class SummaryExtrasState(
    /** "사진 N장": the segment's photos that are not hidden. */
    val photoCount: Int = 0,
)

/**
 * The extras of every segment summary (ruling R9: the record screen's and the guided ride's), for the segment whose
 * summary is on screen ([summarySegment], null while none is). Each segment that appears asks for one post-processing
 * pass (spec §1.1 ①), so its photos (and later its heart rate) come in while the summary shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SummaryExtras(
    summarySegment: Flow<Long?>,
    postStore: PostStore,
    postProcessor: PostProcessor,
    scope: CoroutineScope,
) {
    private val segment = summarySegment.distinctUntilChanged()

    val state: StateFlow<SummaryExtrasState> = segment
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(SummaryExtrasState())
            } else {
                postStore.observePhotos(listOf(id))
                    .map { photos -> SummaryExtrasState(photoCount = photos.count { !it.hidden }) }
                    .catch { emit(SummaryExtrasState()) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, SummaryExtrasState())

    init {
        scope.launch { segment.filterNotNull().collect { postProcessor.request() } }
    }
}

/** The summary's extras above its other footer lines: "사진 N장" (nothing without photos) and [photoPrompt]. */
@Composable
fun SummaryExtrasContent(state: SummaryExtrasState, photoPrompt: @Composable () -> Unit) {
    SummaryPhotoCount(state.photoCount)
    photoPrompt()
}

/** "사진 N장" under the summary's map; nothing without photos. */
@Composable
internal fun SummaryPhotoCount(count: Int) {
    if (count > 0) Text("사진 ${count}장", style = MaterialTheme.typography.titleMedium)
}
