package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static wiring contract for the two integration seams this slice adds.
 *
 * `src/test` has no Activity/Compose host, so the real MediaProjection dialog and the runtime-owner
 * trial generation are device-only. These assertions pin the production wiring that would otherwise
 * silently regress to the previous honest-unavailable state:
 * - the Activity registers the one `StartActivityForResult` launcher and prepares the capture
 *   foreground service before launching `createScreenCaptureIntent()`;
 * - the ViewModel delegates claim/result/teardown to the single DSH consent transaction owner;
 * - the console port projects the consent state and dispatches the typed intent;
 * - the maintenance assembly receives the typed trial launch adapter.
 */
class DshScreenCaptureConsentWiringContractTest {

    private val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
    private val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
    private val console = sourceFile("app/src/main/java/com/muyuchat/mca/composition/DshConsoleAssembly.kt")
    private val runtime = sourceFile("app/src/main/java/com/muyuchat/mca/composition/DshHostAssembly.kt")
    private val consentHost = sourceFile("app/src/main/java/com/muyuchat/mca/MainDshScreenCaptureHost.kt")
    private val consentOwner = sourceFile("app/src/main/java/com/muyuchat/mca/composition/DshScreenCaptureConsentOwner.kt")

    @Test
    fun `the activity owns the one media projection consent launcher`() {
        assertTrue(
            activity.contains("ActivityResultContracts.StartActivityForResult()")
        )
        assertTrue(activity.contains("getSystemService(MediaProjectionManager::class.java)"))
        assertTrue(activity.contains("createScreenCaptureIntent()"))
        // The claim happens before the dialog; the capture service starts only after its result.
        val claimAt = activity.indexOf("beginDshScreenCaptureConsent(requestId)")
        val launchAt = activity.indexOf("screenCaptureLauncher.launch(")
        assertTrue("MainActivity no longer calls beginDshScreenCaptureConsent(requestId)", claimAt >= 0)
        assertTrue("MainActivity no longer launches the consent dialog", launchAt >= 0)
        assertTrue("the claim must happen before the dialog", claimAt < launchAt)
        // The owner must never acquire the projection or start the foreground service itself:
        // the Activity owns the dialog and the capability assembly owns the projection.
        for (forbidden in listOf(
            "MediaProjectionManager",
            "createScreenCaptureIntent",
            "startForegroundService",
            "prepareCapture",
        )) {
            assertTrue(
                "DshScreenCaptureConsentOwner must not reference $forbidden",
                !consentOwner.contains(forbidden),
            )
        }
        // The result goes to the app owner, never to a local grant flag.
        assertTrue(activity.contains("onDshScreenCaptureResult(result.resultCode, result.data)"))
        // The request is observed by the small host bridge, not by an activity-level side effect.
        assertTrue(consentHost.contains("DshScreenCaptureConsentPhase.REQUESTED"))
    }

    @Test
    fun `the view model preserves activity seams and delegates the consent transaction`() {
        assertTrue(viewModel.contains("internal fun grantDshScreenCapture(resultCode: Int, data: Intent?)"))
        assertTrue(viewModel.contains("screenCaptureConsentOwner.begin(requestId)"))
        assertTrue(viewModel.contains("screenCaptureConsentOwner.reject(requestId, code)"))
        assertTrue(viewModel.contains("screenCaptureConsentOwner.acceptResult(resultCode, data)"))
        assertTrue(viewModel.contains("screenCaptureConsentOwner.closePending()"))
        // Ownership moved as a whole: the owner delegates to the controller's exactly-once claim.
        // The behavioural guarantee (first call true, repeats false) is pinned by
        // DshScreenCaptureConsentOwnerTest; here we only pin that the wiring still goes through it.
        assertTrue(consentOwner.contains("fun begin(requestId: Long): Boolean = consent.claim(requestId)"))
        assertTrue(consentOwner.contains("DshScreenCaptureConsentCodes.CONSENT_REQUIRED"))
        assertTrue(consentOwner.contains("phase != DshScreenCaptureConsentPhase.AWAITING_USER"))
    }

    @Test
    fun `the console port projects consent and dispatches the request intent`() {
        assertTrue(console.contains("screenCapture = screenCapture.projectedState()"))
        assertTrue(console.contains("DshConsoleIntent.RequestScreenCapture ->"))
        assertTrue(console.contains("DshScreenCaptureConsentPhase.GRANTED"))
    }

    @Test
    fun `the composition wires the typed trial port and the real capture owner`() {
        assertTrue(runtime.contains("trialLaunchPort = { trialLaunchPort }"))
        // The trial port is now the live typed adapter over the runtime client.
        assertTrue(runtime.contains("get() = modeAssembly.trialLaunchPort"))
        // The real capture owner receives the consent controller and reports releases/teardown.
        assertTrue(runtime.contains("screenCaptureConsent = screenCaptureConsent"))
        assertTrue(runtime.contains("isGrantLive = { capabilityAssembly.isScreenCaptureGranted() }"))
        assertTrue(runtime.contains("DshScreenCaptureConsentOwner<Intent>"))
        assertTrue(runtime.contains("consent = screenCaptureConsent"))
        assertTrue(runtime.contains("grantCapture = ::grantScreenCapture"))
        val capability = sourceFile("app/src/main/java/com/muyuchat/mca/composition/DshCapabilityAssembly.kt")
        assertTrue(capability.contains("screenCaptureConsent.onCaptureReleased()"))
        assertTrue(capability.contains("screenCaptureConsent.onHostStopped()"))
    }

    private fun sourceFile(relativePath: String): String = sequenceOf(
        File(relativePath),
        File("../$relativePath")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $relativePath")
}
