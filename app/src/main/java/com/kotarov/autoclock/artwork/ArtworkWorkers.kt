package com.kotarov.autoclock.artwork

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Both UI owners share one lazy thread; it exits after five idle seconds.
 * The content provider already runs on Binder threads and retains its
 * synchronous file contract. No second persistent render pool is needed. */
internal object ArtworkWorkers {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val executor = ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>()) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "AutoClockArtwork")
    }

    fun <T> create(discard: (T) -> Unit): LatestRenderWorker<T> =
        LatestRenderWorker(executor, { action -> main.post { action() } }, discard)
}
