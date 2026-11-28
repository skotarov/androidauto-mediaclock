package com.kotarov.autoclock.media

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/** Shared event-driven observation; no polling and no receiver after the last owner leaves.
 * All calls are on the main thread. */
internal object CarProjectionMonitor {
    private val listeners = linkedSetOf<(Boolean) -> Unit>()
    private var data: LiveData<Int>? = null
    private var observer: Observer<Int>? = null
    private var transition = ProjectionTransition()

    fun subscribe(context: Context, listener: (Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        if (data == null) {
            transition = ProjectionTransition()
            val live = CarConnection(context.applicationContext).type
            val watch = Observer<Int> { type ->
                if (type != null) {
                    val event = transition.update(
                        type == CarConnection.CONNECTION_TYPE_PROJECTION,
                        type == CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
                    if (event != null) listeners.toList().forEach { it(event) }
                }
            }
            data = live
            observer = watch
            live.observeForever(watch)
        }
        return {
            listeners.remove(listener)
            if (listeners.isEmpty()) {
                observer?.let { data?.removeObserver(it) }
                observer = null
                data = null
                transition = ProjectionTransition()
            }
        }
    }
}
