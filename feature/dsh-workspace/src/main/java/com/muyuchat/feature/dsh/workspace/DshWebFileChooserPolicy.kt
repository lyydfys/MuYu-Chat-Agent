package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.capability.DshWorkspaceAccess

/**
 * Access classification of the WebView file chooser.
 *
 * `onShowFileChooser` is a one-shot `<input type="file">` pick: the page receives temporary read
 * access to the selected documents and nothing else. It must never launch
 * `ACTION_OPEN_DOCUMENT_TREE` and must never be projected as a persistent workspace tree grant;
 * only the capability bridge's `OpenDocumentTree` owner can produce that.
 */
object DshWebFileChooserPolicy {

    const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
    const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
    const val ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE"

    /** The typed access a chooser result confers, for any projection that needs to state it. */
    val access: DshWorkspaceAccess = DshWorkspaceAccess.oneShotFilePick()

    /**
     * Refuses a directory-tree intent instead of silently treating it as a chooser.
     *
     * Any other action (including a null one produced by an unusual WebView) is left to the
     * existing chooser flags, so this guard only removes the one path that would widen access.
     */
    fun refusalFor(action: String?): DshFileChooserRefusal? =
        if (action == ACTION_OPEN_DOCUMENT_TREE) {
            DshFileChooserRefusal.TREE_GRANT_NOT_SUPPORTED
        } else {
            null
        }

    /** True for the two chooser actions the system file picker may legitimately return. */
    fun isOneShotChooser(action: String?): Boolean =
        action == ACTION_GET_CONTENT || action == ACTION_OPEN_DOCUMENT
}

/** Why a file-chooser intent was refused before the system dialog opened. */
enum class DshFileChooserRefusal {
    /** A directory tree is a persistent grant and only the capability owner may request one. */
    TREE_GRANT_NOT_SUPPORTED
}
