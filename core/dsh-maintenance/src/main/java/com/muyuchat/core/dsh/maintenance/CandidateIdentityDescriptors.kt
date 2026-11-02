package com.muyuchat.core.dsh.maintenance

import org.json.JSONObject

/**
 * Pure identity-descriptor rendering for a debug/test staged candidate (see [DshCandidateStager]).
 *
 * Only facts this build actually declares are bound per component; every other component is bound
 * to the verified whole-archive digest, which is the strongest statement available without inventing
 * a per-component manifest. The output is deterministic (fixed key order) so the same archive and
 * ABI always produce the same declared hashes and therefore the same closure fingerprint.
 *
 * These are identity documents, not executable payload bytes. No function here reads or writes a
 * file; [DshCandidateStager] owns the IO, the archive proof and the atomic promotion.
 */
@OptIn(DshCandidateStagingTestOnly::class)
@DshCandidateStagingTestOnly
internal object DshCandidateIdentityDescriptors {

    const val FORMAT = "dsh-closure-identity-v1"

    /** Profile template entry the payload manifest declares; bound by `profileSchema`. */
    const val PROFILE_TEMPLATE_ENTRY = "dshhome/profiles/web/package.json"

    fun render(
        request: DshCandidateStageRequest,
        archive: DshCandidateArchiveFacts,
        component: String
    ): ByteArray {
        val binding = when (component) {
            "source", "node", "profileSchema" -> "manifest-declared"
            else -> "verified-archive"
        }
        val common = listOf(
            "format" to FORMAT,
            "component" to component,
            "targetAbi" to request.targetAbi,
            "binding" to binding,
            "archiveSha256" to archive.sha256,
            "archiveBytes" to archive.bytes.toString(),
            "payloadVersion" to archive.payloadVersion
        )
        val binds = when (component) {
            "source" -> sortedMapOf(
                "kernelEntry" to archive.kernelEntry,
                "kernelEntrySha256" to archive.criticalHashes[archive.kernelEntry].orEmpty(),
                "officialCommit" to archive.officialCommit
            )

            "node" -> sortedMapOf(
                "jniLib" to archive.nodeJniLib,
                "nodeSha256" to archive.nodeSha256,
                "nodeBytes" to archive.nodeBytes.toString()
            )

            "nativeAbi" -> sortedMapOf(
                "targetAbi" to request.targetAbi,
                "nodeSha256" to archive.nodeSha256
            )

            "profileSchema" -> sortedMapOf(
                "profileTemplate" to PROFILE_TEMPLATE_ENTRY,
                "profileTemplateSha256" to archive.criticalHashes[PROFILE_TEMPLATE_ENTRY].orEmpty()
            )

            else -> sortedMapOf(
                "archivedFileCount" to archive.fileCount.toString(),
                "payloadVersion" to archive.payloadVersion
            )
        }
        return buildString {
            append('{')
            common.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append(JSONObject.quote(key)).append(':').append(JSONObject.quote(value))
            }
            append(",\"binds\":{")
            binds.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append(JSONObject.quote(entry.key)).append(':').append(JSONObject.quote(entry.value))
            }
            append("}}")
        }.toByteArray(Charsets.UTF_8)
    }
}

/** Verified archive facts a staged candidate identity binds to; produced only by the stager. */
internal data class DshCandidateArchiveFacts(
    val sha256: String,
    val bytes: Long,
    val payloadVersion: String,
    val officialCommit: String,
    val fileCount: Long,
    val kernelEntry: String,
    val nodeJniLib: String,
    val nodeSha256: String,
    val nodeBytes: Long,
    val criticalHashes: Map<String, String>
)
