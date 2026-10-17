package com.muyuchat.core.engine

import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 閳?a GenieX delegate unload that did not release its native owner must not be published as a
 * clean unload, and the next load must not start a second runtime over the surviving owner.
 *
 * Injection is done through the mutable lifecycle fields of the production classes (and through a
 * fake delegate installed as `activeDelegate`), so this guard exercises the real state machines and
 * runs on the plain JVM unit-test source set.
 *
 * Scope note: these are contract-level injections (a delegate that throws, a delegate that returns
 * without confirming release, a live generation thread that outlives the cancellation grace period,
 * a destroy call that returns a non-zero code). They reproduce the source-level error handling the
 * 2026-10-10 re-review recorded; they do **not** claim to have reproduced a real-device native
 * resource leak, which the audit explicitly leaves unverified.
 */
class R8GenieXUnloadRecoveryTest {

    // 閳光偓閳光偓 injection helpers 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    private fun declaredField(target: Any, name: String): Field =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun setLifecycleField(target: Any, name: String, value: Any?) {
        declaredField(target, name).set(target, value)
    }

    private fun lifecycleField(target: Any, name: String): Any? = declaredField(target, name).get(target)

    private fun statsOf(runner: LocalChatRunner): JSONObject = JSONObject(runner.getRuntimeStatsJson())

    /**
     * Release confirmation probe. Resolved reflectively so this guard compiles and runs against
     * revisions that do not expose the probe yet: an absent probe means "release cannot be
     * confirmed", which is exactly what the assertions below require.
     */
    private fun nativeReleaseConfirmed(runner: Any): Boolean =
        runCatching {
            runner.javaClass.getMethod("isNativeReleaseConfirmed").invoke(runner) as Boolean
        }.getOrDefault(false)

    /** Engine double for [destroyGenieXHandle]: exposes `destroy(long): int` by reflection. */
    @Suppress("unused")
    private class FakeGenieXEngine(private val destroyResult: Int) {
        fun destroy(handle: Long): Int = destroyResult
    }

    /** Engine double whose destroy call always throws. */
    @Suppress("unused")
    private class ThrowingDestroyEngine {
        fun destroy(handle: Long): Int = throw IllegalStateException("injected destroy failure ($handle)")
    }

    private open class RecordingRunner(
        private val unloadFailure: Throwable? = null,
        private val sessionLost: Boolean = false
    ) : LocalChatRunner {
        val unloadCalls = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)

        override val runtime: LocalChatRuntime = LocalChatRuntime.GENIEX_LLAMA_CPP
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        override fun initBackends(nativeLibDir: String) = Unit
        override fun loadModel(modelPath: String, paramsJson: String): Int {
            loadCalls.incrementAndGet()
            return 0
        }

        override fun unloadModel() {
            unloadCalls.incrementAndGet()
            unloadFailure?.let { throw it }
        }

        override fun beginCompletion(messagesJson: String, paramsJson: String): Int = 0
        override fun generateNextChunk(): String? = null
        override fun requestStop() = Unit
        override fun isSessionKnownLost(): Boolean = sessionLost
        override fun getRuntimeStatsJson(): String = "{}"
        override fun shutdown() = Unit
    }

    private fun newOuterRunner(): GenieXLlamaCppChatRunner = GenieXLlamaCppChatRunner()

    private fun newInnerRunner(): GenieXChatRunner = GenieXChatRunner(
        runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
        // SDK enum values are not on the unit-test classpath; the runner only compares these
        // strings, and the QAIRT runtime id is deliberately not used here.
        requestedRuntimeId = "llama_cpp",
        defaultComputeUnit = "hybrid",
        defaultBackendDevices = "r8-test",
        appContext = null
    )

    private fun installActiveDelegate(
        outer: GenieXLlamaCppChatRunner,
        delegate: LocalChatRunner,
        executionPath: String
    ) {
        setLifecycleField(outer, "activeDelegate", delegate)
        setLifecycleField(outer, "executionPath", executionPath)
    }

    private fun stopRequested(latch: CountDownLatch): Thread = Thread {
        // Deliberately ignores interrupts: models a native call that does not return within the
        // cancellation grace period.
        while (latch.count > 0L) {
            try {
                latch.await(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
    }

    // 閳光偓閳光偓 injection 閳? delegate.unloadModel() throws 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Test
    fun throwingDelegateUnloadIsNotPublishedAsACleanUnload() {
        val outer = newOuterRunner()
        val delegate = RecordingRunner(unloadFailure = IllegalStateException("injected unload failure"))
        installActiveDelegate(outer, delegate, "llama_cpp_cpu_fallback")

        outer.unloadModel()

        val stats = statsOf(outer)
        assertEquals("the delegate must still own the (unreleased) runtime", 1, delegate.unloadCalls.get())
        assertSame(
            "a failed unload must keep the delegate that still owns native state",
            delegate,
            lifecycleField(outer, "activeDelegate")
        )
        assertEquals(
            "a failed unload must not publish the idle execution path",
            "llama_cpp_cpu_fallback",
            lifecycleField(outer, "executionPath")
        )
        assertTrue(
            "the failed release must be visible through stats",
            stats.optBoolean("nativeReleasePending", false)
        )
        assertTrue(
            "the failed release must keep a diagnosable reason",
            stats.optString("nativeReleaseFailure").isNotBlank() ||
                stats.optString("lastError").isNotBlank()
        )
        assertFalse(
            "release is not confirmed while the delegate still owns native state",
            nativeReleaseConfirmed(outer)
        )
    }

    // 閳光偓閳光偓 injection 閳?閳?boundary: delegate returns normally, owner still alive 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Test
    fun normalReturnWithoutReleasedOwnerIsNotACleanUnload() {
        val outer = newOuterRunner()
        // A delegate that returns normally from unloadModel() while reporting that its session is
        // lost: exactly the shape the inner GenieX runner produces for a join timeout or a non-zero
        // destroy result.
        val delegate = RecordingRunner(sessionLost = true)
        installActiveDelegate(outer, delegate, "geniex_llama_cpp")

        outer.unloadModel()

        val stats = statsOf(outer)
        assertSame(
            "returning normally without releasing must keep the owning delegate",
            delegate,
            lifecycleField(outer, "activeDelegate")
        )
        assertEquals(
            "returning normally without releasing must not publish idle",
            "geniex_llama_cpp",
            lifecycleField(outer, "executionPath")
        )
        assertTrue(
            "the unreleased owner must be visible through stats",
            stats.optBoolean("nativeReleasePending", false)
        )
        assertTrue(
            "the isolated-worker recovery channel must see the lost session",
            stats.optBoolean("workerSessionLost", false) || outer.isSessionKnownLost()
        )
        assertEquals(
            "an unreleased native owner must require an explicit reload instead of a silent retry",
            LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED,
            outer.sessionRecoveryPolicy()
        )
        assertFalse(nativeReleaseConfirmed(outer))
    }

    // 閳光偓閳光偓 injection 閳? immediate reload / delegate switch after a failed unload 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Test
    fun failedUnloadBlocksTheNextLoadInsteadOfSwitchingRuntime() {
        val outer = newOuterRunner()
        val delegate = RecordingRunner(unloadFailure = IllegalStateException("injected unload failure"))
        installActiveDelegate(outer, delegate, "geniex_llama_cpp")

        outer.unloadModel()
        val releaseFailure = statsOf(outer).optString("lastError", "")
        assertTrue("precondition: the release failure is reported", releaseFailure.isNotBlank())

        val reloadCode = outer.loadModel("/tmp/r8-model.gguf", """{"geniex_compute_unit":"cpu"}""")

        assertEquals("the refused second load must report the unavailable code", -100, reloadCode)
        assertSame(
            "the second load must not install another delegate over a live native owner",
            delegate,
            lifecycleField(outer, "activeDelegate")
        )
        assertEquals(
            "the second load must not switch the execution path",
            "geniex_llama_cpp",
            lifecycleField(outer, "executionPath")
        )
        assertEquals(
            "the second load must not start loading through the delegate",
            0,
            delegate.loadCalls.get()
        )
        assertTrue(
            "the release failure must not be cleared by the refused load",
            statsOf(outer).optString("lastError").contains("unload") ||
                statsOf(outer).optString("nativeReleaseFailure").isNotBlank()
        )
    }

    @Test
    fun failedUnloadAlsoBlocksTheGenieXPath() {
        val outer = newOuterRunner()
        val delegate = RecordingRunner(sessionLost = true)
        installActiveDelegate(outer, delegate, "llama_cpp_cpu_fallback")

        outer.unloadModel()
        val reloadCode = outer.loadModel("/tmp/r8-model.gguf", "{}")

        assertEquals(-100, reloadCode)
        assertSame(delegate, lifecycleField(outer, "activeDelegate"))
        assertEquals("llama_cpp_cpu_fallback", lifecycleField(outer, "executionPath"))
        assertEquals(0, delegate.loadCalls.get())
    }

    // 閳光偓閳光偓 injection 閳?at the source: live generation thread past the grace period 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Test
    fun joinTimeoutKeepsTheNativeOwnerAndRequiresWorkerRecovery() {
        val inner = newInnerRunner()
        val latch = CountDownLatch(1)
        val thread = stopRequested(latch)
        try {
            setLifecycleField(inner, "engine", FakeGenieXEngine(0))
            setLifecycleField(inner, "handle", 42L)
            setLifecycleField(inner, "loaded", true)
            setLifecycleField(inner, "generateThread", thread)
            thread.start()

            inner.unloadModel()

            val stats = JSONObject(inner.getRuntimeStatsJson())
            assertEquals(
                "a join timeout must not detach the native handle",
                42L,
                lifecycleField(inner, "handle")
            )
            assertEquals(
                "a join timeout must not report the model as unloaded",
                true,
                lifecycleField(inner, "loaded")
            )
            assertTrue(
                "the join timeout must reach the existing worker-session-lost channel",
                inner.isSessionKnownLost() || stats.optBoolean("workerSessionLost", false)
            )
            assertEquals(
                "an unreleased owner must require an explicit reload",
                LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED,
                inner.sessionRecoveryPolicy()
            )

            // Recovery: once the native call returns, a retry must release the retained handle.
            thread.interrupt()
            latch.countDown()
            thread.join(5_000L)
            inner.unloadModel()

            assertEquals(
                "after recovery the retained handle must be released",
                0L,
                lifecycleField(inner, "handle")
            )
            assertEquals(false, lifecycleField(inner, "loaded"))
            assertFalse(
                "a released owner must not keep claiming a lost session",
                JSONObject(inner.getRuntimeStatsJson()).optBoolean("workerSessionLost", false)
            )
        } finally {
            latch.countDown()
            thread.join(2_000L)
        }
    }

    // 閳光偓閳光偓 injection 閳?at the source: destroy returns a non-zero code 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Test
    fun nonZeroDestroyKeepsTheHandleAndReportsFailure() {
        val inner = newInnerRunner()
        setLifecycleField(inner, "engine", FakeGenieXEngine(-7))
        setLifecycleField(inner, "handle", 7L)
        setLifecycleField(inner, "loaded", true)

        inner.unloadModel()

        val stats = JSONObject(inner.getRuntimeStatsJson())
        assertEquals(
            "a non-zero destroy must not detach the handle that still needs releasing",
            7L,
            lifecycleField(inner, "handle")
        )
        assertTrue(
            "a non-zero destroy must be visible through stats",
            stats.optString("lastError").isNotBlank() ||
                stats.optString("nativeReleaseFailure").isNotBlank()
        )
        assertTrue(
            "a non-zero destroy must reach the existing worker-session-lost channel",
            inner.isSessionKnownLost() || stats.optBoolean("workerSessionLost", false)
        )

        // Retry with a destroy that succeeds: the retained handle must be released exactly then.
        setLifecycleField(inner, "engine", FakeGenieXEngine(0))
        inner.unloadModel()

        assertEquals(0L, lifecycleField(inner, "handle"))
        assertEquals(false, lifecycleField(inner, "loaded"))
        assertFalse(JSONObject(inner.getRuntimeStatsJson()).optBoolean("workerSessionLost", false))
    }

    @Test
    fun throwingDestroyKeepsTheHandleAndReportsFailure() {
        val inner = newInnerRunner()
        setLifecycleField(inner, "engine", ThrowingDestroyEngine())
        setLifecycleField(inner, "handle", 11L)
        setLifecycleField(inner, "loaded", true)

        inner.unloadModel()

        assertEquals(
            "a throwing destroy must not detach the handle",
            11L,
            lifecycleField(inner, "handle")
        )
        assertTrue(
            "a throwing destroy must be visible",
            JSONObject(inner.getRuntimeStatsJson()).optString("lastError").isNotBlank() ||
                inner.isSessionKnownLost()
        )
    }

    // 閳光偓閳光偓 recovery after a successful unload: no stale failure state 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓

    @Test
    fun successfulUnloadReturnsToIdleAndClearsTheFailure() {
        val outer = newOuterRunner()
        val failing = RecordingRunner(unloadFailure = IllegalStateException("injected unload failure"))
        installActiveDelegate(outer, failing, "geniex_llama_cpp")

        outer.unloadModel()
        assertTrue(statsOf(outer).optBoolean("nativeReleasePending", false))

        // The delegate is replaced by one that releases cleanly; the retry must publish idle.
        val healthy = RecordingRunner()
        installActiveDelegate(outer, healthy, "geniex_llama_cpp")
        outer.unloadModel()

        val stats = statsOf(outer)
        assertFalse(stats.optBoolean("nativeReleasePending", false))
        assertEquals("idle", lifecycleField(outer, "executionPath"))
        assertTrue(lifecycleField(outer, "activeDelegate") == null)
        assertTrue(
            "a confirmed release must not keep the session marked lost",
            !outer.isSessionKnownLost()
        )
    }

    // 鈹€鈹€ recovery channel: exactly the signals the existing service path consumes 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /**
     * The service decides between "reclaim/reload the isolated worker" and "require an explicit
     * reload" from these runner signals only.  A failed release must therefore look like a lost
     * session that requires an explicit reload, and a confirmed release must clear all of it 鈥?the
     * assertions are behavioural so they do not depend on any method name in the implementation.
     */
    @Test
    fun recoveryChannelCarriesTheFailedReleaseAndClearsAfterAConfirmedOne() {
        val outer = newOuterRunner()
        val failing = RecordingRunner(sessionLost = true)
        installActiveDelegate(outer, failing, "geniex_llama_cpp")

        outer.unloadModel()

        assertTrue(
            "the existing session-lost channel must report the unreleased owner",
            outer.isSessionKnownLost()
        )
        assertTrue(
            "the service reads this stats field to trigger recovery",
            statsOf(outer).optBoolean("workerSessionLost", false)
        )
        assertEquals(
            "an unreleased owner must never be silently reloaded over",
            LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED,
            outer.sessionRecoveryPolicy()
        )
        assertFalse(
            "a pending release must not claim to be confirmed",
            nativeReleaseConfirmed(outer)
        )
        assertTrue(
            "the recovery path needs a prompt-free reason",
            (outer.sessionRecoveryMessage() ?: "").isNotBlank()
        )
        assertEquals(
            "the service must not report a loaded model while the owner is unreleased",
            false,
            statsOf(outer).optBoolean("loaded", false)
        )

        // A confirmed release clears every recovery signal again.
        val healthy = RecordingRunner()
        installActiveDelegate(outer, healthy, "geniex_llama_cpp")
        outer.unloadModel()

        assertFalse(outer.isSessionKnownLost())
        assertFalse(statsOf(outer).optBoolean("workerSessionLost", false))
        assertTrue(nativeReleaseConfirmed(outer))
        assertEquals(LocalChatSessionRecoveryPolicy.AUTOMATIC, outer.sessionRecoveryPolicy())
        assertEquals(
            "the collected delegate had no load attempt",
            0,
            healthy.loadCalls.get()
        )
    }
}
