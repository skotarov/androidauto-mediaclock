package com.kotarov.autoclock.artwork

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class LatestRenderWorkerTest {
    private val work = ArrayDeque<Runnable>()
    private val main = ArrayDeque<() -> Unit>()
    private val delivered = mutableListOf<Int>()
    private val discarded = mutableListOf<Int>()
    private val rendered = mutableListOf<Int>()
    private val worker = LatestRenderWorker<Int>(
        Executor { work.add(it) }, { main.add(it) }, { discarded.add(it) })

    private fun submit(value: Int) {
        worker.submit({ rendered.add(value); value }) { delivered.add(it.getOrThrow()) }
    }
    private fun step() {
        work.removeFirst().run()
        main.removeFirst().invoke()
    }

    @Test fun rapidChangesKeepOnlyLatestPendingRequest() {
        submit(1); submit(2); submit(3)
        assertEquals(1, work.size)
        step(); step()
        assertEquals(listOf(1, 3), rendered)
        assertEquals(listOf(1), discarded)
        assertEquals(listOf(3), delivered)
        assertTrue(work.isEmpty())
    }

    @Test fun disconnectDiscardsInFlightAndPendingWork() {
        submit(1); submit(2)
        worker.cancel()
        step()
        assertEquals(listOf(1), discarded)
        assertTrue(delivered.isEmpty())
        assertTrue(work.isEmpty())
        submit(3); step()
        assertEquals(listOf(3), delivered)
    }

    @Test fun resultWaitingForMainThreadCanStillBeCancelled() {
        submit(1)
        work.removeFirst().run()
        worker.cancel()
        main.removeFirst().invoke()
        assertEquals(listOf(1), discarded)
        assertTrue(delivered.isEmpty())
    }

    @Test fun failureDoesNotPreventNextRender() {
        var failed = false
        worker.submit({ error("render failure") }) { failed = it.isFailure }
        step()
        assertTrue(failed)
        submit(7); step()
        assertEquals(listOf(7), delivered)
    }
}
