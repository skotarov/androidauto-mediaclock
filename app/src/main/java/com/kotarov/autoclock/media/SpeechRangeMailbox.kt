package com.kotarov.autoclock.media

/**
 * Binder-to-owner mailbox. Each active utterance has one pending notification
 * at most; the latest exact reported range remains available for immediate
 * seek/pause/checkpoint. Old utterances cannot overwrite a new position.
 */
internal class SpeechRangeMailbox {
    private var id: String? = null
    private var relative: Int? = null
    private var scheduled = false

    @Synchronized fun begin(utterance: String) {
        id = utterance
        relative = null
        scheduled = false
    }

    @Synchronized fun offer(utterance: String?, start: Int, schedule: () -> Unit) {
        if (utterance == null || utterance != id) return
        relative = start
        if (!scheduled) {
            scheduled = true
            schedule()
        }
    }

    @Synchronized fun latest(): Int? = relative

    @Synchronized fun drain(): Int? {
        scheduled = false
        return relative
    }

    @Synchronized fun end(): Int? {
        val last = relative
        id = null
        relative = null
        scheduled = false
        return last
    }
}
