package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.platform.DshCapabilityRouteRegistry
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantOwner
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantRoutes

/**
 * Mounts one domain route table into a generation's capability registry.
 *
 * The capability family and the verb set are explicit at the call site because they are the
 * authorization decision, not a domain detail: a route family that is mounted with GET+POST by
 * habit would widen what one token may do. Terminal routes are POST-only, so this helper takes the
 * verb set instead of defaulting silently for every family.
 *
 * Duplicate registration is rejected by [DshCapabilityRouteRegistry.Builder], so two owners can
 * never shadow one local path. Android-free on purpose: the composition unit test mounts the real
 * terminal table with it and asserts the resulting authorization surface.
 */
internal fun mountCapabilityRoutes(
    builder: DshCapabilityRouteRegistry.Builder,
    table: DshCapabilityRouteTable,
    capabilityOf: (String) -> DshCapability,
    methods: Set<DshCapabilityHttpMethod>
): List<String> {
    val mounted = ArrayList<String>(table.paths().size)
    for (path in table.paths()) {
        val handler = table.handlerFor(path) ?: continue
        builder.route(
            path = path,
            methods = methods,
            capability = capabilityOf(path),
            handler = handler
        )
        mounted += path
    }
    return mounted
}

/**
 * Mounts the three workspace-tree routes under [DshCapability.WORKSPACE_TREE].
 *
 * Each path keeps its own verb set (status GET; request/release POST) so a read token cannot open
 * or drop a grant. The table itself comes from the single grant owner, and no file-chooser or
 * download path is mounted here.
 */
internal fun mountWorkspaceTreeGrantRoutes(
    builder: DshCapabilityRouteRegistry.Builder,
    owner: DshWorkspaceTreeGrantOwner
): List<String> {
    val table = DshWorkspaceTreeGrantRoutes.install(DshCapabilityRouteTable.builder(), owner).build()
    val mounted = ArrayList<String>(DshWorkspaceTreeGrantRoutes.paths().size)
    for (path in table.paths()) {
        val handler = table.handlerFor(path) ?: continue
        builder.route(
            path = path,
            methods = DshWorkspaceTreeGrantRoutes.methodsFor(path),
            capability = DshCapability.WORKSPACE_TREE,
            handler = handler
        )
        mounted += path
    }
    return mounted
}
