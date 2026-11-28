package com.kotarov.autoclock.artwork

import android.graphics.Bitmap
import java.lang.ref.WeakReference

/** Borrow already-published bitmaps without owning another bitmap cache.
 * Media metadata retains the originals; the provider never recycles them.
 * One main and one mini entry; old sizes are rendered separately to preserve
 * the established host-specific artwork resolution and pixel quality. */
object PublishedArtwork {
    private data class Entry(val key: String, val bitmap: WeakReference<Bitmap>)
    private var main: Entry? = null
    private var mini: Entry? = null

    @Synchronized fun publish(key: String, bitmap: Bitmap, isMini: Boolean) {
        val entry = Entry(key, WeakReference(bitmap))
        if (isMini) mini = entry else main = entry
    }

    @Synchronized fun borrow(key: String, size: Int, isMini: Boolean): Bitmap? {
        val entry = if (isMini) mini else main
        if (entry?.key != key) return null
        return entry.bitmap.get()?.takeIf {
            !it.isRecycled && it.width == size && it.height == size
        }
    }

    @Synchronized fun clear() {
        main = null
        mini = null
    }
}
