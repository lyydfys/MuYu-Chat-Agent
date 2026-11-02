package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable evidence that one cloud model really completed a native tool round (N05d).
 *
 * N05e role: a diagnostics/seed journal of real provider rounds, not the activation gate. The
 * picker and the runtime binding share `AgentNativeToolCapabilities`, which already covers the
 * controlled-verified Responses wire; this store can still gate a format that has no controlled
 * verification. No record means the prompted compatibility path for such formats.
 *
 * The store is bounded and fail-closed: a corrupt file makes reads return false and writes throw,
 * so damage is visible instead of silently enabling an unverified protocol.
 */
class AgentNativeToolEvidenceStore(
    rootDirectory: File
) {
    private val root: File = File(rootDirectory.absoluteFile, "native-tools").also { it.mkdirs() }
    private val file = File(root, "evidence.json")
    private val lock = Any()
    private val codec = StrictAgentJsonCodec()

    fun isVerified(providerId: String, modelId: String): Boolean = synchronized(lock) {
        if (providerId.isBlank() || modelId.isBlank()) return false
        if (!file.isFile) return false
        val records = try {
            readAll()
        } catch (_: Exception) {
            return false
        }
        records.any { it.providerId == providerId && it.modelId == modelId }
    }

    /** Records one successful real round. Called only by a verified probe/round owner. */
    fun recordVerified(providerId: String, modelId: String, wireVersion: String, atMillis: Long) {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(wireVersion.isNotBlank()) { "wireVersion must not be blank" }
        synchronized(lock) {
            val records = try {
                readAll()
            } catch (failure: Exception) {
                error("native tool evidence store is corrupt: ${failure.javaClass.simpleName}")
            }
            val updated = records
                .filterNot { it.providerId == providerId && it.modelId == modelId }
                .plus(Evidence(providerId, modelId, wireVersion, atMillis))
                .sortedWith(compareBy({ it.providerId }, { it.modelId }))
                .take(MAX_RECORDS)
            writeAll(updated)
        }
    }

    private fun readAll(): List<Evidence> {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_BYTES) error("native tool evidence file is oversized")
        val parsed = codec.parse(file.readText(Charsets.UTF_8)) as? AgentJsonParseResult.Parsed
            ?: error("native tool evidence is not valid JSON")
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap()
            ?: error("native tool evidence must be an object")
        if ((fields["schema"] as? AgentJsonValue.Num)?.literal?.toIntOrNull() != SCHEMA) {
            error("native tool evidence has an unsupported schema")
        }
        val rows = (fields["records"] as? AgentJsonValue.Arr)?.items
            ?: error("native tool evidence has no records")
        return rows.map { row ->
            val map = (row as? AgentJsonValue.Obj)?.fields?.toMap()
                ?: error("native tool evidence row must be an object")
            Evidence(
                providerId = (map["providerId"] as? AgentJsonValue.Str)?.value
                    ?: error("providerId is missing"),
                modelId = (map["modelId"] as? AgentJsonValue.Str)?.value
                    ?: error("modelId is missing"),
                wireVersion = (map["wireVersion"] as? AgentJsonValue.Str)?.value
                    ?: error("wireVersion is missing"),
                verifiedAtMillis = (map["verifiedAtMillis"] as? AgentJsonValue.Num)?.literal
                    ?.toLongOrNull() ?: error("verifiedAtMillis is missing")
            )
        }
    }

    private fun writeAll(records: List<Evidence>) {
        val json = AgentJsonValue.Obj(
            listOf(
                "schema" to AgentJsonValue.Num(SCHEMA.toString()),
                "records" to AgentJsonValue.Arr(
                    records.map { record ->
                        AgentJsonValue.Obj(
                            listOf(
                                "providerId" to AgentJsonValue.Str(record.providerId),
                                "modelId" to AgentJsonValue.Str(record.modelId),
                                "wireVersion" to AgentJsonValue.Str(record.wireVersion),
                                "verifiedAtMillis" to AgentJsonValue.Num(record.verifiedAtMillis.toString())
                            )
                        )
                    }
                )
            )
        )
        val bytes = codec.write(json).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) error("native tool evidence would be oversized")
        val temp = File(root, "evidence.json.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (failure: Exception) {
            temp.delete()
            error("native tool evidence could not be written: ${failure.javaClass.simpleName}")
        }
    }

    private data class Evidence(
        val providerId: String,
        val modelId: String,
        val wireVersion: String,
        val verifiedAtMillis: Long
    )

    private companion object {
        const val SCHEMA = 1
        const val MAX_BYTES = 256 * 1024
        const val MAX_RECORDS = 256
    }
}
