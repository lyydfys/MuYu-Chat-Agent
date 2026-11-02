package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshGatewayModel
import java.util.UUID

/**
 * D06/D07: provider identity, primary/auxiliary routing and the desired/applied revision loop for
 * the internal MCA model gateway.
 *
 * The gateway never invents provider facts: the official DeepSeek account provider is served by the
 * official DSH module (its ids are opaque and never rewritten here), while MCA models keep the
 * stable `mca-local:` / `mca-cloud:` namespacing the app adapter already publishes. An operation
 * pins the resolved snapshot it started with; a later catalog/binding change only affects new
 * operations, and an old request is settled under its own binding instead of being replayed.
 */
enum class DshModelBackend(
    val stableIdPrefix: String?,
    val networkRequired: Boolean
) {
    /** Official DeepSeek account provider; MCA must not rename or re-key its models. */
    DEEPSEEK_ACCOUNT(null, true),
    MCA_LOCAL("mca-local:", false),
    MCA_CLOUD("mca-cloud:", true);

    companion object {
        /**
         * Classifies a served model id. Official account ids are opaque, so anything that is not a
         * documented MCA namespace belongs to the account provider rather than being guessed.
         */
        fun classify(modelId: String): DshModelBackend = when {
            modelId.startsWith(MCA_LOCAL.stableIdPrefix!!) -> MCA_LOCAL
            modelId.startsWith(MCA_CLOUD.stableIdPrefix!!) -> MCA_CLOUD
            else -> DEEPSEEK_ACCOUNT
        }
    }
}

/** Auxiliary inference calls that must be routed explicitly, never silently to a hidden cloud. */
enum class DshAuxiliaryRoute {
    TITLE,
    COMPRESSION,
    SUBAGENT,
    MEMORY
}

/** Immutable resolution recorded for one operation. */
data class DshModelRouteSnapshot(
    val operation: String,
    val modelId: String,
    val backend: DshModelBackend,
    val catalogRevision: String,
    val bindingGeneration: Long,
    val networkRequired: Boolean,
    val supportsTools: Boolean,
    /** null means the provider did not declare vision; it is never claimed as supported. */
    val visionDeclared: Boolean?
) {
    val resolvedKey: String
        get() = "$modelId@$catalogRevision#$bindingGeneration"
}

enum class DshRouteSuspendReason {
    /** The configured route needs the network while the user asked for local-only inference. */
    OFFLINE_NOT_LOCAL,
    /** The model is no longer advertised (unloaded/removed); it is not replaced automatically. */
    MODEL_NOT_IN_CATALOG,
    /** The route needs a capability this backend/model does not have. */
    CAPABILITY_MISSING,
    /** A local primary with a cloud auxiliary needs an explicit user decision. */
    MIXED_ROUTING_NOT_APPROVED
}

sealed interface DshRouteDecision {
    data class Resolved(val snapshot: DshModelRouteSnapshot) : DshRouteDecision

    data class Suspended(
        val reason: DshRouteSuspendReason,
        val route: DshAuxiliaryRoute?,
        val detail: String
    ) : DshRouteDecision

    /** The user must choose explicitly; nothing is inferred and no request is sent. */
    data class RequiresExplicitUserChoice(val route: DshAuxiliaryRoute) : DshRouteDecision
}

/**
 * Pure routing policy for one host configuration.
 *
 * Offline means "all inference, including every auxiliary route, must be served by a local model".
 * A missing local capability suspends the auxiliary call instead of silently using the account or
 * cloud route, and a mixed (local primary + cloud auxiliary) setup requires an explicit user
 * choice instead of being assumed.
 */
