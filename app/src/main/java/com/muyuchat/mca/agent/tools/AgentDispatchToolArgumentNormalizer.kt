package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentBuiltInToolArgumentNormalizer
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer

/**
 * Routes one tool key to the normalizer that owns its schema (N08/N13/N05d).
 *
 * Each family normalizer already delegates unknown keys to the V1 built-in normalizer, so the only
 * job here is selecting the right owner **before** freeze: a write tool must resolve defaults and
 * canonical paths with its own rules, not with the workspace.read schema. Unrouted keys fall back to
 * the V1 normalizer, keeping every existing descriptor's behavior unchanged.
 */
class AgentDispatchToolArgumentNormalizer(
    routes: List<Pair<Set<String>, AgentToolArgumentNormalizer>>,
    private val fallback: AgentToolArgumentNormalizer = AgentBuiltInToolArgumentNormalizer(),
    prefixRoutes: List<Pair<String, AgentToolArgumentNormalizer>> = emptyList()
) : AgentToolArgumentNormalizer {
    private val routes: List<Pair<Set<String>, AgentToolArgumentNormalizer>> = routes.toList()
    private val prefixRoutes: List<Pair<String, AgentToolArgumentNormalizer>> = prefixRoutes.toList()

    override fun normalize(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String
    ): AgentArgumentNormalization {
        val owner = routes.firstOrNull { (keys, _) -> descriptor.toolKey in keys }?.second
            ?: prefixRoutes.firstOrNull { (prefix, _) -> descriptor.toolKey.startsWith(prefix) }?.second
        return (owner ?: fallback).normalize(descriptor, rawArgumentsJson)
    }
}
