package com.kotarov.autoclock.media

/** A native timeline needs a real elapsed position and a finite duration in ms. */
internal object PlaybackTimeline {
    fun known(position: Int, duration: Int): Boolean = duration > 0 && position in 0..duration
}
