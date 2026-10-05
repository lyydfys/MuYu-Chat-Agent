package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8/S40-2B precondition: behavioral contract for the Local API control-plane create path.
 *
 * Pins the rejection matrix order, the two state writes, the failure rollback and the 202
 * acceptance payload of `createTuningJob(request, idempotencyKey)` inside MainViewModel's
 * `LocalApiRuntime.controlPlane` object, plus the idempotency wrapper's delegation. Written
 * BEFORE the S40-2B extraction so the move cannot silently reorder guards or drop payload
 * fields; assertions are on the facts, not on the file that hosts them.
 */
class LocalApiTuningControlContractTest {
    @Test
    fun createTuningJobKeepsItsRejectionMatrixOrderAndPayloads() {
        val source = mainViewModelSource()
        val body = functionBody(source, "override fun createTuningJob(")

        val ordered = listOf(
            "val identity = activeRuntimeIdentity",
            "code = \"model_not_loaded\"",
            "val model = activeModelForRuntimeProfile",
            "code = \"profile_not_loaded\"",
            "if (!localApiModelMatches(request.modelId, state, model))",
            "code = \"model_mismatch\"",
            "val debugMode = localApiDebugMode(request.mode)",
            "code = \"invalid_tuning_mode\"",
            "val requestedPreference = localApiPerformancePreference(request.performancePreference)",
            "code = \"invalid_performance_preference\"",
            "runtimeProfileStore.activeTuningJob(identity.identityHash)",
            "code = \"tuning_state_unavailable\"",
            "localApiTuningConflict(",
            "localApiExclusiveLifecycleConflict(\"create\")",
            "runtimeProfileStore.createTuningJob(",
            "code = \"tuning_create_conflict\""
        )
        var cursor = 0
        for (fact in ordered) {
            val at = body.indexOf(fact, cursor)
            assertTrue("expected [$fact] after offset $cursor", at >= 0)
            cursor = at + fact.length
        }

        assertTrue(body.contains(".put(\"requestedModel\", request.modelId)"))
        assertTrue(body.contains(".put(\"loadedModelId\", state.loadedModelId)"))
        assertTrue(body.contains(".put(\"loadedModelName\", state.loadedModelName)"))
        assertTrue(body.contains("phase = \"QUEUED\""))
        assertTrue(body.contains("activeTuningJobId = job.jobId"))
        assertTrue(body.contains("tuningJobState = AgentTuningJobState.QUEUED"))
        assertTrue(body.contains("tuningPhase = \"等待取得调优生命周期\""))
        assertTrue(body.contains("statusMessage = \"Local API 已创建调优任务，正在进入安全候选搜索。\""))
        assertTrue(body.contains("startAgentTuning("))
        assertTrue(body.contains("precreatedJob = job"))

        // failure rollback keeps its persisted transition, both state writes and the conflict reply
        assertTrue(body.contains("state = PersistedTuningJobState.FAILED"))
        assertTrue(body.contains("phase = \"START_REJECTED\""))
        assertTrue(body.contains("failureCode = \"LIFECYCLE_CONFLICT\""))
        assertTrue(body.contains("activeTuningJobId = null"))
        assertTrue(body.contains("tuningJobState = AgentTuningJobState.FAILED"))
        assertTrue(body.contains("tuningPhase = \"启动冲突\""))

        // accepted reply
        assertTrue(body.contains(".put(\"accepted\", true)"))
        assertTrue(body.contains(".put(\"idempotencyKeyAccepted\", idempotencyKey.isNotBlank())"))
        assertTrue(body.contains("httpStatus = 202"))

        // the rollback conflict reply and the primary conflict reply both carry action=create
        assertEquals(2, Regex("localApiTuningConflict\\(").findAll(body).count())

        // idempotency wrapper (second overload) delegates through the durable control helper
        val wrapperStart = source.indexOf("idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext")
        assertTrue(wrapperStart >= 0)
        val wrapper = source.substring(wrapperStart, (wrapperStart + 400).coerceAtMost(source.length))
        assertTrue(wrapper.contains("runDurableLocalApiControl(idempotency)"))
        assertTrue(wrapper.contains("createTuningJob(request, idempotency.key)"))
    }

    @Test
    fun controlPlaneProjectionsAndTuningControlOverloadsStayPinned() {
        // S40-2B-2/2B-3 precondition: the one-line projection delegates and every tuning-control
        // method's String/IdempotencyContext overload pair. Extracting any of these must keep both
        // surfaces accounted for.
        val source = mainViewModelSource()
        assertTrue(source.contains("override fun profileJson(): String = runtimeProfileJson()"))
        assertTrue(source.contains("override fun tuningJson(): String = tuningStateJson()"))
        for (method in listOf("pauseTuningJob", "resumeTuningJob", "cancelTuningJob")) {
            val count = Regex("override fun $method\\(").findAll(source).count()
            assertEquals("$method must keep exactly its two overloads", 2, count)
        }
    }

    private fun mainViewModelSource(): String = sourceFile("MainViewModel.kt")

    private fun sourceFile(fileName: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            listOf(
                File(root, "app/src/main/java/com/muyuchat/mca/$fileName"),
                File(root, "src/main/java/com/muyuchat/mca/$fileName")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $fileName")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing opening brace for: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace, index + 1)
                }
            }
        }
        error("Unbalanced braces for: $signature")
    }
}
