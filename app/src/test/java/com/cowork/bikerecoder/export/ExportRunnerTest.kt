package com.cowork.bikerecoder.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** One export at a time; a discarded export never shows its outcome (spec §4.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportRunnerTest {

    private val saved = ExportResult.Saved("content://downloads/1", "bikeRecoder_route_20261101_0900.gpx", ExportFormat.GPX.mime)

    @Test
    fun secondRunWhileRunningIsIgnored() = runTest {
        val runner = ExportRunner(backgroundScope) { _, _ -> }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val export: suspend () -> ExportResult = {
            calls++
            gate.await()
            saved
        }

        runner.run(export)
        runCurrent()
        runner.run(export)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, calls)
        assertEquals(ExportState.Done(saved), runner.state.value)
    }

    @Test
    fun discardDropsTheRunningOutcome() = runTest {
        val runner = ExportRunner(backgroundScope) { _, _ -> }
        val gate = CompletableDeferred<Unit>()
        runner.run {
            gate.await()
            saved
        }
        runCurrent()
        assertEquals(ExportState.Running, runner.state.value)

        runner.discard()
        assertEquals(ExportState.Idle, runner.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ExportState.Idle, runner.state.value)

        // A new export after it is shown as usual.
        runner.run { ExportResult.Failed }
        runCurrent()
        assertEquals(ExportState.Failed, runner.state.value)
    }
}
