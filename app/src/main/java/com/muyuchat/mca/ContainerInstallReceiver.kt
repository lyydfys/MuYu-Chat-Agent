package com.muyuchat.mca

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.muyuchat.feature.dsh.workspace.container.AndroidDshContainerAssets
import com.muyuchat.feature.dsh.workspace.container.DshContainerHost
import com.muyuchat.feature.dsh.workspace.container.DshContainerLayout

/**
 * Iteration-only entry point for the container install.
 *
 * The six-step install normally needs six UI taps (drawer → DSH → ball → console → scroll →
 * install), which made each verification round cost two extra minutes plus manual attention. This
 * receiver runs the exact same pipeline from one adb command:
 *
 * ```
 * adb shell am broadcast -a com.muyuchat.mca.RUN_CONTAINER_INSTALL -p com.muyuchat.mca
 * adb logcat -s MCA-CONTAINER
 * ```
 *
 * It is a **no-op in any real release**: the build sets `MCA_ITERATION` to false unless the build
 * ran with `-PmcaIteration=true`, and the receiver checks it before doing anything. It never
 * accepts a command, a path or a URL from the caller — only "run the fixed pipeline".
 */
class ContainerInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.MCA_ITERATION) {
            // Never reachable through a published build.
            return
        }
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val layout = DshContainerLayout.forApp(
            filesDir = app.filesDir.absolutePath,
            nativeLibraryDir = app.applicationInfo.nativeLibraryDir
        )
        val assets = AndroidDshContainerAssets(app.assets)
        Log.i(TAG, "broadcast install start")
        // Same entry point the console uses, so this exercises the production ownership model.
        DshContainerHost.of(app).startInstall(assets, layout)
    }

    companion object {
        const val ACTION = "com.muyuchat.mca.RUN_CONTAINER_INSTALL"
        private const val TAG = "MCA-CONTAINER"
    }
}
