package com.apps.dsimpletools.speak

import android.app.Application
import android.util.Log

/**
 * No DI framework here on purpose (see project README) — cross-component
 * access in this phase is done via small companion-object singletons (see
 * [com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService]),
 * not via anything hung off this Application class. This class exists as an
 * anchor for future phases (e.g. loading an ASR model singleton at startup).
 */
class SpeakApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Speak application onCreate")
    }

    companion object {
        private const val TAG = "Speak.App"
    }
}
