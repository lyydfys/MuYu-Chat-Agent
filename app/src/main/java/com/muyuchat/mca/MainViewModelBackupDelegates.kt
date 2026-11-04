package com.muyuchat.mca

import android.app.Application

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.muyuchat.mca.backup.AndroidMcaBackupRestoreTarget
import com.muyuchat.mca.backup.AndroidMcaBackupRowSource
import com.muyuchat.mca.backup.McaBackupCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Backup entry points of the settings page.
 *
 * They are thin on purpose: the envelope, its verification, the row codec and the restore transaction
 * all live in `com.muyuchat.mca.backup`, and the ViewModel keeps owning state writes. The database
 * adapters are built per call, so a restore never holds a stale `SupportSQLiteDatabase` handle and the
 * coordinator stays the single place that talks to Room.
 */

internal fun MainViewModel.exportBackup(uri: Uri) {
    backupCoordinator().exportBackup(uri, viewModelScope, ::fail)
}

/** Verifies a picked backup without writing anything. */
internal fun MainViewModel.previewBackupRestore(uri: Uri) {
    backupCoordinator().previewRestore(uri, viewModelScope, ::fail)
}

/**
 * Restores a picked backup into the live database: one transaction, all-or-nothing.
 *
 * The coordinator publishes success/failure on the status surface; the returned reason is only the
 * fallback log line here.
 */
internal fun MainViewModel.restoreBackup(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
        val failure = backupCoordinator().restoreBackup(uri)
        if (failure != null) fail(failure)
    }
}

/** File name the SAF create-document dialog should suggest. */
internal fun MainViewModel.suggestedBackupFileName(): String = McaBackupCoordinator.suggestedFileName()

private fun MainViewModel.backupCoordinator(): McaBackupCoordinator = McaBackupCoordinator(
    application = getApplication(),
    snapshot = { uiStateStore.current },
    appVersionName = mcaAppVersionName(),
    showStatus = { message -> showStatus(message) },
    // Both adapters are resolved lazily and defensively: a device where the database cannot be opened
    // must fail the action with a message, not crash the settings page.
    rowSource = { runCatching { AndroidMcaBackupRowSource(getApplication<Application>()) }.getOrNull() },
    restoreTarget = { runCatching { AndroidMcaBackupRestoreTarget(getApplication<Application>()) }.getOrNull() }
)

/** Best-effort version label for the envelope; an unreadable package name is simply omitted. */
internal fun MainViewModel.mcaAppVersionName(): String = runCatching {
    val info = getApplication<android.app.Application>()
        .packageManager
        .getPackageInfo(getApplication<android.app.Application>().packageName, 0)
    info.versionName.orEmpty()
}.getOrDefault("")
