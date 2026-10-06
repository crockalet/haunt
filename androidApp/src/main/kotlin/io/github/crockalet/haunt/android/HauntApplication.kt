package io.github.crockalet.haunt.android

import android.app.Application

/** Owns the process-wide [HauntRuntime]; get it anywhere with `HauntRuntime.from(context)`. */
class HauntApplication : Application() {
    lateinit var runtime: HauntRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        HauntNotifications.ensureChannel(this)
        runtime = HauntRuntime(this)
    }
}
