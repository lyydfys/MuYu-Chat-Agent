package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolPackageHostOperation
import com.muyuchat.core.agent.contract.AgentToolPackageHostParameter
import com.muyuchat.core.agent.contract.AgentToolPackageParameterType
import com.muyuchat.core.agent.contract.AgentToolSideEffect

/**
 * Read-only view of the **code-owned** host operations a tool package may bind to.
 *
 * A manifest only stores an operation id. This catalog is the trusted half of the binding: the
 * implementation, schema, side effect, data class and timeout all come from the app's built-in
 * catalog, never from package content. P0 exposes read-only workspace operations only; no HTTP, MCP,
 * shell, UI, provider or package-proxy operation can be represented here.
 */
interface AgentToolPackageHostCatalog {
    fun find(operationId: String): AgentToolPackageHostOperation?

    fun operations(): List<AgentToolPackageHostOperation>

    companion object {
        val EMPTY: AgentToolPackageHostCatalog = of(emptyList())

        /** Builds an inert catalog (tests/composition only); it never executes anything. */
        fun of(operations: List<AgentToolPackageHostOperation>): AgentToolPackageHostCatalog {
            val byId = operations.sortedBy { it.operationId }.associateBy { it.operationId }
            require(byId.size == operations.size) { "host catalog contains duplicate operation ids" }
            return object : AgentToolPackageHostCatalog {
                override fun find(operationId: String): AgentToolPackageHostOperation? = byId[operationId]

                override fun operations(): List<AgentToolPackageHostOperation> = byId.values.toList()
            }
        }
    }
}

/**
 * Derives the trusted host-operation metadata from the frozen built-in tool catalog.
 *
 * The allowlist is `workspace.list`, `workspace.read_text` and `workspace.search_text` — the three
 * existing read-only built-ins. A write operation such as `artifact.write_text` is intentionally
 * absent: package writes are a later P1 slice gated on the P0 approval pipeline. If a listed
 * operation's descriptor stops being read-only (or its schema drifts outside the scalar subset the
 * binder understands), construction fails instead of silently exposing a weaker/garbled tool.
 *
 * The catalog is metadata only. It exposes no [com.muyuchat.core.agent.contract.AgentToolExecutor]
 * and cannot execute package content.
 */
