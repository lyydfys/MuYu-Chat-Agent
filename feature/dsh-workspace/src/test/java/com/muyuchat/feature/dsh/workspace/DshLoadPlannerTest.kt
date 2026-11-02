package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshLoadPlannerTest {

    private val originText = "http://127.0.0.1:34567"
    private val origin = DshOrigin.parse(originText)!!
    private val ready = DshRuntimeSnapshot(
        phase = DshRuntimePhase.READY,
        epoch = 7L,
        origin = originText
    )

    @Test
    fun freshEntryForCurrentGenerationLoadsTokenThenRestoresPath() {
        val entry = DshWebEntry(epoch = 7L, authenticatedUrl = "$originText/?token=abc")
        val plan = DshLoadPlanner.plan(ready, entry, "/session/3")
        assertTrue(plan is DshWebLoadPlan.TokenEntry)
        plan as DshWebLoadPlan.TokenEntry
        assertEquals(7L, plan.epoch)
        assertEquals("$originText/?token=abc", plan.url)
        assertEquals(origin, plan.origin)
        assertEquals("/session/3", plan.restorePath)
    }

    @Test
    fun missingEntryFallsBackToCookieResumeAtSavedPath() {
        val plan = DshLoadPlanner.plan(ready, null, "/session/3")
        assertTrue(plan is DshWebLoadPlan.CookieResume)
        plan as DshWebLoadPlan.CookieResume
        assertEquals(7L, plan.epoch)
        assertEquals("$originText/session/3", plan.url)
    }

    @Test
    fun missingEntryAndMissingPathResumeAtRoot() {
        val plan = DshLoadPlanner.plan(ready, null, null)
        assertTrue(plan is DshWebLoadPlan.CookieResume)
        assertEquals("$originText/", (plan as DshWebLoadPlan.CookieResume).url)
    }

    @Test
    fun staleOrForeignEntriesAreNeverLoaded() {
        val stale = DshWebEntry(epoch = 6L, authenticatedUrl = "$originText/?token=stale")
        assertTrue(DshLoadPlanner.plan(ready, stale, null) is DshWebLoadPlan.CookieResume)

        val foreign = DshWebEntry(epoch = 7L, authenticatedUrl = "http://127.0.0.1:9/?token=x")
        assertTrue(DshLoadPlanner.plan(ready, foreign, null) is DshWebLoadPlan.CookieResume)

        val https = DshWebEntry(epoch = 7L, authenticatedUrl = "https://127.0.0.1:34567/?token=x")
        assertTrue(DshLoadPlanner.plan(ready, https, null) is DshWebLoadPlan.CookieResume)
    }

    @Test
    fun unsafeSavedPathIsDropped() {
        val plan = DshLoadPlanner.plan(ready, null, "/session/3?token=leak")
        assertTrue(plan is DshWebLoadPlan.CookieResume)
        assertEquals("$originText/", (plan as DshWebLoadPlan.CookieResume).url)
        assertTrue(DshNavigationPolicy.sanitizeRestorePath("/session/3?token=leak") == null)
    }

    @Test
    fun notReadyPhaseProjectsFailure() {
        val failed = DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = 8L,
            failure = DshRuntimeFailure(DshFailureCode.NODE_START_FAILED, "x", retryable = true)
        )
        val plan = DshLoadPlanner.plan(failed, null, "/")
        assertTrue(plan is DshWebLoadPlan.Failure)
        assertEquals(
            DshFailureCode.NODE_START_FAILED,
            (plan as DshWebLoadPlan.Failure).projection.code
        )
    }

    @Test
    fun malformedOriginCannotProduceAWebPlan() {
        val malformed = DshRuntimeSnapshot(
            phase = DshRuntimePhase.READY,
            epoch = 9L,
            origin = "http://127.0.0.1"
        )
        assertTrue(DshLoadPlanner.plan(malformed, null, "/") is DshWebLoadPlan.Failure)
    }

    @Test
    fun startPolicyRestartsOnlyFailedHosts() {
        assertEquals(DshStartAction.ATTACH, DshStartPolicy.actionFor(DshRuntimeSnapshot()))
        assertEquals(
            DshStartAction.ATTACH,
            DshStartPolicy.actionFor(ready)
        )
        assertEquals(
            DshStartAction.RESTART,
            DshStartPolicy.actionFor(
                DshRuntimeSnapshot(
                    phase = DshRuntimePhase.FAILED,
                    failure = DshRuntimeFailure(DshFailureCode.INTERNAL, "x")
                )
            )
        )
    }
}
