package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentEffectKind
import com.muyuchat.core.agent.contract.AgentResourceScope
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentCanonicalJson

/**
 * Typed resource scopes for the tools whose resource identity is not a workspace path.
 *
 * The frozen-command factory needs a scope before the approval is shown. For `http.get` and
 * `terminal.exec` the exact resource (origin, argv) is known only after the normalizer has
 * canonicalised the model arguments, so the run owner calls these helpers with the *normalized*
 * argument JSON (`AgentArgumentNormalization.Normalized.normalizedArgumentsJson`). The result binds
 * the same origin/argv bytes the executor will use, and the factory rejects the call if the scope is
 * missing (a network/process tool can never be approved against a fake workspace path).
 *
 * A returned `null` means "the normalized arguments do not describe this tool" — the owner must
 * reject the call, not guess a scope.
 */
object AgentExtendedToolScopes {
    /** Scope of one approved `http.get`: the exact canonical origin, method GET, NETWORK_READ. */
    fun httpGet(normalizedArgumentsJson: String): AgentScopePrecondition? {
        val url = readText(normalizedArgumentsJson, "url") ?: return null
        val destination = when (val checked = AgentHttpDestinationRules.check(url)) {
            is AgentHttpUrlResult.Valid -> checked
            is AgentHttpUrlResult.Invalid -> return null
        }
        return AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Network(
                    origins = listOf(destination.origin),
                    methods = setOf("GET"),
                    dataClass = AgentDataClass.PRIVATE
                )
            ),
            effects = setOf(AgentEffectKind.NETWORK_READ)
        )
    }

    /**
     * Scope of one approved `terminal.exec`: the exact argv hash plus the sandbox identity.
     *
     * [runtimeId], [cwdHandle] and [environmentPolicy] are host facts owned by the sandbox adapter;
     * they are never taken from model text.
     */
    fun terminalExec(
        normalizedArgumentsJson: String,
        runtimeId: String,
        cwdHandle: String,
        environmentPolicy: String
    ): AgentScopePrecondition? {
        val command = readText(normalizedArgumentsJson, "command") ?: return null
        val args = readText(normalizedArgumentsJson, "args").orEmpty()
        val tokens = args.split(' ').filter { it.isNotEmpty() }
        val digest = StringBuilder("mca.terminal.argv.v1")
        digest.append('\n').append(command)
        tokens.forEach { token -> digest.append('\n').append(token) }
        val argvHash = AgentCanonicalJson.sha256Hex(digest.toString().toByteArray(Charsets.UTF_8))
        return AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Process(
                    runtimeId = runtimeId,
                    cwdHandle = cwdHandle,
                    argvHash = argvHash,
                    environmentPolicy = environmentPolicy
                )
            ),
            effects = setOf(AgentEffectKind.PROCESS_EXEC)
        )
    }

    private fun readText(normalizedArgumentsJson: String, key: String): String? {
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(normalizedArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return null
        }
        return (fields[key] as? AgentArgValue.Text)?.value
    }
}
