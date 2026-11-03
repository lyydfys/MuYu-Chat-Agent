package com.muyuchat.mca.dsh.overlay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.muyuchat.mca.MainActivity

/**
 * Intents the app-outside display uses to bring the user back to DSH.
 *
 * Opening the workspace is a plain Activity start with one boolean extra; [MainActivity] consumes the
 * extra once and switches to the DSH tab. The DSH WebView then restores its own last path, so a tap on
 * the mark or on a reply bubble returns to the conversation the reply belongs to. No session id, URL
 * or token is ever put in an Intent (the launch token must stay out of Intents).
 */
internal object DshOverlayIntents {

    const val EXTRA_OPEN_WORKSPACE = "com.muyuchat.mca.dsh.overlay.OPEN_WORKSPACE"

    /** Starts the app on the DSH workspace tab; safe to call from a Service context. */
    fun openWorkspace(context: Context) {
        runCatching { context.startActivity(workspaceIntent(context)) }
    }

    fun workspaceIntent(context: Context): Intent =
        Intent(context.applicationContext, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            .putExtra(EXTRA_OPEN_WORKSPACE, true)

    /** Notification content intent: same target, as a stable PendingIntent. */
    fun workspacePendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context.applicationContext,
            requestCode,
            workspaceIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
