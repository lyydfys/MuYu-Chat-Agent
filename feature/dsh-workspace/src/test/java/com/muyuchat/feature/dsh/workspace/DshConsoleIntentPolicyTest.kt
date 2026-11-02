package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionSnapshot
import com.muyuchat.core.dsh.contract.console.DshConsoleCapabilityProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleDiagnosticsProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshConsoleMaintenanceProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleOfficialSurface
import com.muyuchat.core.dsh.contract.console.DshConsoleRejection
import com.muyuchat.core.dsh.contract.console.DshConsoleRuntimeProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshConsoleIntentPolicyTest {

    @Test
    fun refreshIsAlwaysAllowed() {
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(DshConsoleIntent.Refresh, snapshot())
        )
    }

    @Test
    fun officialSurfaceIsAllowedOnlyWhenListedAndReady() {
        val allowed = DshConsoleIntentPolicy.gate(
            DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.SETTINGS),
            snapshot()
        )
        val notListed = DshConsoleIntentPolicy.gate(
            DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.PLUGINS),
            snapshot()
        )
        val notReady = DshConsoleIntentPolicy.gate(
            DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.SETTINGS),
            snapshot(phase = DshRuntimePhase.STARTING)
        )

        assertEquals(DshConsoleIntentGate.Allowed, allowed)
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.SURFACE_UNAVAILABLE),
            notListed
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.HOST_NOT_READY),
            notReady
        )
    }

    @Test
    fun theOfficialAccountSurfaceIsTypedUnavailableWhileTheOwnerCannotRouteIt() {
        // The console port has no typed same-origin route/WebView seam for the official account UI,
        // so it must not advertise the surface and must refuse a direct request.
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.SURFACE_UNAVAILABLE),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.ACCOUNT),
                snapshot()
            )
        )
        assertFalse(
            DshConsoleIntentPolicy.availableIntents(snapshot())
                .contains(DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.ACCOUNT))
        )
    }

    @Test
    fun stopIsAllowedOnlyForALiveHost() {
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(DshConsoleIntent.StopRuntime, snapshot())
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.STOP_REQUESTED),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.StopRuntime,
                snapshot(stopRequested = true)
            )
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.HOST_NOT_READY),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.StopRuntime,
                snapshot(phase = DshRuntimePhase.STOPPED)
            )
        )
    }

    @Test
    fun restartIsAllowedOnlyAfterFailureOrStop() {
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.RestartRuntime,
                snapshot(phase = DshRuntimePhase.FAILED)
            )
        )
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.RestartRuntime,
                snapshot(phase = DshRuntimePhase.STOPPED)
            )
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.HOST_BUSY),
            DshConsoleIntentPolicy.gate(DshConsoleIntent.RestartRuntime, snapshot())
        )
    }

    @Test
    fun maintenanceBarrierAcquisitionIsGatedByCurrentAdmission() {
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.AcquireMaintenanceBarrier,
                snapshot()
            )
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.MAINTENANCE_ACTIVE),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.AcquireMaintenanceBarrier,
                snapshot(barrierActive = true)
            )
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.HOST_BUSY),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.AcquireMaintenanceBarrier,
                snapshot(phase = DshRuntimePhase.STARTING)
            )
        )
    }

    @Test
    fun maintenanceBarrierReleaseRequiresAHeldBarrier() {
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.MAINTENANCE_NOT_HELD),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.ReleaseMaintenanceBarrier,
                snapshot()
            )
        )
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.ReleaseMaintenanceBarrier,
                snapshot(barrierActive = true)
            )
        )
    }

    @Test
    fun diagnosticsExportRequiresSomethingToExport() {
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.NOT_AVAILABLE),
            DshConsoleIntentPolicy.gate(DshConsoleIntent.ExportDiagnostics, snapshot())
        )
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.ExportDiagnostics,
                snapshot(lastErrorCode = "RENDERER_GONE")
            )
        )
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.ExportDiagnostics,
                snapshot(diagnosticLines = listOf("host ready"))
            )
        )
    }

    @Test
    fun availableIntentsContainOnlyAllowedOnes() {
        val available = DshConsoleIntentPolicy.availableIntents(snapshot(barrierActive = true))

        assertTrue(DshConsoleIntent.Refresh in available)
        assertTrue(DshConsoleIntent.StopRuntime in available)
        assertTrue(DshConsoleIntent.ReleaseMaintenanceBarrier in available)
        assertTrue(DshConsoleIntent.AcquireMaintenanceBarrier !in available)
        assertTrue(DshConsoleIntent.RestartRuntime !in available)
        assertTrue(DshConsoleIntent.ExportDiagnostics !in available)
        assertTrue(
            DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.SETTINGS) in available
        )
    }

    @Test
    fun gatingNeverMutatesTheSnapshot() {
        val snapshot = snapshot(barrierActive = true)
        val before = snapshot.toString()

        DshConsoleIntentPolicy.gate(DshConsoleIntent.AcquireMaintenanceBarrier, snapshot)
        DshConsoleIntentPolicy.availableIntents(snapshot)

        assertEquals(before, snapshot.toString())
    }

    @Test
    fun aSnapshotWithoutSurfacesOffersNoOfficialEntry() {
        val available = DshConsoleIntentPolicy.availableIntents(snapshot(surfaces = emptySet()))

        assertFalse(available.any { it is DshConsoleIntent.OpenOfficialSurface })
    }

    @Test
    fun screenCaptureConsentIsGatedByTheRegisteredVirtualScreenTool() {
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.SCREEN_CAPTURE_UNAVAILABLE),
            DshConsoleIntentPolicy.gate(DshConsoleIntent.RequestScreenCapture, snapshot())
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.SCREEN_CAPTURE_UNAVAILABLE),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.RequestScreenCapture,
                snapshot(capabilities = listOf(virtualScreen(toolRegistered = false)))
            )
        )
        assertEquals(
            DshConsoleIntentGate.Allowed,
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.RequestScreenCapture,
                snapshot(capabilities = listOf(virtualScreen(toolRegistered = true)))
            )
        )
        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.HOST_NOT_READY),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.RequestScreenCapture,
                snapshot(
                    phase = DshRuntimePhase.STARTING,
                    capabilities = listOf(virtualScreen(toolRegistered = true))
                )
            )
        )
    }

    @Test
    fun screenCaptureConsentGateReflectsTheLiveConsentState() {
        val registered = listOf(virtualScreen(toolRegistered = true))

        assertEquals(
            DshConsoleIntentGate.Denied(DshConsoleRejection.SCREEN_CAPTURE_ALREADY_GRANTED),
            DshConsoleIntentPolicy.gate(
                DshConsoleIntent.RequestScreenCapture,
                snapshot(
                    capabilities = registered,
                    consent = DshScreenCaptureConsentState(DshScreenCaptureConsentPhase.GRANTED)
                )
            )
        )
        listOf(
            DshScreenCaptureConsentPhase.REQUESTED,
            DshScreenCaptureConsentPhase.AWAITING_USER
        ).forEach { phase ->
            assertEquals(
                DshConsoleIntentGate.Denied(DshConsoleRejection.SCREEN_CAPTURE_PENDING),
                DshConsoleIntentPolicy.gate(
                    DshConsoleIntent.RequestScreenCapture,
                    snapshot(
                        capabilities = registered,
                        consent = DshScreenCaptureConsentState(phase)
                    )
                )
            )
        }
        listOf(
            DshScreenCaptureConsentPhase.CANCELLED,
            DshScreenCaptureConsentPhase.REJECTED,
            DshScreenCaptureConsentPhase.RELEASED
        ).forEach { phase ->
            assertEquals(
                DshConsoleIntentGate.Allowed,
                DshConsoleIntentPolicy.gate(
                    DshConsoleIntent.RequestScreenCapture,
                    snapshot(
                        capabilities = registered,
                        consent = DshScreenCaptureConsentState(phase, code = "consent_required")
                    )
                )
            )
        }
    }

    @Test
    fun availableIntentsOfferTheConsentEntryWhileTheToolIsRegistered() {
        val available = DshConsoleIntentPolicy.availableIntents(
            snapshot(capabilities = listOf(virtualScreen(toolRegistered = true)))
        )

        assertTrue(DshConsoleIntent.RequestScreenCapture in available)
        assertFalse(
            DshConsoleIntent.RequestScreenCapture in
                DshConsoleIntentPolicy.availableIntents(snapshot())
        )
    }

    private fun virtualScreen(toolRegistered: Boolean) = DshConsoleCapabilityProjection(
        capability = DshCapability.VIRTUAL_SCREEN,
        state = if (toolRegistered) DshCapabilityState.GRANTED else DshCapabilityState.NOT_ENABLED,
        toolRegistered = toolRegistered
    )

    private fun snapshot(
        phase: DshRuntimePhase = DshRuntimePhase.READY,
        stopRequested: Boolean = false,
        barrierActive: Boolean = false,
        lastErrorCode: String? = null,
        diagnosticLines: List<String> = emptyList(),
        surfaces: Set<DshConsoleOfficialSurface> = setOf(DshConsoleOfficialSurface.SETTINGS),
        capabilities: List<DshConsoleCapabilityProjection> = emptyList(),
        consent: DshScreenCaptureConsentState = DshScreenCaptureConsentState()
    ): DshConsoleSnapshot = DshConsoleSnapshot(
        revision = 1,
        capturedAtMillis = 1_000L,
        runtime = DshConsoleRuntimeProjection(
            phase = phase,
            generation = 3L,
            origin = if (phase == DshRuntimePhase.READY) "http://127.0.0.1:34000" else null,
            stopRequested = stopRequested,
            firstFrameRendered = phase == DshRuntimePhase.READY,
            failureCode = if (phase == DshRuntimePhase.FAILED) "NODE_START_FAILED" else null,
            modelBound = phase == DshRuntimePhase.READY
        ),
        capabilities = capabilities,
        screenCapture = consent,
        maintenance = DshConsoleMaintenanceProjection(
            DshMaintenanceAdmissionSnapshot(
                barrierActive = barrierActive,
                mutationLeaseCount = 0,
                readLeaseCount = 0
            )
        ),
        diagnostics = DshConsoleDiagnosticsProjection(
            webViewGeneration = 1,
            rendererRestarts = 0,
            lastErrorCode = lastErrorCode,
            redactedLines = diagnosticLines
        ),
        officialSurfaces = surfaces
    )
}
