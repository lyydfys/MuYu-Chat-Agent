package com.muyuchat.core.dsh.a11y.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * The only Android entry of `:core:dsh-a11y`.
 *
 * It is deliberately thin: the system binds it when the user enables "DeepSeek Harness 屏幕助手" in
 * Accessibility settings, and it forwards lifecycle/event callbacks to [DshA11yController]. All
 * behaviour (validation, caps, gestures, screenshots, node refs) lives in the controller and in
 * the pure policy layer, so the service itself has nothing worth faking in a JVM test.
 *
 * Declared in this module's `AndroidManifest.xml` with
 * `android.permission.BIND_ACCESSIBILITY_SERVICE` and the `accessibility_service_config` meta-data
 * (`canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot`). Enabling it is always a
 * user action; when it is disabled the module answers `/status` with `running=false` and every
 * action with `NOT_ENABLED` instead of claiming the capability.
 */
class DshA11yService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        controller.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        controller.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        controller.onInterrupt()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        controller.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        controller.detach(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.onConfigurationChanged()
    }

    companion object {
        /**
         * Process-local singleton. The app composition root wires the official tool surface with
         * `DshA11yCapabilityRoutes(DshA11yService.controller)`; without an enabled service the same
         * engine answers `NOT_ENABLED`, so the routes can be registered unconditionally.
         */
        val controller: DshA11yController
            get() = DshA11yController.instance
    }
}
