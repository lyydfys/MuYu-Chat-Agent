package com.muyuchat.feature.dsh.workspace

/** Destinations inside the DSH-only workspace tab. MCA chat/image/agent tabs are not part of it. */
enum class DshWorkspaceDestination { WEB, CONSOLE }

/**
 * Pure navigation policy of the DSH-only tab (D20/D25).
 *
 * The console is an overlay destination of the same tab, never a new top-level MCA surface. It is
 * reachable only when the composition root injected a console port; without one the tab behaves
 * exactly as before. Back from the console always returns to the official DSH page, which stays
 * composed underneath so its WebView/renderer and in-flight DSH work are not disturbed.
 */
object DshWorkspaceDestinationPolicy {

    fun consoleEntryAvailable(consolePortInjected: Boolean): Boolean = consolePortInjected

    fun backDestination(current: DshWorkspaceDestination): DshWorkspaceDestination =
        when (current) {
            DshWorkspaceDestination.CONSOLE -> DshWorkspaceDestination.WEB
            DshWorkspaceDestination.WEB -> DshWorkspaceDestination.WEB
        }

    /** Back from the console is consumed by the tab; back from the WebView follows its history. */
    fun consumesBack(current: DshWorkspaceDestination): Boolean =
        current == DshWorkspaceDestination.CONSOLE
}