class BuiltInToolPackageHostCatalog private constructor(
    private val byId: Map<String, AgentToolPackageHostOperation>
) : AgentToolPackageHostCatalog {

    override fun find(operationId: String): AgentToolPackageHostOperation? = byId[operationId]

    override fun operations(): List<AgentToolPackageHostOperation> = byId.values.toList()

    companion object {
        private val ALLOWED_DATA_CLASSES = setOf(
            AgentDataClass.PUBLIC,
            AgentDataClass.PRIVATE,
            AgentDataClass.LOCAL_ONLY
        )

        fun readOnly(builtInCatalog: AgentToolCatalog): BuiltInToolPackageHostCatalog {
            val byId = LinkedHashMap<String, AgentToolPackageHostOperation>()
            for (descriptor in builtInCatalog.snapshot()) {
                if (descriptor.toolKey !in AgentToolPackageLimits.READ_ONLY_HOST_OPERATIONS) continue
                val executor = requireNotNull(builtInCatalog.find(descriptor.toolKey)) {
                    "built-in catalog is missing its own descriptor: ${descriptor.toolKey}"
                }
                require(executor.descriptor == descriptor) {
                    "built-in catalog descriptor identity changed while reading it: ${descriptor.toolKey}"
                }
                require(descriptor.sideEffect == AgentToolSideEffect.READ_ONLY) {
                    "package host operation must be read-only: ${descriptor.toolKey}"
                }
                if (descriptor.dataClass !in ALLOWED_DATA_CLASSES) continue
                require(descriptor.timeoutMillis in 1L..AgentToolPackageHostOperation.MAX_HOST_TIMEOUT_MILLIS) {
                    "host operation timeout is outside the bounded range: ${descriptor.toolKey}"
                }
                byId[descriptor.toolKey] = AgentToolPackageHostOperation(
                    operationId = descriptor.toolKey,
                    displayName = descriptor.displayName,
                    sideEffect = descriptor.sideEffect,
                    dataClass = descriptor.dataClass,
                    parameters = parseParameters(descriptor.toolKey, descriptor.schemaJson),
                    maxTimeoutMillis = descriptor.timeoutMillis
                )
            }
            return BuiltInToolPackageHostCatalog(byId)
        }

        private fun parseParameters(
            operationId: String,
            schemaJson: String
        ): List<AgentToolPackageHostParameter> {
            val root = ToolPackageJson.parse(schemaJson) as? ToolPackageJsonValue.ObjectValue
                ?: fail(operationId, "schema root is not an object")
            if (root.stringField("type") != "object") fail(operationId, "schema root type is not object")
            if (root.fields["additionalProperties"] != ToolPackageJsonValue.BooleanValue(false)) {
                fail(operationId, "schema root must forbid additional properties")
            }
            val properties = root.fields["properties"] as? ToolPackageJsonValue.ObjectValue
                ?: fail(operationId, "schema properties are missing")
            val required = when (val value = root.fields["required"]) {
                null -> emptySet()
                is ToolPackageJsonValue.ArrayValue -> value.items.map { item ->
                    (item as? ToolPackageJsonValue.StringValue)?.value
                        ?: fail(operationId, "schema required list must contain strings")
                }.toSet()
                else -> fail(operationId, "schema required list is malformed")
            }
            if (required.any { it !in properties.fields.keys }) {
                fail(operationId, "schema required names an undeclared property")
            }
            return properties.fields.keys.sorted().map { name ->
                val node = properties.fields.getValue(name) as? ToolPackageJsonValue.ObjectValue
                    ?: fail(operationId, "parameter schema is not an object")
                val (type, nullable) = parseType(operationId, name, node)
                AgentToolPackageHostParameter(
                    name = name,
                    type = type,
                    required = name in required,
                    nullable = nullable,
                    minLength = optionalInteger(operationId, name, node, "minLength")?.toInt(),
                    maxLength = optionalInteger(operationId, name, node, "maxLength")?.toInt(),
                    minimum = optionalInteger(operationId, name, node, "minimum"),
                    maximum = optionalInteger(operationId, name, node, "maximum")
                )
            }
        }

        private fun parseType(
            operationId: String,
            parameter: String,
            node: ToolPackageJsonValue.ObjectValue
        ): Pair<AgentToolPackageParameterType, Boolean> {
            val typeValue = node.fields["type"]
                ?: fail(operationId, "parameter '$parameter' has no type")
            val names = when (typeValue) {
                is ToolPackageJsonValue.StringValue -> listOf(typeValue.value)
                is ToolPackageJsonValue.ArrayValue -> typeValue.items.map { item ->
                    (item as? ToolPackageJsonValue.StringValue)?.value
                        ?: fail(operationId, "parameter '$parameter' type array must contain strings")
                }
                else -> fail(operationId, "parameter '$parameter' type is malformed")
            }
            if (names.isEmpty() || names.size > 2 || names.toSet().size != names.size) {
                fail(operationId, "parameter '$parameter' type union is unsupported")
            }
            val nullable = "null" in names
            if (nullable && names.size != 2) {
                fail(operationId, "parameter '$parameter' nullability is malformed")
            }
            val concrete = names.firstOrNull { it != "null" }
                ?: fail(operationId, "parameter '$parameter' has no concrete type")
            val type = when (concrete) {
                "string" -> AgentToolPackageParameterType.STRING
                "integer" -> AgentToolPackageParameterType.INTEGER
                "boolean" -> AgentToolPackageParameterType.BOOLEAN
                else -> fail(operationId, "parameter '$parameter' type is outside the package subset")
            }
            return type to nullable
        }

        private fun optionalInteger(
            operationId: String,
            parameter: String,
            node: ToolPackageJsonValue.ObjectValue,
            key: String
        ): Long? = when (val value = node.fields[key]) {
            null -> null
            is ToolPackageJsonValue.IntegerValue -> value.value
            else -> fail(operationId, "parameter '$parameter' bound '$key' is not an integer")
        }

        private fun ToolPackageJsonValue.ObjectValue.stringField(key: String): String? =
            (fields[key] as? ToolPackageJsonValue.StringValue)?.value

        private fun fail(operationId: String, detail: String): Nothing =
            throw IllegalStateException("built-in host operation '$operationId' is not package-compatible: $detail")
    }
}
