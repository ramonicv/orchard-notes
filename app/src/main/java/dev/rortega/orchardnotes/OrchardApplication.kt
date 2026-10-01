package dev.rortega.orchardnotes

import android.app.Application

class OrchardApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
