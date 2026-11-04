package com.muyuchat.mca.backup

import android.app.Application
import android.net.Uri
import com.muyuchat.mca.MainUiState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Wires the backup envelope to the two user-visible actions: export a manifest through the existing
 * SAF document channel, and check a picked backup file against this build.
 *
 * What this slice deliberately does *not* do: it never writes to the live library. The chat,
 * assistant, world-book and knowledge stores own their own transactional import paths and the
 * Android backup rules keep `mca.db` out of device transfer on purpose, so a row-level restore is a
 * separate, reviewed change. The verification result and the rendered copy say exactly that, so a
 * user cannot mistake "校验通过" for "数据已恢复".
 *
 * The export path still uses [McaBackupStaging]: the JSON is written to a staging file, re-read and
 * re-verified, published atomically into app storage, and only then copied to the SAF destination.
 */
internal class McaBackupCoordinator(
    private val application: Application,
    private val snapshot: () -> MainUiState,
    private val appVersionName: String,
    private val showStatus: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** Suggested SAF file name; also used by the Activity when it opens the create dialog. */
        fun suggestedFileName(nowMillis: Long = System.currentTimeMillis()): String =
            "mca-backup-$nowMillis.json"

        private const val LOCAL_DIR = "backup"
        private const val LOCAL_FILE = "last-verified-backup.json"
    }

    /** Builds the envelope from the current read-only state. Counts only; no row bodies. */
    fun buildEnvelope(): McaBackupEnvelope {
        val state = snapshot()
        val settings = settingsSnapshot(state)
        return McaBackupEnvelope(
            createdAtMillis = clock(),
            appVersionName = appVersionName,
            tables = listOf(
                McaBackupTableCount(McaBackupTable.CHAT_SESSIONS, state.chatSessions.size),
                McaBackupTableCount(McaBackupTable.CHAT_MESSAGES, state.chatSessions.sumOf { it.messages.size }),
                McaBackupTableCount(McaBackupTable.ASSISTANTS, state.assistants.size),
                McaBackupTableCount(McaBackupTable.WORLD_BOOKS, state.worldBooks.size),
                McaBackupTableCount(McaBackupTable.KNOWLEDGE_BASES, state.knowledgeBases.size),
                McaBackupTableCount(
                    McaBackupTable.KNOWLEDGE_DOCUMENTS,
                    state.knowledgeDocumentCounts.values.sum()
                ),
                McaBackupTableCount(McaBackupTable.SETTINGS, settings.size)
            ),
            settings = settings
        )
    }

    /** Writes a self-verified manifest to [uri] (SAF). Never touches the app's data files. */
    fun exportBackup(uri: Uri, scope: CoroutineScope, fail: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val envelope = buildEnvelope()
                val compatible = McaBackupVerify.verify(envelope)
                check(compatible is McaBackupVerification.Compatible) {
                    "内部校验未通过：${McaBackupVerify.describe(compatible)}"
                }
                val payload = envelope.toJson().toByteArray(Charsets.UTF_8)
                val localFile = File(File(application.filesDir, LOCAL_DIR), LOCAL_FILE)
                val published = McaBackupStaging.publishVerified(localFile, payload) { staged ->
                    McaBackupVerify.verify(staged.readBackupText().orEmpty()).isCompatible
                }
                check(published.succeeded) { published.message }
                val stream = application.contentResolver.openOutputStream(uri)
                    ?: error("无法写入所选位置。")
                stream.use { it.write(payload) }
                envelope
            }.onSuccess { envelope ->
                showStatus(
                    "备份已导出：${envelope.totalRows} 行 / ${envelope.tables.size} 张表（不含聊天正文与凭据）"
                )
            }.onFailure { error ->
                fail("备份导出失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Verifies a picked backup file and reports compatibility. Deliberately read-only: the live
     * library is not opened, replaced or migrated by this call.
     */
    fun previewRestore(uri: Uri, scope: CoroutineScope, fail: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val stream = application.contentResolver.openInputStream(uri)
                    ?: error("无法读取所选文件。")
                val raw = stream.use { it.readBackupText() }
                    ?: error("备份文件超过 ${McaBackupFormat.MAX_TEXT_BYTES / 1024 / 1024} MB 上限。")
                McaBackupVerify.verify(raw)
            }.onSuccess { verification ->
                showStatus(restoreNotice(verification))
            }.onFailure { error ->
                fail("备份校验失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /**
     * The user-facing sentence for a verification result. A compatible file does **not** claim the
     * data was restored; the second half of the sentence is the honest status of this build.
     */
    fun restoreNotice(verification: McaBackupVerification): String = when (verification) {
        is McaBackupVerification.Compatible ->
            "${McaBackupVerify.describe(verification)}；本次只做校验，未改动现有数据"

        is McaBackupVerification.Incompatible ->
            "无法恢复：${McaBackupVerify.describe(verification)}"
    }

    /** Non-secret settings only. [McaBackupSecrets] is the last filter before encoding. */
    private fun settingsSnapshot(state: MainUiState): Map<String, String> {
        val params = state.params
        return McaBackupSecrets.sanitizeSettings(
            mapOf(
                "generation.n_ctx" to params.nCtx.toString(),
                "generation.n_predict" to params.nPredict.toString(),
                "generation.temperature" to params.temperature.toString(),
                "generation.top_p" to params.topP.toString(),
                "generation.top_k" to params.topK.toString(),
                "generation.repeat_penalty" to params.repeatPenalty.toString(),
                "preference.mode" to state.preference.mode.name,
                "local_api.enabled" to state.apiEnabled.toString(),
                "local_api.open_port" to state.restEnabled.toString(),
                "web_search.enabled" to state.webSearchConfig.enabled.toString()
            )
        )
    }
}

/** Reads at most [maxBytes] of UTF-8 text; null when the source is larger (never truncated). */
internal fun InputStream.readBackupText(maxBytes: Int = McaBackupFormat.MAX_TEXT_BYTES): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toString("UTF-8")
}
