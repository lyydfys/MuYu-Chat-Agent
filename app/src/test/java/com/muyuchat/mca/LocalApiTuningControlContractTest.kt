package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8/S40-2B precondition: behavioral contract for the Local API control-plane create path.
 *
 * Pins the rejection matrix order, the two state writes, the failure rollback and the 202
 * acceptance payload of `createTuningJob(request, idempotencyKey)`, plus the idempotency
 * wrapper's delegation. P8/S40-2B-1 moved the main implementation to
 * `LocalApiControlPlaneOperations.localApiCreateTuningJobFor`; the main-body facts are read
 * from there (call forms remapped to the control port), while the wrapper facts stay on
 * MainViewModel. Assertions are on the facts, not on the file that hosts them.
 */
class LocalApiTuningControlContractTest {
    @Test
    fun createTuningJobKeepsItsRejectionMatrixOrderAndPayloads() {
        val source = mainViewModelSource()
        val body = functionBody(
            localApiControlPlaneOperationsSource(),
            "internal fun localApiCreateTuningJobFor("
        )

        val ordered = listOf(
            "val identity = activeRuntimeIdentity()",
            "code = \"model_not_loaded\"",
            "val model = activeModelForRuntimeProfile()",
            "code = \"profile_not_loaded\"",
            "if (!localApiModelMatches(request.modelId, state, model))",
            "code = \"model_mismatch\"",
            "val debugMode = localApiDebugMode(request.mode)",
            "code = \"invalid_tuning_mode\"",
            "val requestedPreference = localApiPerformancePreference(request.performancePreference)",
            "code = \"invalid_performance_preference\"",
            "activeTuningJob(identity.identityHash)",
            "code = \"tuning_state_unavailable\"",
            "localApiTuningConflict(",
            "localApiExclusiveLifecycleConflict(\"create\")",
            "createTuningJob(",
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
        assertTrue(body.contains("assignActiveTuningJobId(job.jobId)"))
        assertTrue(body.contains("tuningJobState = AgentTuningJobState.QUEUED"))
        assertTrue(body.contains("tuningPhase = \"等待取得调优生命周期\""))
        assertTrue(body.contains("statusMessage = \"Local API 已创建调优任务，正在进入安全候选搜索。\""))
        assertTrue(body.contains("startAgentTuning("))
        assertTrue(body.contains("precreatedJob = job"))

        // failure rollback keeps its persisted transition, both state writes and the conflict reply
        assertTrue(body.contains("state = PersistedTuningJobState.FAILED"))
        assertTrue(body.contains("phase = \"START_REJECTED\""))
        assertTrue(body.contains("failureCode = \"LIFECYCLE_CONFLICT\""))
        assertTrue(body.contains("assignActiveTuningJobId(null)"))
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
        for (method in listOf("pauseTuningJob", "resumeTuningJob", "cancelTuningJob", "applyTuningJob", "rollbackTuning")) {
            val count = Regex("override fun $method\\(").findAll(source).count()
            assertEquals("$method must keep exactly its two overloads", 2, count)
        }
    }

    private fun mainViewModelSource(): String = sourceFile("MainViewModel.kt")

    @Test
    fun rollbackTuningKeepsItsValidationChainAndAcceptPayload() {
        // P8/S40-2B-3: the rollback overload's validation chain, the gated state writes and the
        // 202 accept payload moved to LocalApiControlPlaneOperations unchanged; the rollback job
        // launch and its lifecycle ownership stay on MainViewModel behind the control port.
        val body = functionBody(
            localApiControlPlaneOperationsSource(),
            "internal fun localApiRollbackTuningFor("
        )

        val ordered = listOf(
            "val identity = activeRuntimeIdentity()",
            "\"model_not_loaded\"",
            "val model = activeModelForRuntimeProfile()",
            "currentRuntimeState(identity.identityHash)",
            "\"rollback_state_unavailable\"",
            "\"rollback_not_available\"",
            "runtimeState.activeJob?.let { activeJob ->",
            "localApiTuningConflict(",
            "localApiExclusiveLifecycleConflict(\"rollback\")",
            "val active = runtimeState.activeProfile",
            "\"rollback_not_available\"",
            "val rollbackTarget = active.parentCommittedProfileId",
            "runtimeState.pointers?.lastKnownGoodProfileId?.takeIf { it != active.profileId }",
            "\"rollback_not_available\"",
            "updateUiState {",
            "tuningPhase = \"等待回滚生命周期\"",
            "statusMessage = \"Local API 已接受 rollback，正在取得运行时生命周期。\"",
            "launchTuningRollback(identity, model)",
            ".put(\"action\", \"rollback\")",
            ".put(\"targetProfileId\", rollbackTarget)",
            "httpStatus = 202"
        )
        var cursor = 0
        for (fact in ordered) {
            val at = body.indexOf(fact, cursor)
            assertTrue("expected [$fact] after offset $cursor", at >= 0)
            cursor = at + fact.length
        }
        assertTrue(body.contains("engineLifecycle = AgentEngineLifecycle.ROLLING_BACK"))
        assertTrue(body.contains("tuningJobState = AgentTuningJobState.RECOVERING"))
        assertTrue(body.contains(".put(\"accepted\", true)"))
        assertTrue(body.contains(".put(\"idempotencyKeyAccepted\", idempotencyKey.isNotBlank())"))
        assertEquals(6, Regex("localApiTuningRejected\\(").findAll(body).count())
        assertEquals(1, Regex("localApiTuningConflict\\(").findAll(body).count())
    }

    @Test
    fun applyTuningJobKeepsItsValidationMatrixAndAcceptPayload() {
        // P8/S40-2B-2: the apply overload's validation chain, the gated state writes and the
        // 202 accept payload moved to LocalApiControlPlaneOperations unchanged; the apply job
        // launch and its lifecycle ownership stay on MainViewModel behind the control port.
        val body = functionBody(
            localApiControlPlaneOperationsSource(),
            "internal fun localApiApplyTuningJobFor("
        )

        val ordered = listOf(
            "val identity = activeRuntimeIdentity()",
            "\"model_not_loaded\"",
            "if (activeModelForRuntimeProfile() == null) {",
            "tuningJob(jobId)",
            "code = \"tuning_job_not_found\"",
            "job.identityKey != identity.identityHash",
            "\"tuning_job_model_mismatch\"",
            "localApiExclusiveLifecycleConflict(\"apply\", allowedStagedJobId = jobId)",
            "pendingTransaction(identity.identityHash)",
            "\"pending_profile_unavailable\"",
            "pending.journal.jobId != jobId",
            "\"staged_candidate_not_found\"",
            "TuningJournalState.STAGED.name",
            "\"staged_candidate_not_ready\"",
            "PersistedTuningJobState.PAUSED.name",
            "\"tuning_apply_state_conflict\"",
            "assignActiveTuningJobId(jobId)",
            "setTuningCancelRequested(false)",
            "setTuningPauseRequested(false)",
            "launchTuningApply(pending)",
            ".put(\"action\", \"apply\")",
            "httpStatus = 202"
        )
        var cursor = 0
        for (fact in ordered) {
            val at = body.indexOf(fact, cursor)
            assertTrue("expected [$fact] after offset $cursor", at >= 0)
            cursor = at + fact.length
        }
        assertTrue(body.contains("engineLifecycle = AgentEngineLifecycle.RELOADING"))
        assertTrue(body.contains("tuningJobState = AgentTuningJobState.VALIDATING"))
        assertTrue(body.contains("tuningPhase = \"等待应用 staged candidate\""))
        assertTrue(body.contains("statusMessage = \"Local API 已接受 apply，正在取得运行时生命周期。\""))
        assertTrue(body.contains(".put(\"accepted\", true)"))
        assertTrue(body.contains(".put(\"idempotencyKeyAccepted\", idempotencyKey.isNotBlank())"))
        assertEquals(8, Regex("localApiTuningRejected\\(").findAll(body).count())
        assertTrue(body.contains("tuningJobPublicJson(job)"))
    }

    private fun localApiControlPlaneOperationsSource(): String =
        sourceFile("LocalApiControlPlaneOperations.kt")

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