class DshModelRoutingPolicy(
    private val offline: Boolean,
    private val mixedRoutingApproved: Boolean
) {
    fun routePrimary(
        modelId: String,
        catalog: List<DshGatewayModel>,
        catalogRevision: String,
        bindingGeneration: Long
    ): DshRouteDecision = resolve("primary", modelId, null, catalog, catalogRevision, bindingGeneration)

    fun routeAuxiliary(
        route: DshAuxiliaryRoute,
        modelId: String,
        catalog: List<DshGatewayModel>,
        catalogRevision: String,
        bindingGeneration: Long,
        requireTools: Boolean = false
    ): DshRouteDecision = resolve(
        operation = route.name.lowercase(),
        modelId = modelId,
        route = route,
        catalog = catalog,
        catalogRevision = catalogRevision,
        bindingGeneration = bindingGeneration,
        requireTools = requireTools
    )

    private fun resolve(
        operation: String,
        modelId: String,
        route: DshAuxiliaryRoute?,
        catalog: List<DshGatewayModel>,
        catalogRevision: String,
        bindingGeneration: Long,
        requireTools: Boolean = false
    ): DshRouteDecision {
        val backend = DshModelBackend.classify(modelId)
        val model = catalog.firstOrNull { it.id == modelId }
            ?: return DshRouteDecision.Suspended(
                DshRouteSuspendReason.MODEL_NOT_IN_CATALOG,
                route,
                "model '$modelId' is not in catalog revision $catalogRevision"
            )
        if (offline && backend.networkRequired) {
            return DshRouteDecision.Suspended(
                DshRouteSuspendReason.OFFLINE_NOT_LOCAL,
                route,
                "offline mode cannot serve ${backend.name} route '$modelId'"
            )
        }
        if (requireTools && !model.supportsTools) {
            return DshRouteDecision.Suspended(
                DshRouteSuspendReason.CAPABILITY_MISSING,
                route,
                "model '$modelId' does not support tools"
            )
        }
        return DshRouteDecision.Resolved(
            DshModelRouteSnapshot(
                operation = operation,
                modelId = modelId,
                backend = backend,
                catalogRevision = catalogRevision,
                bindingGeneration = bindingGeneration,
                networkRequired = backend.networkRequired,
                supportsTools = model.supportsTools,
                visionDeclared = null
            )
        )
    }

    /**
     * A local primary with a cloud auxiliary is a mixed configuration: it sends part of the turn to
     * the network, so it needs an explicit user choice instead of being assumed from the primary.
     */
    fun mixedRoutingDecision(primaryModelId: String, auxModelId: String): DshRouteDecision? {
        val primary = DshModelBackend.classify(primaryModelId)
        val aux = DshModelBackend.classify(auxModelId)
        if (primary == DshModelBackend.MCA_LOCAL && aux.networkRequired && !mixedRoutingApproved) {
            return DshRouteDecision.RequiresExplicitUserChoice(DshAuxiliaryRoute.COMPRESSION)
        }
        return null
    }

}

/** One immutable catalog/binding revision. */
data class DshCatalogRevisionStamp(
    val catalogRevision: String,
    val bindingGeneration: Long
)

/** One rebind command the gateway/host may execute; the command id makes an ACK unambiguous. */
data class DshBindingCommand(
    val commandId: String,
    val runtimeEpoch: Long,
    val desired: DshCatalogRevisionStamp
)

enum class DshAckRejection {
    /** The gateway applied an older catalog/binding than the command asked for. */
    STALE_REVISION,
    /** The ACK came from another runtime generation (service/Node restart). */
    IDENTITY_MISMATCH,
    /** The gateway has no record of this command. */
    COMMAND_UNKNOWN
}

sealed interface DshCatalogAck {
    data class Applied(val commandId: String, val applied: DshCatalogRevisionStamp) : DshCatalogAck

    data class Rejected(
        val commandId: String,
        val reason: DshAckRejection,
        val applied: DshCatalogRevisionStamp?
    ) : DshCatalogAck

    /** A late ACK for a command that was already settled (old generation/old catalog). */
    data class IgnoredLate(val commandId: String) : DshCatalogAck

    /** A rebind was requested while work is provably in flight: the user must stop explicitly. */
    data class NeedsExplicitUserStop(val detail: String) : DshCatalogAck

    /** A new rebind command is in flight; its ACK must echo [DshBindingCommand.commandId]. */
    data class CommandIssued(val command: DshBindingCommand) : DshCatalogAck

    /** No ACK arrived in time; query the same command id, never re-dispatch or replay a turn. */
    data class QueryRequired(val commandId: String) : DshCatalogAck
}

/**
 * desired/applied revision coordinator.
 *
 * Rules (D07a/D07b):
 * - a rebind command is created only for a concrete desired revision and carries the current
 *   runtime epoch; an ACK from another epoch is an identity mismatch, never applied;
 * - an ACK whose applied revision is older than the desired one is rejected as stale and leaves the
 *   previous applied revision authoritative;
 * - a late/duplicate ACK never overwrites a newer applied state;
 * - a busy host is never guessed idle by a timer: [requestRebind] answers
 *   [DshCatalogAck.NeedsExplicitUserStop] unless quiescence is proven by the caller;
 * - a timeout produces [DshCatalogAck.QueryRequired]: the same command id is queried, and an
 *   unproven outcome is never retried as a new turn.
 */
