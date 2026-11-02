package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P03 contract: the user-facing chat stop freezes the exact local attempt identity of the run it
 * stops, and never derives a target from the engine token that happens to be current.
 *
 * The identity half is behavior (the ownership gate returns the registered id atomically with the
 * cancellation); the wiring half reads the production source because the adapter itself needs the
 * whole ViewModel/engine assembly, which this JVM test source set does not construct.
 */
class ChatAttemptStopIdentityContractTest {

    private fun startedRun(runs: ChatRunOwnership): Triple<UiGenerationReservation, Any, ChatAttemptIdentity> {
        val reservation = requireNotNull(runs.reserveStart())
        val owner = Job()
        assertTrue(runs.activate(reservation, owner))
        assertTrue(runs.markPhase(reservation.runId, owner, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        val attempt = ChatAttemptIdentity(reservation.runId, "ui-${reservation.runId}-fixture")
        return Triple(reservation, owner, attempt)
    }

    // ---------------------------------------------------------------- frozen identity

    @Test
    fun cancellingFreezesTheRegisteredAttemptForTheExactRun() {
        val runs = ChatRunOwnership()
        val (reservation, owner, attempt) = startedRun(runs)
        assertTrue(runs.markLocalAttempt(reservation.runId, owner, attempt))

        val cancellation = runs.cancelCurrentAndCancelOwner()

        assertSame(owner, cancellation.owner)
        assertEquals("the exact local request must be captured under the ownership lock",
            attempt, cancellation.localAttempt)
        assertTrue(cancellation.stopLocalRuntime)
    }

    @Test
    fun anAttemptIdThatNamesAnotherRunIsRefused() {
        val runs = ChatRunOwnership()
        val (reservation, owner, _) = startedRun(runs)
        val foreign = ChatAttemptIdentity(reservation.runId + 1L, "ui-${reservation.runId + 1L}-foreign")

        assertFalse(runs.markLocalAttempt(reservation.runId, owner, foreign))
        assertNull("a refused identity must not become the stop target",
            runs.cancelCurrent().localAttempt)
    }

    @Test
    fun aDifferentAttemptIdForTheSameRunIsRefused() {
        val runs = ChatRunOwnership()
        val (reservation, owner, attempt) = startedRun(runs)
        assertTrue(runs.markLocalAttempt(reservation.runId, owner, attempt))
        assertTrue("registering the same identity is idempotent",
            runs.markLocalAttempt(reservation.runId, owner, attempt))
        val conflicting = ChatAttemptIdentity(reservation.runId, "ui-${reservation.runId}-other")

        assertFalse("one run dispatches at most one local attempt",
            runs.markLocalAttempt(reservation.runId, owner, conflicting))
        assertEquals(attempt, runs.cancelCurrent().localAttempt)
    }

    @Test
    fun aStaleRegistrationCannotAttachToANewerRun() {
        val runs = ChatRunOwnership()
        val (oldReservation, oldOwner, oldAttempt) = startedRun(runs)
        assertTrue(runs.markLocalAttempt(oldReservation.runId, oldOwner, oldAttempt))
        runs.cancelCurrent()

        val reservation = requireNotNull(runs.reserveStart())
        val newOwner = Job()
        assertTrue(runs.activate(reservation, newOwner))
        val staleAttempt = ChatAttemptIdentity(reservation.runId, "ui-${reservation.runId}-stale")
        assertFalse("the old owner cannot register an attempt on the new run",
            runs.markLocalAttempt(reservation.runId, oldOwner, staleAttempt))
        assertFalse("and the old run's attempt id cannot be replayed under the new run",
            runs.markLocalAttempt(reservation.runId, newOwner, oldAttempt))

        val cancellation = runs.cancelCurrent()
        assertNull("the new run has no attempt of its own yet", cancellation.localAttempt)
    }

    @Test
    fun aCloudRunWithNoLocalAttemptCancelsWithoutAnEngineTarget() {
        val runs = ChatRunOwnership()
        val reservation = requireNotNull(runs.reserveStart())
        val owner = Job()
        assertTrue(runs.activate(reservation, owner))
        assertTrue(runs.markPhase(reservation.runId, owner, UiGenerationRuntimePhase.CLOUD_ACTIVE))

        val cancellation = runs.cancelCurrentAndCancelOwner()

        assertNull(cancellation.localAttempt)
        assertFalse(cancellation.stopLocalRuntime)
    }

    @Test
    fun theIdentityVerifiesItsOwnRunPrefix() {
        val wrongPrefix = runCatching { ChatAttemptIdentity(4L, "ui-5-wrong") }
        val wrongNamespace = runCatching { ChatAttemptIdentity(4L, "agent:run:0:0") }

        assertTrue("an attempt cannot claim another run", wrongPrefix.isFailure)
        assertTrue("an Agent request is not a chat attempt", wrongNamespace.isFailure)
    }

    // ---------------------------------------------------------------- production wiring

    @Test
    fun theUserStopOnlyRequestsTheFrozenAttempt() {
        val body = functionBody(adapterSource(), "override suspend fun requestRuntimeStop(")
        val precise = body.indexOf("chatExecutionFacade.stopGenerationIfActive(attempt.requestId)")

        assertTrue("the adapter receives the frozen identity, not a current token", precise >= 0)
        assertFalse("no unconditional global stop", body.contains("chatExecutionFacade.stopGeneration()"))
        assertFalse("no stop-time current-token lookup", body.contains("activeGenerationStopToken"))
        assertFalse("no engine-wide stop", body.contains("engine.stopGeneration"))

        val adapter = adapterSource()
        val targetStart = adapter.indexOf("override fun frozenLocalAttempt(")
        val target = adapter.substring(
            targetStart,
            adapter.indexOf("override suspend fun requestRuntimeStop(", targetStart)
        )
        assertTrue("the exact frozen id is the only target input",
            target.contains("cancellation.localAttempt"))
        assertFalse("the target is not re-derived from a phase or a current token",
            target.contains("stopLocalRuntime") || target.contains("activeGenerationStopToken"))
    }

    @Test
    fun theStopFreezesBeforeLaunchingItsCoroutine() {
        val body = functionBody(stopOperationsSource(), "internal fun stopActiveGeneration(")

        val freeze = body.indexOf("port.cancelCurrentGeneration()")
        val launch = body.indexOf("port.stopScope().launch")
        assertTrue("the attempt must be frozen on the caller's thread", freeze >= 0)
        assertTrue("before the stop coroutine can race a replacement", launch > freeze)
        assertFalse("the stop path must not consult a current engine token",
            body.contains("activeUiStopRequestId"))
    }

    @Test
    fun theLocalAttemptIsRegisteredBeforeAdmissionAndBeforeTheRunBecomesStoppable() {
        val body = functionBody(executionSource(), "fun ChatGenerationExecutionPort.runChatGeneration(")
        val registration = body.indexOf("chatRuns.markLocalAttempt(")
        val identity = body.indexOf("ChatAttemptIdentity(generationRunId, uiRequestId)")
        val admission = body.indexOf("LocalApiRuntime.streamChat(")
        val localPhase = body.indexOf("UiGenerationRuntimePhase.LOCAL_ACTIVE")

        assertTrue(registration >= 0)
        assertTrue(identity > registration)
        assertTrue("registration must precede engine admission", registration < admission)
        assertTrue("and the LOCAL_ACTIVE phase that permits a stop",
            localPhase > registration)
        assertTrue("the engine request id is frozen at creation — never a later lookup",
            body.contains("val registered = chatRuns.markLocalAttempt("))
    }

    // ---------------------------------------------------------------- helpers

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }

    private fun adapterSource(): String = sourceFile("ChatExecutionPortAdapters.kt")

    private fun stopOperationsSource(): String = sourceFile("ChatStopGenerationOperations.kt")

    private fun executionSource(): String = sourceFile("ChatExecutionOperations.kt")

    private fun sourceFile(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/$name"),
                File(root, "app/src/main/java/com/muyuchat/mca/$name")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("$name not found from ${System.getProperty("user.dir")}")
    }
}
