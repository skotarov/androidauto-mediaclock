package com.kotarov.autoclock.media

import java.util.PriorityQueue

/** Keep only the first N entries in sorted order regardless of provider order. */
internal class BoundedFolderOrder<T>(private val limit: Int, private val order: Comparator<T>) {
    init { require(limit > 0) }
    private val heap = PriorityQueue<T>(limit, order.reversed())
    fun add(item: T) {
        if (heap.size < limit) heap.add(item)
        else if (order.compare(item, heap.peek()) < 0) {
            heap.poll()
            heap.add(item)
        }
    }
    fun sorted(): List<T> = heap.sortedWith(order)
}
