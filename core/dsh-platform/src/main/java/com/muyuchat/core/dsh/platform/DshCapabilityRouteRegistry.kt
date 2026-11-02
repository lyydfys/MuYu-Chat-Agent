package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteLimits
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable

/** One fixed route: its allowed verbs, the capability it requires and its typed handler. */
data class DshCapabilityRouteDefinition(
    val path: String,
    val methods: Set<DshCapabilityHttpMethod>,
    val capability: DshCapability,
    val handler: DshCapabilityRouteHandler
) {
    init {
        require(path.startsWith('/') && path.length <= DshCapabilityRouteLimits.MAX_PATH_CHARS) {
            "capability route path must be an absolute local path"
        }
        require(methods.isNotEmpty()) { "capability route '$path' must allow at least one method" }
    }
}

/** Resolution of one `(path, method)` pair, decided before any handler or throttle is reached. */
sealed interface DshCapabilityRouteResolution {
    data class Found(val definition: DshCapabilityRouteDefinition) : DshCapabilityRouteResolution

    data object NotFound : DshCapabilityRouteResolution

    data class MethodNotAllowed(
        val allowed: Set<DshCapabilityHttpMethod>
    ) : DshCapabilityRouteResolution
}

/**
 * The fixed route table plus the two facts the contract table does not carry: which verbs a route
 * accepts and which [DshCapability] a request must be authorized for.
 *
 * It is the `DshCapabilityRouteTable` consumer: [build] constructs the immutable contract table
 * from the same registrations, so the dispatch surface and the published route list cannot drift.
 * Changing the surface requires a new registry (and a new server generation), not a mutable map.
 */
class DshCapabilityRouteRegistry private constructor(
    private val definitions: Map<String, DshCapabilityRouteDefinition>,
    private val contractTable: DshCapabilityRouteTable
) {
    /** Immutable contract view of this registry; safe to publish and enumerate. */
    fun routeTable(): DshCapabilityRouteTable = contractTable

    fun paths(): List<String> = contractTable.paths()

    fun definitionFor(path: String): DshCapabilityRouteDefinition? = definitions[path]

    fun resolve(path: String, method: DshCapabilityHttpMethod?): DshCapabilityRouteResolution {
        val definition = definitions[path] ?: return DshCapabilityRouteResolution.NotFound
        if (method == null || method !in definition.methods) {
            return DshCapabilityRouteResolution.MethodNotAllowed(definition.methods)
        }
        return DshCapabilityRouteResolution.Found(definition)
    }

    class Builder {
        private val definitions = LinkedHashMap<String, DshCapabilityRouteDefinition>()

        /** Registers a GET-only route. */
        fun get(
            path: String,
            capability: DshCapability,
            handler: DshCapabilityRouteHandler
        ): Builder = route(path, setOf(DshCapabilityHttpMethod.GET), capability, handler)

        /** Registers a POST-only route. */
        fun post(
            path: String,
            capability: DshCapability,
            handler: DshCapabilityRouteHandler
        ): Builder = route(path, setOf(DshCapabilityHttpMethod.POST), capability, handler)

        /** Registers one route for an explicit verb set. */
        fun route(
            path: String,
            methods: Set<DshCapabilityHttpMethod>,
            capability: DshCapability,
            handler: DshCapabilityRouteHandler
        ): Builder {
            require(definitions.put(path, DshCapabilityRouteDefinition(path, methods, capability, handler)) == null) {
                "capability route '$path' is already registered"
            }
            return this
        }

        fun build(): DshCapabilityRouteRegistry {
            val tableBuilder = DshCapabilityRouteTable.builder()
            definitions.values.forEach { definition ->
                tableBuilder.route(definition.path, definition.handler)
            }
            return DshCapabilityRouteRegistry(
                definitions = definitions.toMap(),
                contractTable = tableBuilder.build()
            )
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
