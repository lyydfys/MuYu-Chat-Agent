package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.download.*
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.QnnRuntimeProfileSelector
import com.muyuchat.core.nativebridge.NativeQnnBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：ManagedDownloadProgress / InstalledManagedDownload 两个
 * internal data class 自 ManagedModelDownloadInstaller.kt 逐字节平移；零改写（原样顶层）。
 */

internal data class ManagedDownloadProgress(
    val downloadFileName: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.RUNNING,
    val phase: com.muyuchat.feature.modelhub.ModelHubDownloadPhase =
        com.muyuchat.feature.modelhub.ModelHubDownloadPhase.QUEUED,
    val failureSource: com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource? = null,
    val statusMessage: String = "正在准备下载…",
    val integrityStatus: String = "UNKNOWN",
    val integrityMessage: String? = null,
    val executionStatus: String = "UNKNOWN",
    val executionMessage: String? = null
)

internal data class InstalledManagedDownload(
    val modelId: String? = null,
    val imageId: String? = null,
    val projector: Boolean = false,
    val message: String,
    val integrityStatus: String = "PASSED",
    val integrityMessage: String? = null,
    val executionStatus: String = "NOT_APPLICABLE",
    val executionMessage: String? = null
)
