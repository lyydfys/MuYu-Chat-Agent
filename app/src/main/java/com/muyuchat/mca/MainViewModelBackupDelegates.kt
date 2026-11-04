package com.muyuchat.mca

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.muyuchat.mca.backup.McaBackupCoordinator

/**
 * Backup entry points of the settings page.
 *
 * They are thin on purpose: the envelope, its verification and the staging transaction all live in
 * `com.muyuchat.mca.backup`, and the ViewModel keeps owning state writes. Nothing here reads the
 * library stores, and nothing here replaces user data.
 */

internal fun MainViewModel.exportBackup(uri: Uri) {
    backupCoordinator().exportBackup(uri, viewModelScope, ::fail)
}

internal fun MainViewModel.previewBackupRestore(uri: Uri) {
    backupCoordinator().previewRestore(uri, viewModelScope, ::fail)
}

/** File name the SAF create-document dialog should suggest. */
internal fun MainViewModel.suggestedBackupFileName(): String = McaBackupCoordinator.suggestedFileName()

private fun MainViewModel.backupCoordinator(): McaBackupCoordinator = McaBackupCoordinator(
    application = getApplication(),
    snapshot = { uiStateStore.current },
    appVersionName = mcaAppVersionName(),
    showStatus = { message -> showStatus(message) }
)

/** Best-effort version label for the envelope; an unreadable package name is simply omitted. */
internal fun MainViewModel.mcaAppVersionName(): String = runCatching {
    val info = getApplication<android.app.Application>()
        .packageManager
        .getPackageInfo(getApplication<android.app.Application>().packageName, 0)
    info.versionName.orEmpty()
}.getOrDefault("")
