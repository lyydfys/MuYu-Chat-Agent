package com.muyuchat.feature.dsh.workspace.web

import android.content.Context

/**
 * Persisted choice of web kernel.
 *
 * Deliberately a tiny SharedPreferences store instead of a settings object: the value is one enum,
 * it must survive a process restart, and it must be readable before the workspace composes. The
 * preference file is listed in the shared-preferences golden so an accidental extra file fails the
 * surface test rather than appearing silently on disk.
 */
class DshWebKernelStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): DshWebKernel = DshWebKernel.fromPref(prefs.getString(KEY_KERNEL, null))

    fun write(kernel: DshWebKernel) {
        prefs.edit().putString(KEY_KERNEL, kernel.prefValue).apply()
    }

    companion object {
        /** Preference file name; also asserted by `SharedPreferencesSurfaceGoldenTest`. */
        const val PREFERENCES_NAME: String = "dsh_web_kernel"

        const val KEY_KERNEL: String = "kernel"
    }
}
