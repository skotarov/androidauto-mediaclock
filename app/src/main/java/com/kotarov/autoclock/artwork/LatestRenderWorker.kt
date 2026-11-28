package com.kotarov.autoclock.artwork

import java.util.concurrent.Executor

/** Owner-thread coordinator: one in-flight job and at most one pending job.
 * A cancelled/superseded result is discarded on the owner thread. */
internal class LatestRenderWorker<T>(
    private val executor: Executor,
    private val deliver: (() -> Unit) -> Unit,
    private val discard: (T) -> Unit
) {
    private data class Job<T>(
        val revision: Long,
        val work: () -> T,
        val complete: (Result<T>) -> Unit
    )
    private var revision = 0L
    private var running = false
    private var pending: Job<T>? = null

    fun submit(work: () -> T, complete: (Result<T>) -> Unit) {
        pending = Job(++revision, work, complete)
        launch()
    }

    fun cancel() {
        revision++
        pending = null
    }

    private fun launch() {
        if (running) return
        val job = pending ?: return
        pending = null
        running = true
        executor.execute {
            val result = runCatching { job.work() }
            deliver {
                running = false
                try {
                    if (job.revision == revision) job.complete(result)
                    else result.getOrNull()?.let(discard)
                } finally {
                    launch()
                }
            }
        }
    }
}
