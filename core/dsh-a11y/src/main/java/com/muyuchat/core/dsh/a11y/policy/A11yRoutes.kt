package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executes one validated command. Implemented by the Android adapter in
 * `com.muyuchat.core.dsh.a11y.android.DshA11yController`; tests use a fake.
 */
fun interface A11yEngine {
    suspend fun execute(command: A11yCommand): A11yOutcome
}

/**
 * Transport seam of this module: the 14 accessibility paths as a
 * [DshCapabilityRouteTable] subset for `:core:dsh-platform`.
 *
 * The platform owns authentication, generation checks, the idempotency ledger and the socket; this
 * class only owns path ownership, method checks, parsing and wire encoding. An engine failure is
 * converted to a typed `INTERNAL` document instead of a dropped connection, so the official tool
 * package always receives a parseable JSON answer.
 *
 * Wiring (see LANE report): `DshA11yCapabilityRoutes(DshA11yController.instance)` and either
 * `routes.install(platformRouteTableBuilder)` or `routes.routeTable()` merged into the platform
 * table. The same table may be served on `APP_A11Y_PORT`; the official package only uses that port.
 */
class DshA11yCapabilityRoutes(private val engine: A11yEngine) {

    /** Adds every accessibility route to [builder]; a duplicate owner fails at build time. */
    fun install(builder: DshCapabilityRouteTable.Builder): DshCapabilityRouteTable.Builder {
        A11yRequestParser.SUPPORTED_PATHS.forEach { path ->
            builder.route(path, handlerFor(path))
        }
        return builder
    }

    /** Builds a standalone table (useful when the platform merges per-domain tables). */
    fun routeTable(): DshCapabilityRouteTable = install(DshCapabilityRouteTable.builder()).build()

    private fun handlerFor(path: String): DshCapabilityRouteHandler =
        DshCapabilityRouteHandler { request ->
            val outcome = when {
                request.method != expectedMethod(path) -> A11yOutcome.Rejected(
                    A11yRejection.invalid(
                        "$path only accepts ${expectedMethod(path).name} requests"
                    )
                )

                else -> execute(request)
            }
            DshCapabilityRouteResponse.Json(statusCode = 200, bodyJson = A11yWire.encode(outcome))
        }

    private suspend fun execute(request: DshCapabilityRouteRequest): A11yOutcome {
        val parsed = A11yRequestParser.parse(request)
        return when (parsed) {
            is A11yParseResult.Rejected -> A11yOutcome.Rejected(parsed.rejection)
            is A11yParseResult.Command -> try {
                engine.execute(parsed.command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Throwable) {
                A11yOutcome.Rejected(
                    A11yRejection.internal("accessibility command failed inside the platform adapter")
                )
            }
        }
    }

    private fun expectedMethod(path: String): DshCapabilityHttpMethod =
        if (path == "/gesture") DshCapabilityHttpMethod.POST else DshCapabilityHttpMethod.GET
}
