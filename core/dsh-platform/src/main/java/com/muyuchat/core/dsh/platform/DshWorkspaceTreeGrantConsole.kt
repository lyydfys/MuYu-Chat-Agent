package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import com.muyuchat.core.dsh.contract.console.DshConsoleCapabilityProjection

/**
 * Read-only console projection of the workspace-tree grant.
 *
 * Only a live GRANTED status becomes "available"; a revoked or invalid grant is shown as denied
 * and a missing provider as not enabled. The console therefore cannot display a picker result or a
 * stale record as working workspace access.
 */
object DshWorkspaceTreeGrantConsole {

    fun project(
        status: DshWorkspaceTreeGrantStatus,
        toolRegistered: Boolean
    ): DshConsoleCapabilityProjection = DshConsoleCapabilityProjection(
        capability = DshCapability.WORKSPACE_TREE,
        state = when (status.state) {
            DshWorkspaceTreeGrantState.GRANTED -> DshCapabilityState.GRANTED
            DshWorkspaceTreeGrantState.REVOKED,
            DshWorkspaceTreeGrantState.INVALID -> DshCapabilityState.DENIED
            DshWorkspaceTreeGrantState.NOT_GRANTED,
            DshWorkspaceTreeGrantState.PROVIDER_UNAVAILABLE -> DshCapabilityState.NOT_ENABLED
        },
        toolRegistered = toolRegistered && status.state == DshWorkspaceTreeGrantState.GRANTED,
        detail = status.detail ?: when (status.state) {
            DshWorkspaceTreeGrantState.GRANTED ->
                if (status.flags.write) {
                    "persisted SAF folder: read+write"
                } else {
                    "persisted SAF folder: read-only"
                }

            DshWorkspaceTreeGrantState.NOT_GRANTED ->
                "no persistent workspace folder grant; DSH must request one through the picker"

            DshWorkspaceTreeGrantState.REVOKED ->
                "the user revoked the workspace folder grant"

            DshWorkspaceTreeGrantState.INVALID ->
                "the persisted tree permission is no longer held"

            DshWorkspaceTreeGrantState.PROVIDER_UNAVAILABLE ->
                "the document provider is unavailable"
        }
    )
}
