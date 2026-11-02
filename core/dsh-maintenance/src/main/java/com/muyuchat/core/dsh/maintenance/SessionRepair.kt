package com.muyuchat.core.dsh.maintenance

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Precise attachment repair (D21).
 *
 * Kotlin owns the decision, not the zstd framing: [DshSessionCodec] is injected by the adapter
 * (official reader/writer or a zstd JNI helper). The policy decodes the session, walks every
 * attachment part, verifies each reference against the object store, and rewrites only the parts
 * that the diagnosis proved bad. Untouched frames keep their original bytes, and every other
 * part/line/frame is preserved by construction.
 *
 * Refusal rules mirror the reference tool: unknown format, an undecodable frame or an invalid
 * JSON line refuses the whole session instead of "fixing" it into a different shape.
 */

data class DshSessionFrame(
    val index: Int,
    val text: String,
    val decodable: Boolean,
    val raw: ByteArray,
    val detail: String = ""
)

/**
 * Decoded artifact. [frames] contains complete frames only; a torn final frame is reported through
 * [tornTailStart]/[tornTailBytes]/[tornTailLines] and is never counted as committed content.
 */
data class DshSessionContent(
    val formatId: String,
    val frames: List<DshSessionFrame>,
    val tornTailStart: Long? = null,
    val tornTailBytes: Long = 0L,
    val tornTailLines: Int = 0
) {
    val hasTornTail: Boolean get() = tornTailStart != null && tornTailBytes > 0L
}

class DshSessionDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface DshSessionCodec {
    fun decode(bytes: ByteArray): DshSessionContent

    /** Re-encodes one changed frame; unchanged frames are never passed through this method. */
    fun encodeFrame(text: String): ByteArray

    /**
     * Read-only proof that [file] starts with a real official session header. The default refuses:
     * an implementation that cannot decode headers must not let a directory check stand in for it.
     */
    fun inspectHeader(file: File): DshSessionHeaderProof = DshSessionHeaderProof.NOT_VERIFIED

    /**
     * Batch message/event counts, aligned with [files]. A `null` entry means "unknown" and must be
     * surfaced as unknown, never as zero.
     */
    fun stats(files: List<File>): List<DshSessionStats?> = List(files.size) { null }
}

/** One exact replacement: frame/line/JSON path plus the attachment that was proven bad. */
data class DshSessionRepairAction(
    val frameIndex: Int,
    val lineIndex: Int,
    val partPath: List<String>,
    val attachmentId: String,
    val status: DshAttachmentStatus,
    val replacementText: String
)

data class DshSessionRepairPlan(
    val sessionId: String,
    val file: File,
    val format: DshSessionFormat,
    val frameCount: Int,
    val lineCount: Int,
    val findings: List<DshAttachmentFinding>,
    val actions: List<DshSessionRepairAction>,
    /** A structurally torn final frame will be truncated exactly like the kernel's own heal. */
    val tornTailTruncated: Boolean = false,
    val tornTailBytes: Long = 0L,
    val refusal: DshMaintenanceFailure? = null
) {
    val needsRepair: Boolean get() = refusal == null && (actions.isNotEmpty() || tornTailTruncated)
}

sealed interface DshSessionRepairResult {
    data class Planned(val plan: DshSessionRepairPlan) : DshSessionRepairResult
    data class Applied(val transactionId: String, val plan: DshSessionRepairPlan) : DshSessionRepairResult
    data class NoAction(val reason: String) : DshSessionRepairResult
    data class Rejected(val failure: DshMaintenanceFailure) : DshSessionRepairResult
    data class RolledBack(val transactionId: String, val failure: DshMaintenanceFailure) : DshSessionRepairResult
    data class RecoveryRequired(val transactionId: String, val failure: DshMaintenanceFailure) : DshSessionRepairResult
}