class DshCatalogBindingCoordinator(
    initialApplied: DshCatalogRevisionStamp? = null,
    private val runtimeEpoch: Long,
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private var appliedStamp: DshCatalogRevisionStamp? = initialApplied
    private var pending: DshBindingCommand? = null

    fun applied(): DshCatalogRevisionStamp? = synchronized(lock) { appliedStamp }

    fun pendingCommand(): DshBindingCommand? = synchronized(lock) { pending }

    fun requestRebind(
        catalogRevision: String,
        busyOperation: Boolean,
        quiescenceProven: Boolean
    ): DshCatalogAck = synchronized(lock) {
        if (busyOperation && !quiescenceProven) {
            return DshCatalogAck.NeedsExplicitUserStop(
                "a model operation is still in flight; stop it explicitly before rebinding"
            )
        }
        pending?.let {
            return DshCatalogAck.IgnoredLate("rebind already pending as ${it.commandId}")
        }
        val generation = (appliedStamp?.bindingGeneration ?: 0L) + 1L
        val command = DshBindingCommand(
            commandId = commandIdFactory(),
            runtimeEpoch = runtimeEpoch,
            desired = DshCatalogRevisionStamp(catalogRevision, generation)
        )
        pending = command
        DshCatalogAck.CommandIssued(command)
    }

    /**
     * Applies one ACK. The applied revision must be exactly the desired one, for this command and
     * this runtime epoch; every other shape is rejected or ignored without touching the state.
     */
    fun acknowledge(
        commandId: String,
        ackRuntimeEpoch: Long,
        applied: DshCatalogRevisionStamp
    ): DshCatalogAck = synchronized(lock) {
        val command = pending
        if (command == null || command.commandId != commandId) {
            return DshCatalogAck.IgnoredLate(commandId)
        }
        if (ackRuntimeEpoch != command.runtimeEpoch || ackRuntimeEpoch != runtimeEpoch) {
            return DshCatalogAck.Rejected(commandId, DshAckRejection.IDENTITY_MISMATCH, appliedStamp)
        }
        if (applied.bindingGeneration < command.desired.bindingGeneration) {
            return DshCatalogAck.Rejected(commandId, DshAckRejection.STALE_REVISION, appliedStamp)
        }
        if (applied != command.desired) {
            return DshCatalogAck.Rejected(commandId, DshAckRejection.STALE_REVISION, appliedStamp)
        }
        appliedStamp = applied
        pending = null
        DshCatalogAck.Applied(commandId, applied)
    }

    /** No ACK in time: query the same command, never issue a new one and never replay a turn. */
    fun timeout(commandId: String): DshCatalogAck = synchronized(lock) {
        val command = pending ?: return DshCatalogAck.IgnoredLate(commandId)
        if (command.commandId != commandId) return DshCatalogAck.IgnoredLate(commandId)
        DshCatalogAck.QueryRequired(commandId)
    }

    /**
     * The answer to a timed-out query. `null` means the gateway still cannot confirm the command;
     * the previous applied revision stays authoritative and the command is not retried.
     */
    fun resolveQuery(commandId: String, queried: DshCatalogRevisionStamp?): DshCatalogAck =
        synchronized(lock) {
            val command = pending ?: return DshCatalogAck.IgnoredLate(commandId)
            if (command.commandId != commandId) return DshCatalogAck.IgnoredLate(commandId)
            if (queried == null) {
                return DshCatalogAck.Rejected(commandId, DshAckRejection.COMMAND_UNKNOWN, appliedStamp)
            }
            acknowledge(commandId, runtimeEpoch, queried)
        }

    /**
     * Catalog events may arrive out of order. A stamp older than the applied binding is a stale
     * catalog observation and must not be published as the new revision.
     */
    fun acceptCatalogEvent(stamp: DshCatalogRevisionStamp): Boolean = synchronized(lock) {
        val current = appliedStamp ?: return false
        stamp.bindingGeneration >= current.bindingGeneration
    }
}

