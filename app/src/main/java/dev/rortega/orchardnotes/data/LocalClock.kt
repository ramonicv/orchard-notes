package dev.rortega.orchardnotes.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Strictly increasing timestamps (milliseconds, nudged forward on ties) for writes to the
 * cache, so two cached versions of a note are never ambiguous about which came later.
 */
object LocalClock {
    private val last = AtomicLong()

    fun next(): Long = last.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
}
