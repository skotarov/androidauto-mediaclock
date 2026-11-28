package com.kotarov.autoclock.media

/** No event for initial state, repeated values, or unrelated native-car state. */
internal class ProjectionTransition {
    private var wasProjected = false
    fun update(projected: Boolean, disconnected: Boolean): Boolean? {
        if (projected && !wasProjected) {
            wasProjected = true
            return true
        }
        if (disconnected && wasProjected) {
            wasProjected = false
            return false
        }
        return null
    }
}
