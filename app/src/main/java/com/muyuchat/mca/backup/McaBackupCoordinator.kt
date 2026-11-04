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
    private val clock: () -> Long = System::currentTimeMillis,
    /** Row reader for export; null keeps the manifest-only behaviour. */
    private val rowSource: () -> McaBackupRowSource? = { null },
    /** Restore target over the live database; null means restore is unavailable. */
    private val restoreTarget: () -> McaBackupRestoreTarget? = { null }
) {
    companion object {
        /** Suggested SAF file name; also used by the Activity when it opens the create dialog. */
        fun suggestedFileName(nowMillis: Long = System.currentTimeMillis()): String =
            "mca-backup-$nowMillis.json"

        private const val LOCAL_DIR = "backup"
        private const val LOCAL_FILE = "last-verified-backup.json"
    }

    /**
     * Builds the envelope from the current state.
     *
     * With a [rowSource] the database-backed families carry their rows and their manifest counts come
     * from what was actually read; world books (a file store) and settings stay out of the payload and
     * are reported as out of scope.
     */
    fun buildEnvelope(): McaBackupEnvelope {
        val state = snapshot()
        val settings = settingsSnapshot(state)
        val payload = rowSource()?.let(::readPayload).orEmpty()
        val counts = McaBackupTable.REQUIRED.map { table ->
            when {
                table.restorable && payload.isNotEmpty() ->
                    payload.firstOrNull { it.table == table }?.rowCount ?: 0
                table == McaBackupTable.CHAT_SESSIONS -> state.chatSessions.size
                table == McaBackupTable.CHAT_MESSAGES -> state.chatSessions.sumOf { it.messages.size }
                table == McaBackupTable.ASSISTANTS -> state.assistants.size
                table == McaBackupTable.WORLD_BOOKS -> state.worldBooks.size
                table == McaBackupTable.KNOWLEDGE_BASES -> state.knowledgeBases.size
                table == McaBackupTable.KNOWLEDGE_DOCUMENTS -> state.knowledgeDocumentCounts.values.sum()
                else -> settings.size
            }
        }
        return McaBackupEnvelope(
            createdAtMillis = clock(),
            appVersionName = appVersionName,
            tables = McaBackupTable.REQUIRED.mapIndexed { index, table ->
                McaBackupTableCount(table, counts[index])
            },
            settings = settings,
            payload = payload
        )
    }

    /** Reads every restorable table; a failing table is omitted rather than silently emptied. */
    private fun readPayload(source: McaBackupRowSource): List<McaBackupTableRows> =
        McaBackupTable.RESTORABLE.mapNotNull { table ->
            val dbTable = table.databaseTable ?: return@mapNotNull null
            runCatching {
                val raw = source.readTable(dbTable)
                McaBackupRows.build(table, raw.columns, raw.rows)
            }.getOrNull()
        }

    /** Writes a self-verified backup (rows when available) to [uri] through SAF. */
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
                val rows = envelope.payload.sumOf { it.rowCount }
                val redacted = envelope.payload.sumOf { it.redactedColumns.size }
                showStatus(
                    buildString {
                        append("备份已导出：")
                        if (envelope.payload.isEmpty()) {
                            append("${envelope.totalRows} 行清单")
                        } else {
                            append("$rows 行 / ${envelope.payload.size} 张表")
                        }
                        if (redacted > 0) append("，剔除 $redacted 个敏感列")
                        append("；世界书与助手卡为文件存储，不在本备份范围")
                    }
                )
            }.onFailure { error ->
                fail("备份导出失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Restores a picked backup into the live database inside one transaction.
     *
     * Blocking on purpose: the caller already runs on IO. Returns null on success, otherwise the
     * reason sentence. A "restore-before" snapshot of the current rows is written next to the last
     * export first — it is a human fallback only, never part of the rollback decision.
     */
    fun restoreBackup(uri: Uri): String? = runCatching {
        val target = restoreTarget()
            ?: return@runCatching "当前版本未接入数据库恢复目标，无法恢复"
        val stream = application.contentResolver.openInputStream(uri)
            ?: return@runCatching "无法读取所选文件。"
        val raw = stream.use { it.readBackupText() }
            ?: return@runCatching "备份文件超过 ${McaBackupFormat.MAX_TEXT_BYTES / 1024 / 1024} MB 上限。"
        val envelope = when (val decoded = decodeMcaBackupEnvelope(raw)) {
            is McaBackupDecodeResult.Malformed -> return@runCatching decoded.reason
            is McaBackupDecodeResult.Decoded -> decoded.envelope
        }
        when (val verification = McaBackupVerify.verify(envelope)) {
            is McaBackupVerification.Incompatible -> return@runCatching McaBackupVerify.describe(verification)
            is McaBackupVerification.Compatible -> Unit
        }
        val plan = when (val planned = McaBackupRows.plan(envelope, envelope.payload)) {
            is McaBackupPayloadPlan.Rejected -> return@runCatching planned.reason
            is McaBackupPayloadPlan.Ready -> planned
        }
        val result = McaBackupRestoreEngine(target) { writeRestoreSnapshot() }.restore(plan)
        when (result) {
            is McaDatabaseRestoreResult.Restored -> {
                showStatus(
                    "已恢复：${result.tables} 张表 / ${result.rows} 行" +
                        (result.snapshot?.let { "；$it" } ?: "")
                )
                null
            }

            is McaDatabaseRestoreResult.Failed -> {
                val message = "恢复失败：${result.failure.message}（${result.failure.code}）：${result.detail}"
                showStatus(message)
                message
            }
        }
    }.getOrElse { error -> "恢复失败：${error.message ?: error.javaClass.simpleName}" }

    /** Best-effort copy of the current rows before a restore; a failure is only a lost fallback. */
    private fun writeRestoreSnapshot(): String? {
        val source = rowSource() ?: return null
        val payload = readPayload(source)
        if (payload.isEmpty()) return null
        val envelope = McaBackupEnvelope(
            createdAtMillis = clock(),
            appVersionName = appVersionName,
            tables = McaBackupTable.REQUIRED.map { table ->
                McaBackupTableCount(table, payload.firstOrNull { it.table == table }?.rowCount ?: 0)
            },
            settings = emptyMap(),
            payload = payload
        )
        val file = File(File(application.cacheDir, LOCAL_DIR), "restore-snapshot-${clock()}.json")
        val written = McaBackupStaging.publishVerified(
            file = file,
            payload = envelope.toJson().toByteArray(Charsets.UTF_8)
        ) { staged -> decodeMcaBackupEnvelope(staged.readBackupText().orEmpty()) is McaBackupDecodeResult.Decoded }
        return if (written.succeeded) "已先备份当前数据到 cache/$LOCAL_DIR/${file.name}" else null
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
