package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Deterministic `manifest.json` codec for `mca.agent.task-package.v1`.
 *
 * Serialized field order is fixed; artifacts are sorted by relative path. The manifest contains
 * only the display summary and artifact metadata — never the goal text, grants, policy, credentials,
 * opaque provider state or events. Unknown optional top-level fields in a known major are preserved
 * as inert JSON text and are never executed.
 */
object AgentTaskPackageManifestCodec {
    private val knownTopLevel = setOf("format", "version", "exportedAt", "task", "artifacts")

    fun encode(manifest: AgentTaskPackageManifest, limits: AgentTaskPackageLimits): ByteArray {
        require(manifest.exportedAtMillis >= 0L)
        val sorted = manifest.artifacts.sortedBy { it.relativePath }
        val json = buildString(512) {
            append('{')
            append("\"format\":").append(quote(AgentTaskPackageFormat.FORMAT)).append(',')
            append("\"version\":").append(AgentTaskPackageFormat.VERSION).append(',')
            append("\"exportedAt\":").append(manifest.exportedAtMillis).append(',')
            append("\"task\":{")
            append("\"createdAt\":").append(manifest.task.createdAtMillis).append(',')
            append("\"displayStatus\":").append(quote(manifest.task.displayStatus.name)).append(',')
            append("\"displayTitle\":").append(quote(manifest.task.displayTitle)).append(',')
            append("\"updatedAt\":").append(manifest.task.updatedAtMillis)
            append("},")
            append("\"artifacts\":[")
            sorted.forEachIndexed { index, artifact ->
                if (index > 0) append(',')
                append('{')
                append("\"mediaType\":").append(quote(artifact.mediaType)).append(',')
                append("\"relativePath\":").append(quote(artifact.relativePath)).append(',')
                append("\"sha256\":").append(quote(artifact.sha256)).append(',')
                append("\"sizeBytes\":").append(artifact.sizeBytes)
                append('}')
            }
            append("]}")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        check(bytes.size <= limits.maxManifestBytes) { "manifest exceeds the bounded size" }
        return bytes
    }

    fun decode(bytes: ByteArray, limits: AgentTaskPackageLimits): AgentPackageResult<AgentTaskPackageManifest> {
        if (bytes.size > limits.maxManifestBytes) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest exceeds the size limit")
        }
        val raw = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest is not valid UTF-8")
        }
        if (!raw.trimStart().startsWith("{") || !raw.trimEnd().endsWith("}")) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest must be a JSON object")
        }
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest JSON is malformed")
        }
        val format = json.opt("format")
        if (format !is String) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest format is missing")
        }
        if (format != AgentTaskPackageFormat.FORMAT) {
            val isKnownFamily = format.startsWith("mca.agent.task-package.v")
            return failure(
                if (isKnownFamily) {
                    AgentPackageErrorCode.UNSUPPORTED_VERSION
                } else {
                    AgentPackageErrorCode.MANIFEST_INVALID
                },
                "unsupported task package format"
            )
        }
        val version = json.opt("version")
        if (version !is Number || version.toInt() != AgentTaskPackageFormat.VERSION) {
            return failure(AgentPackageErrorCode.UNSUPPORTED_VERSION, "unsupported task package version")
        }
        val exportedAt = json.opt("exportedAt")
        if (exportedAt !is Number || exportedAt.toLong() < 0L) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest exportedAt is invalid")
        }
        val taskJson = json.opt("task") as? JSONObject
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest task summary is missing")
        val displayTitle = taskJson.opt("displayTitle") as? String
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest display title is missing")
        if (displayTitle.isBlank() ||
            displayTitle.codePointCount(0, displayTitle.length) > limits.maxTitleChars
        ) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest display title is invalid")
        }
        val statusName = taskJson.opt("displayStatus") as? String
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest display status is missing")
        val displayStatus = AgentDisplayStatus.entries.firstOrNull { it.name == statusName }
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest display status is unknown")
        val createdAt = taskJson.opt("createdAt") as? Number
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest createdAt is missing")
        val updatedAt = taskJson.opt("updatedAt") as? Number
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest updatedAt is missing")
        if (createdAt.toLong() < 0L || updatedAt.toLong() < createdAt.toLong()) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest task timestamps are invalid")
        }
        val artifactsJson = json.opt("artifacts") as? JSONArray
            ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest artifacts are missing")
        if (artifactsJson.length() > limits.maxArtifacts) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "manifest has too many artifacts")
        }
        val artifacts = ArrayList<AgentPackageArtifactEntry>(artifactsJson.length())
        val seen = HashSet<String>(artifactsJson.length())
        for (index in 0 until artifactsJson.length()) {
            val artifactJson = artifactsJson.opt(index) as? JSONObject
                ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact entry is not an object")
            val relativePath = artifactJson.opt("relativePath") as? String
                ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact path is missing")
            val safePath = AgentPackagePathGuard.validateArtifactPath(relativePath, limits)
                ?: return failure(AgentPackageErrorCode.UNSAFE_PATH, "artifact path is unsafe")
            val mediaType = artifactJson.opt("mediaType") as? String
                ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact media type is missing")
            if (mediaType.isBlank() || mediaType.length > limits.maxMediaTypeChars ||
                mediaType.any { it.code < 0x20 }
            ) {
                return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact media type is invalid")
            }
            val size = artifactJson.opt("sizeBytes") as? Number
                ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact size is missing")
            if (size.toLong() < 0L || size.toLong() > limits.maxEntryBytes) {
                return failure(AgentPackageErrorCode.ENTRY_TOO_LARGE, "artifact size is out of bounds")
            }
            val sha = artifactJson.opt("sha256") as? String
                ?: return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact sha256 is missing")
            if (!sha.matches(Regex("^[0-9a-f]{64}$"))) {
                return failure(AgentPackageErrorCode.MANIFEST_INVALID, "artifact sha256 is malformed")
            }
            if (!seen.add(AgentPackagePathGuard.normalizeForComparison(safePath))) {
                return failure(AgentPackageErrorCode.DUPLICATE_ENTRY, "duplicate normalized artifact path")
            }
            artifacts += AgentPackageArtifactEntry(
                relativePath = safePath,
                mediaType = mediaType,
                sizeBytes = size.toLong(),
                sha256 = sha
            )
        }
        val unknown = JSONObject()
        json.keys().forEach { key ->
            if (key !in knownTopLevel) unknown.put(key, json.opt(key))
        }
        val unknownJson = if (unknown.length() == 0) null else unknown.toString()
        if (unknownJson != null && unknownJson.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
            return failure(AgentPackageErrorCode.MANIFEST_INVALID, "unknown manifest metadata is too large")
        }
        // Unknown nested task/artifact fields are inert metadata and are ignored; unknown top-level
        // fields are preserved and never executed.
        return AgentPackageResult.Success(
            AgentTaskPackageManifest(
                exportedAtMillis = exportedAt.toLong(),
                task = AgentTaskDisplaySummary(
                    displayTitle = displayTitle,
                    displayStatus = displayStatus,
                    createdAtMillis = createdAt.toLong(),
                    updatedAtMillis = updatedAt.toLong()
                ),
                artifacts = artifacts.sortedBy { it.relativePath },
                unknownFieldsJson = unknownJson
            )
        )
    }

    private fun quote(value: String): String = JSONObject.quote(value)

    private fun <T> failure(code: AgentPackageErrorCode, message: String): AgentPackageResult<T> =
        AgentPackageResult.Failure(code, message)
}