class DshSessionRepairer(
    private val engine: DshMaintenanceEngine,
    private val layout: DshHomeLayout,
    private val codec: DshSessionCodec,
    private val verifier: DshAttachmentObjectVerifier,
    private val maxSessionBytes: Long = 256L * 1024L * 1024L
) {
    /** Read-only diagnosis. Never writes; the caller shows the preview before [apply]. */
    fun plan(session: DshSessionRef, running: Boolean = false): DshSessionRepairPlan {
        if (running) {
            return refusal(session, DshMaintenanceError.SESSION_RUNNING, "the session is running; settle or refuse first")
        }
        if (!session.file.isFile) {
            return refusal(session, DshMaintenanceError.SESSION_NOT_FOUND, "session log is absent")
        }
        if (session.format == DshSessionFormat.OTHER_VERSIONED) {
            return refusal(
                session,
                DshMaintenanceError.UNKNOWN_FORMAT,
                "session log version is not known to this build; refusing to rewrite"
            )
        }
        if (session.file.length() > maxSessionBytes) {
            return refusal(session, DshMaintenanceError.SPACE_INSUFFICIENT, "session exceeds the repair byte budget")
        }
        val content = try {
            codec.decode(session.file.readBytes())
        } catch (exception: Exception) {
            return refusal(session, DshMaintenanceError.UNKNOWN_FORMAT, "session could not be decoded")
        }
        return inspect(session, content)
    }

    /**
     * Applies exactly the planned replacements. Re-reads the current session, re-verifies every
     * targeted reference, stages the complete new object, lets the maintenance engine quarantine
     * the original and install the new file atomically, then re-reads the installed file.
     */
    fun apply(
        session: DshSessionRef,
        plan: DshSessionRepairPlan,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        admissionWaitMillis: Long = 0L
    ): DshSessionRepairResult {
        plan.refusal?.let { return DshSessionRepairResult.Rejected(it) }
        if (plan.actions.isEmpty() && !plan.tornTailTruncated) {
            return DshSessionRepairResult.NoAction("no bad attachment reference was found")
        }
        if (!session.file.isFile) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SESSION_NOT_FOUND, "session log disappeared before repair")
            )
        }
        val content = try {
            codec.decode(session.file.readBytes())
        } catch (exception: Exception) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.UNKNOWN_FORMAT, "session could not be decoded before repair")
            )
        }
        if (content.frames.size != plan.frameCount) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SESSION_CHANGED, "session frames changed; re-plan")
            )
        }
        if (!content.hasTornTail && plan.tornTailTruncated) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SESSION_CHANGED, "the torn tail is gone; re-plan")
            )
        }
        val rebuilt = rebuild(content, plan.actions)
            ?: return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.SESSION_CHANGED,
                    "a planned attachment reference is no longer present or is now healthy; re-plan"
                )
            )
        if (rebuilt.size.toLong() > maxSessionBytes) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.SPACE_INSUFFICIENT, "repaired session exceeds the byte budget")
            )
        }
        val workRoot = try {
            engine.newWorkDirectory("session-repair")
        } catch (io: Exception) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot create the repair staging directory")
            )
        }
        val staging = File(workRoot, session.file.name)
        try {
            DshMaintenanceIo.writeDurably(staging, rebuilt)
        } catch (io: Exception) {
            return DshSessionRepairResult.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "cannot write the repaired session copy")
            )
        }
        val expected = DshMaintenanceIo.sha256(rebuilt)
        val outcome = engine.execute(
            DshMaintenanceRequest(
                kind = DshMaintenanceKind.SESSION_REPAIR,
                requester = requester,
                quiescence = quiescence,
                targetRoot = layout.homeDir,
                steps = listOf(
                    DshMaintenanceStep(
                        action = DshStepAction.INSTALL_FILE,
                        target = session.file,
                        staging = staging,
                        expectedSha256 = expected
                    )
                ),
                healthCheckRequired = true,
                admissionWaitMillis = admissionWaitMillis,
                detail = "actions=${plan.actions.size};frames=${plan.frameCount}" +
                    (if (plan.tornTailTruncated) ";tornTail=${plan.tornTailBytes}" else ""),
                body = { verifyInstalled(session, plan) }
            )
        )
        return when (outcome) {
            is DshMaintenanceOutcome.Committed -> DshSessionRepairResult.Applied(outcome.transactionId, plan)
            is DshMaintenanceOutcome.RolledBack -> DshSessionRepairResult.RolledBack(outcome.transactionId, outcome.failure)
            is DshMaintenanceOutcome.Rejected -> DshSessionRepairResult.Rejected(outcome.failure)
            is DshMaintenanceOutcome.RecoveryRequired -> DshSessionRepairResult.RecoveryRequired(
                outcome.transactionId,
                outcome.failure
            )
        }
    }

    // ---------------------------------------------------------------- diagnosis

    private fun inspect(session: DshSessionRef, content: DshSessionContent): DshSessionRepairPlan {
        val findings = ArrayList<DshAttachmentFinding>()
        val actions = ArrayList<DshSessionRepairAction>()
        var lineCount = 0
        for (frame in content.frames) {
            if (!frame.decodable) {
                return planOf(
                    session,
                    content,
                    findings,
                    actions,
                    lineCount,
                    DshMaintenanceFailure(
                        DshMaintenanceError.UNKNOWN_FORMAT,
                        "a zstd frame cannot be decoded; refusing to rewrite"
                    )
                )
            }
            val lines = frame.text.split('\n')
            for ((lineIndex, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                lineCount++
                val parsed = try {
                    JSONObject(line)
                } catch (exception: Exception) {
                    return planOf(
                        session,
                        content,
                        findings,
                        actions,
                        lineCount,
                        DshMaintenanceFailure(
                            DshMaintenanceError.UNKNOWN_FORMAT,
                            "a session line is not valid JSON; refusing to widen the damage"
                        )
                    )
                }
                val parts = ArrayList<Pair<List<String>, JSONObject>>()
                collectParts(parsed, ArrayList(), parts)
                for ((path, part) in parts) {
                    val attachment = part.optJSONObject("attachment") ?: continue
                    val ref = DshAttachmentRef(
                        attachmentId = attachment.optString("attachmentId"),
                        mediaType = attachment.optString("mediaType").takeIf { it.isNotEmpty() },
                        name = attachment.optString("name").takeIf { it.isNotEmpty() },
                        declaredBytes = if (attachment.has("bytes")) attachment.optLong("bytes") else 0L
                    )
                    val finding = verifier.verify(ref)
                    findings.add(finding)
                    if (!finding.ok) {
                        actions.add(
                            DshSessionRepairAction(
                                frameIndex = frame.index,
                                lineIndex = lineIndex,
                                partPath = path,
                                attachmentId = ref.attachmentId,
                                status = finding.status,
                                replacementText = degradationText(ref, finding)
                            )
                        )
                    }
                }
            }
        }
        return planOf(session, content, findings, actions, lineCount, null)
    }

    private fun planOf(
        session: DshSessionRef,
        content: DshSessionContent,
        findings: List<DshAttachmentFinding>,
        actions: List<DshSessionRepairAction>,
        lineCount: Int,
        refusal: DshMaintenanceFailure?
    ) = DshSessionRepairPlan(
        sessionId = session.sessionId,
        file = session.file,
        format = session.format,
        frameCount = content.frames.size,
        lineCount = lineCount,
        findings = findings,
        actions = actions,
        tornTailTruncated = content.hasTornTail,
        tornTailBytes = content.tornTailBytes,
        refusal = refusal
    )

    private fun refusal(
        session: DshSessionRef,
        code: DshMaintenanceError,
        message: String
    ) = DshSessionRepairPlan(
        sessionId = session.sessionId,
        file = session.file,
        format = session.format,
        frameCount = 0,
        lineCount = 0,
        findings = emptyList(),
        actions = emptyList(),
        refusal = DshMaintenanceFailure(code, message)
    )

    private fun degradationText(ref: DshAttachmentRef, finding: DshAttachmentFinding): String {
        val bits = ArrayList<String>()
        ref.name?.let(bits::add)
        ref.mediaType?.let(bits::add)
        if (ref.declaredBytes > 0) bits.add("${ref.declaredBytes} bytes")
        val description = if (bits.isEmpty()) "(no description)" else bits.joinToString(" · ")
        return "[attachment unavailable] $description — ${finding.status.name}: ${finding.detail}" +
            " (only this part was degraded; the rest of the session is unchanged)"
    }

    // ---------------------------------------------------------------- rewrite

    private fun rebuild(content: DshSessionContent, actions: List<DshSessionRepairAction>): ByteArray? {
        val byFrame = actions.groupBy { it.frameIndex }
        val out = ByteArrayOutputStream()
        for (frame in content.frames) {
            val frameActions = byFrame[frame.index]
            if (frameActions.isNullOrEmpty()) {
                out.write(frame.raw)
                continue
            }
            val lines = frame.text.split('\n').toMutableList()
            for ((lineIndex, lineActions) in frameActions.groupBy { it.lineIndex }) {
                if (lineIndex !in lines.indices) return null
                val parsed = try {
                    JSONObject(lines[lineIndex])
                } catch (exception: Exception) {
                    return null
                }
                for (action in lineActions) {
                    val part = nodeAt(parsed, action.partPath) as? JSONObject ?: return null
                    val attachment = part.optJSONObject("attachment") ?: return null
                    if (attachment.optString("attachmentId") != action.attachmentId) return null
                    if (verifier.verify(
                            DshAttachmentRef(
                                attachment.optString("attachmentId"),
                                attachment.optString("mediaType").takeIf { it.isNotEmpty() },
                                attachment.optString("name").takeIf { it.isNotEmpty() },
                                if (attachment.has("bytes")) attachment.optLong("bytes") else 0L
                            )
                        ).ok
                    ) {
                        return null
                    }
                    if (!replaceAt(parsed, action.partPath, textPart(action.replacementText))) return null
                }
                lines[lineIndex] = parsed.toString()
            }
            out.write(codec.encodeFrame(lines.joinToString("\n")))
        }
        return out.toByteArray()
    }

    /** Post-install verification: zero targeted findings, same frame/line count, no new JSON damage. */
    private fun verifyInstalled(session: DshSessionRef, plan: DshSessionRepairPlan): DshMaintenanceFailure? {
        val content = try {
            codec.decode(session.file.readBytes())
        } catch (exception: Exception) {
            return DshMaintenanceFailure(
                DshMaintenanceError.HEALTH_FAILED,
                "repaired session cannot be decoded by the reader"
            )
        }
        if (content.frames.size != plan.frameCount) {
            return DshMaintenanceFailure(
                DshMaintenanceError.HEALTH_FAILED,
                "repaired session changed its frame count"
            )
        }
        if (content.hasTornTail) {
            return DshMaintenanceFailure(
                DshMaintenanceError.HEALTH_FAILED,
                "repaired session still ends with a torn frame"
            )
        }
        val targeted = plan.actions.map { it.attachmentId }.toSet()
        var lines = 0
        for (frame in content.frames) {
            if (!frame.decodable) {
                return DshMaintenanceFailure(
                    DshMaintenanceError.HEALTH_FAILED,
                    "repaired session has an undecodable frame"
                )
            }
            for (line in frame.text.split('\n')) {
                if (line.isBlank()) continue
                lines++
                val parsed = try {
                    JSONObject(line)
                } catch (exception: Exception) {
                    return DshMaintenanceFailure(
                        DshMaintenanceError.HEALTH_FAILED,
                        "repaired session has a non-JSON line"
                    )
                }
                val parts = ArrayList<Pair<List<String>, JSONObject>>()
                collectParts(parsed, ArrayList(), parts)
                for ((_, part) in parts) {
                    val attachment = part.optJSONObject("attachment") ?: continue
                    val id = attachment.optString("attachmentId")
                    if (id in targeted) {
                        return DshMaintenanceFailure(
                            DshMaintenanceError.HEALTH_FAILED,
                            "a targeted broken attachment reference is still present"
                        )
                    }
                }
            }
        }
        if (lines != plan.lineCount) {
            return DshMaintenanceFailure(
                DshMaintenanceError.HEALTH_FAILED,
                "repaired session changed its line count"
            )
        }
        return null
    }

    // ---------------------------------------------------------------- JSON walk

    private fun collectParts(
        node: Any?,
        path: MutableList<String>,
        out: MutableList<Pair<List<String>, JSONObject>>
    ) {
        when (node) {
            is JSONObject -> {
                val type = node.optString("type")
                if ((type == "image" || type == "file") && node.opt("attachment") is JSONObject) {
                    out.add(path.toList() to node)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    path.add(key)
                    collectParts(node.opt(key), path, out)
                    path.removeAt(path.size - 1)
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    path.add(index.toString())
                    collectParts(node.opt(index), path, out)
                    path.removeAt(path.size - 1)
                }
            }
        }
    }

    private fun nodeAt(root: JSONObject, path: List<String>): Any? {
        var current: Any? = root
        for (token in path) {
            current = when (val node = current) {
                is JSONObject -> node.opt(token)
                is JSONArray -> token.toIntOrNull()?.let { index ->
                    if (index in 0 until node.length()) node.opt(index) else null
                }

                else -> null
            } ?: return null
        }
        return current
    }

    private fun replaceAt(root: JSONObject, path: List<String>, replacement: JSONObject): Boolean {
        if (path.isEmpty()) return false
        val parent = if (path.size == 1) root else nodeAt(root, path.dropLast(1))
        val token = path.last()
        return when (parent) {
            is JSONObject -> {
                parent.put(token, replacement)
                true
            }

            is JSONArray -> {
                val index = token.toIntOrNull() ?: return false
                if (index !in 0 until parent.length()) return false
                parent.put(index, replacement)
                true
            }

            else -> false
        }
    }

    private fun textPart(text: String): JSONObject = JSONObject().apply {
        put("type", "text")
        put("text", text)
    }
}
