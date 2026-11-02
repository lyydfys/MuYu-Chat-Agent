package com.muyuchat.core.dsh.runtime.config

/**
 * One MCP capability an `mcp-servers.json` entry may expect from the pinned DSH payload.
 *
 * This is a declaration-time expectation, not a runtime switch: the official
 * `@deepseek-ai/dsh-mcp-client` row receives only `transport`/`serverName`/`command`/`args`/`env`
 * (or `url`/`headers`), so [DshMcpOverlayRows] never forwards a capability list. Its only job is
 * to let an imported document state what it expects and be told the truth before anything is
 * written.
 */
enum class DshMcpCapability(val token: String) {
    TOOLS("tools"),
    RESOURCES("resources"),
    PROMPTS("prompts");

    /** True only when the pinned payload has a code path that can serve this capability. */
    val servedByPinnedPayload: Boolean
        get() = DshMcpPinnedPayloadSurface.supportOf(this) == DshMcpCapabilitySupport.SERVED

    companion object {
        /** Case-sensitive on purpose: the declaration vocabulary is a closed set of lowercase tokens. */
        fun fromToken(token: String): DshMcpCapability? = entries.firstOrNull { it.token == token }
    }
}

enum class DshMcpCapabilitySupport {
    /** The pinned payload performs this operation on its own and exposes the result to the model. */
    SERVED,

    /** No pinned-payload code path performs this operation; declaring it is refused. */
    BLOCKED
}

/**
 * The MCP surface the pinned DSH payload actually ships, read from the packaged payload (not from
 * documentation):
 *
 * - `@deepseek-ai/dsh-mcp-client` 0.2.1-rc.2 connects to one server and registers its tools on
 *   `ctx.tools` as `mcp__<serverName>__<rawName>` (payload
 *   `.../dsh-mcp-client/lib/index.js`, `syncTools` / `publicToolName`).
 * - `@deepseek-ai/dsh-mcp-resources` 0.2.1-rc.2 is inserted by the shipped `dsh-base` bundle row
 *   `mcp-resources` and registers three model tools: `list_mcp_resources` (`resources/list`),
 *   `list_mcp_resource_templates` (`resources/templates/list`) and `read_mcp_resource`
 *   (`resources/read`). The client registers each connection's resource operations through
 *   `ctx.inject(["mcpResources"], ...)`. MCA must not proxy or reimplement this protocol.
 * - No pinned-payload package calls `prompts/list` or `prompts/get`. The MCP SDK dependency can
 *   encode those methods, but no DSH plugin uses them, so prompts are not a served capability.
 *
 * Device/runtime behaviour (handshake, resource payloads, reconnect, cancel) is **not** proven by
 * this compile-time matrix; see
 * `docs/proposals/agent-dsh-embedded-v1/DSH_MCP_RESOURCES_CLOSURE_20261029.md`.
 */
object DshMcpPinnedPayloadSurface {

    /**
     * Harness-reserved environment namespace. The pinned `@deepseek-ai/dsh-subprocess` scrub drops
     * every `DSH_*` name (case-insensitive) from the inherited environment; a declaration must not
     * inject one back through the explicit `env` map.
     */
    const val RESERVED_ENV_PREFIX = "DSH_"

    /** Tool names the official `mcp-resources` plugin registers for every connected server. */
    val resourceTools: List<String> = listOf(
        "list_mcp_resources",
        "list_mcp_resource_templates",
        "read_mcp_resource"
    )

    fun supportOf(capability: DshMcpCapability): DshMcpCapabilitySupport = when (capability) {
        DshMcpCapability.TOOLS,
        DshMcpCapability.RESOURCES -> DshMcpCapabilitySupport.SERVED

        // BLOCKED: no DSH plugin performs prompts/list or prompts/get. Do not fake support by
        // forwarding a prompt-shaped field to a plugin that would ignore it.
        DshMcpCapability.PROMPTS -> DshMcpCapabilitySupport.BLOCKED
    }

    /**
     * How the official stdio transport builds a child environment.
     *
     * The payload starts from `scrubbedParentEnv()` (the host environment minus
     * `/KEY|PASSWORD|SECRET|TOKEN/i` names and every `DSH_*` name), then merges the declaration's
     * explicit `env` map last. It is a **name scrub, not an allowlist**: unrelated parent values
     * survive, and an explicit `env` entry can deliberately forward a credential-shaped name or
     * re-introduce a `DSH_*` name. MCA therefore cannot claim "no inherited secrets"; it can only
     * refuse to re-introduce the harness-reserved `DSH_*` namespace itself.
     */
    val stdioEnvironment: DshMcpStdioEnvContract = DshMcpStdioEnvContract(
        inheritsHostEnvironment = true,
        scrubbedNameRule = "drops /KEY|PASSWORD|SECRET|TOKEN/i and all DSH_* names (case-insensitive)",
        declarationEnvMergedLast = true,
        explicitAllowlist = false
    )
}

/**
 * Typed, value-free description of the stdio child environment contract. It exists so a settings
 * surface can state the real isolation boundary instead of implying an allowlist that the pinned
 * payload does not implement.
 */
data class DshMcpStdioEnvContract(
    val inheritsHostEnvironment: Boolean,
    val scrubbedNameRule: String,
    val declarationEnvMergedLast: Boolean,
    val explicitAllowlist: Boolean
)
