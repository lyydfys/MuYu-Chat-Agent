package com.muyuchat.core.dsh.runtime.payload

/** Narrow provisioning seam so the lifecycle owner can be tested without APK assets. */
fun interface DshPayloadProvisioner {
    fun ensureInstalled(): DshPayloadInstallResult
}
