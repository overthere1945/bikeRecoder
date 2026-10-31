package com.cowork.bikerecoder.export

import com.cowork.bikerecoder.WarnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where an export started from a screen stands (spec §4.2). */
sealed interface ExportState {
    data object Idle : ExportState

    /** Being built and saved: the menu is closed and a small progress indicator shows over the panel. */
    data object Running : ExportState

    /** Saved: the "내보냈습니다" dialog is shown until [ExportRunner.clear]. */
    data class Done(val saved: ExportResult.Saved) : ExportState

    /** Not saved: one Toast "저장하지 못했습니다", then [ExportRunner.clear]. */
    data object Failed : ExportState

    /** The trip turned out to be recording: one Toast "기록을 마친 뒤 내보낼 수 있습니다", then [ExportRunner.clear]. */
    data object Recording : ExportState
}

/**
 * One export at a time for a screen's view model, in its [scope]. [run] is ignored while one is running; [clear] goes
 * back to [ExportState.Idle] once the screen has shown the outcome; [discard] forgets a running export (its outcome is
 * never shown — the file may still be saved). Call it from the scope's thread.
 */
class ExportRunner(private val scope: CoroutineScope, private val log: WarnLog) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Bumped by [discard]: a job of an older generation drops its outcome. */
    private var generation = 0
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun run(export: suspend () -> ExportResult) {
        if (_state.value == ExportState.Running) return
        _state.value = ExportState.Running
        val started = generation
        scope.launch {
            val result = try {
                export()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Export failed", e)
                ExportResult.Failed
            }
            if (started != generation) return@launch
            _state.value = when (result) {
                is ExportResult.Saved -> ExportState.Done(result)
                ExportResult.Failed -> ExportState.Failed
                ExportResult.Recording -> ExportState.Recording
            }
        }
    }

    /** Forgets the running export and any outcome not shown yet; back to [ExportState.Idle]. */
    fun discard() {
        generation++
        _state.value = ExportState.Idle
    }

    fun clear() {
        if (_state.value != ExportState.Running) _state.value = ExportState.Idle
    }
}
