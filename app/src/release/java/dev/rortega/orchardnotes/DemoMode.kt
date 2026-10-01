package dev.rortega.orchardnotes

import android.content.Intent

/** Demo data exists only in debug builds. */
object DemoMode {
    @Suppress("UNUSED_PARAMETER")
    fun handle(intent: Intent?, container: AppContainer): Boolean = false
}
