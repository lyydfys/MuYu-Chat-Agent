package com.muyuchat.core.dsh.a11y.android

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural assertions only.
 *
 * The unit-test Android runtime returns default values for framework methods, so a test that
 * "invokes" the service or a node/gesture stub would prove nothing. This test only checks the two
 * things that can be verified without a device: the class is a real `AccessibilityService` subclass
 * and it overrides the lifecycle callbacks the controller depends on. All behaviour lives in the
 * pure policy package, where it is tested with real assertions.
 */
class DshA11yServiceStructureTest {

    @Test
    fun serviceIsAnAccessibilityService() {
        assertTrue(
            "DshA11yService must extend the platform AccessibilityService",
            AccessibilityService::class.java.isAssignableFrom(DshA11yService::class.java)
        )
    }

    @Test
    fun serviceOverridesTheLifecycleCallbacksTheControllerNeeds() {
        val methods = DshA11yService::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("onServiceConnected", methods.contains("onServiceConnected"))
        assertTrue("onAccessibilityEvent", methods.contains("onAccessibilityEvent"))
        assertTrue("onInterrupt", methods.contains("onInterrupt"))
        assertTrue("onUnbind", methods.contains("onUnbind"))
        assertTrue("onConfigurationChanged", methods.contains("onConfigurationChanged"))
    }

    @Test
    fun controllerSequencesRotationsThroughTheSameEngineEntryPoint() {
        val methods = DshA11yController::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("execute is the A11yEngine entry point", methods.contains("execute"))
        // Internal Kotlin members may be name-mangled by the compiler; match either form.
        assertTrue(
            "onConfigurationChanged must invalidate held coordinates",
            methods.any { it == "onConfigurationChanged" || it.startsWith("onConfigurationChanged$") }
        )
    }
}
