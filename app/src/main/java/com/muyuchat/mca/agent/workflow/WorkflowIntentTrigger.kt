package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import java.io.ByteArrayOutputStream

/**
 * Typed origin of one workflow run.
 *
 * This is the sealed projection of the persisted [WorkflowTriggerKind] enum: it adds the fields a
 * trigger needs to prove *why* a run started (an intent action/sender, a schedule slot) without
 * changing the stored enum's wire form. [WorkflowTriggerKind.INTENT] is the new variant added by
 * this change; manual and schedule keep their exact previous semantics.
 */
sealed interface WorkflowTriggerSource {
    val kind: WorkflowTriggerKind

    data object Manual : WorkflowTriggerSource {
        override val kind: WorkflowTriggerKind = WorkflowTriggerKind.MANUAL
    }

    data class Schedule(val slotMillis: Long) : WorkflowTriggerSource {
        init {
            require(slotMillis > 0L) { "schedule trigger slot must be positive" }
        }

        override val kind: WorkflowTriggerKind = WorkflowTriggerKind.SCHEDULE
    }

    /**
     * An external app asked for a run. [action] is the exact registered action; it is the only
     * fact the receiver trusts, and it must match the registration the user enabled.
     */
    data class Intent(
        val action: String,
        val definitionId: String,
        val version: Long,
        val senderPackage: String? = null
    ) : WorkflowTriggerSource {
        init {
            require(action.isNotBlank()) { "intent trigger action must not be blank" }
            require(definitionId.isNotBlank()) { "intent trigger definitionId must not be blank" }
            require(version >= 1L) { "intent trigger version must be positive" }
        }

        override val kind: WorkflowTriggerKind = WorkflowTriggerKind.INTENT
    }
}

/** Stable, user-facing refusal codes of the intent trigger. Each one is a concrete condition. */
object WorkflowIntentTriggerErrorCodes {
    const val NOT_REGISTERED = "WF_INTENT_NOT_REGISTERED"
    const val MALFORMED_URI = "WF_INTENT_MALFORMED_URI"
    const val MISSING_DEFINITION = "WF_INTENT_MISSING_DEFINITION"
    const val VERSION_INVALID = "WF_INTENT_VERSION_INVALID"
    const val EXTRA_NOT_ALLOWED = "WF_INTENT_EXTRA_NOT_ALLOWED"
    const val DEFINITION_NOT_PUBLISHED = "WF_INTENT_DEFINITION_NOT_PUBLISHED"
    const val DISABLED = "WF_INTENT_DISABLED"
}

/**
 * One registration row: the exact intent action that may start one published definition version.
 *
 * The action is the user-visible identity (`com.muyuchat.mca.action.RUN_WORKFLOW`); [enabled] is
 * the user's switch, so a registered but disabled action is refused instead of silently running.
 * Nothing here is model-authored: the row is written by the settings/admin surface.
 */
data class WorkflowIntentRegistration(
    val action: String,
    val definitionId: String,
    val version: Long,
    val enabled: Boolean = true
) {
    init {
        require(isAcceptableAction(action)) { "intent action is not acceptable" }
        require(definitionId.isNotBlank()) { "intent registration definitionId must not be blank" }
        require(version >= 1L) { "intent registration version must be positive" }
    }

    companion object {
        const val MAX_ACTION_CHARS: Int = 160
        private val ACTION_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.]{2,158}$")

        /**
         * First-segment names this registry refuses to own: platform/system actions belong to the
         * OS or to another app, so registering one would hijack an unrelated broadcast.
         */
        private val RESERVED_ACTION_ROOTS: Set<String> = setOf("android", "androidx", "com.android")

        /**
         * Actions may only be a dotted identity. It must not carry a scheme separator, a wildcard
         * or whitespace, so a registration can never name another app's implicit-broadcast catch-all.
         */
        fun isAcceptableAction(action: String): Boolean =
            action.length in 3..MAX_ACTION_CHARS &&
                ACTION_PATTERN.matches(action) &&
                action.count { it == '.' } >= 1 &&
                action.substringBefore('.') !in RESERVED_ACTION_ROOTS
    }
}

/** Result of registering one intent trigger. A refusal names the exact rule that failed. */
sealed interface WorkflowIntentRegistrationResult {
    data class Registered(val registration: WorkflowIntentRegistration) : WorkflowIntentRegistrationResult

    data class Rejected(val code: String, val safeMessage: String) : WorkflowIntentRegistrationResult
}

/**
 * The raw facts of one received intent, independent of the platform Intent type.
 *
 * Keeping this a plain value object is what lets the parsing/rejection rules be JVM unit-tested;
 * the Android receiver only copies action/data/extras into it.
 */
data class WorkflowIntentEnvelope(
    val action: String?,
    val dataUri: String? = null,
    val extras: Map<String, String> = emptyMap(),
    val senderPackage: String? = null
)

/** Accepted request: everything later stages need, with the idempotency key already derived. */
data class WorkflowIntentRequest(
    val commandId: String,
    val definitionId: String,
    val version: Long,
    val action: String,
    val senderPackage: String? = null,
    val runId: String? = null
) {
    init {
        AgentContract.requireId(commandId, "intent commandId")
        require(definitionId.isNotBlank()) { "intent definitionId must not be blank" }
        require(version >= 1L) { "intent version must be positive" }
        require(action.isNotBlank()) { "intent action must not be blank" }
    }

    fun toCommand(): WorkflowIntentRunCommand = WorkflowIntentRunCommand(
        commandId = commandId,
        definitionId = definitionId,
        version = version,
        runId = runId
    )
}

sealed interface WorkflowIntentParseResult {
    data class Accepted(val request: WorkflowIntentRequest) : WorkflowIntentParseResult

    data class Rejected(val code: String, val safeMessage: String) : WorkflowIntentParseResult
}

/**
 * Registration table plus the strict parser for external intent triggers.
 *
 * Rules, all enforced before any task is created:
 *  1. the action must be registered and [WorkflowIntentRegistration.enabled];
 *  2. the definition version named by the registration must still be published;
 *  3. the caller may pass only `definition` / `version` / `runId` extras, and only when the
 *     registration leaves them open — an unknown extra is refused instead of ignored, so a typo
 *     can never look like a successful run;
 *  4. a data URI is optional; when present it must be a `mca-workflow:` URI and may only restate
 *     the definition/version.
 *
 * The idempotency key is derived from (action, sender, definition, version, runId), so a repeated
 * broadcast with the same facts is a replay, while two intentional runs need different run ids.
 */
class WorkflowIntentTriggerRegistry(
    registrations: List<WorkflowIntentRegistration> = emptyList(),
    private val publishedVersionExists: (definitionId: String, version: Long) -> Boolean = { _, _ -> true }
) {
    private val byAction = LinkedHashMap<String, WorkflowIntentRegistration>()

    init {
        registrations.forEach(::register)
    }

    fun list(): List<WorkflowIntentRegistration> = byAction.values.toList()

    fun find(action: String?): WorkflowIntentRegistration? =
        action?.let { byAction[it.trim()] }

    fun register(registration: WorkflowIntentRegistration): WorkflowIntentRegistrationResult {
        val code = when {
            byAction.containsKey(registration.action) -> "WF_INTENT_ALREADY_REGISTERED"
            byAction.size >= MAX_REGISTRATIONS -> "WF_INTENT_REGISTRATION_LIMIT"
            else -> null
        }
        if (code != null) {
            return WorkflowIntentRegistrationResult.Rejected(
                code,
                "The intent trigger is already registered or the table is full."
            )
        }
        byAction[registration.action] = registration
        return WorkflowIntentRegistrationResult.Registered(registration)
    }

    fun replace(registration: WorkflowIntentRegistration): WorkflowIntentRegistrationResult {
        byAction[registration.action] = registration
        return WorkflowIntentRegistrationResult.Registered(registration)
    }

    fun unregister(action: String): Boolean = byAction.remove(action.trim()) != null

    /** Parses one received intent. Never throws; every refusal is typed and user-visible. */
    fun parse(envelope: WorkflowIntentEnvelope): WorkflowIntentParseResult {
        val action = envelope.action?.trim().orEmpty()
        if (action.isEmpty()) {
            return reject(
                WorkflowIntentTriggerErrorCodes.NOT_REGISTERED,
                "The intent has no action."
            )
        }
        val registration = byAction[action]
            ?: return reject(
                WorkflowIntentTriggerErrorCodes.NOT_REGISTERED,
                "This action is not registered as a workflow trigger."
            )
        if (!registration.enabled) {
            return reject(
                WorkflowIntentTriggerErrorCodes.DISABLED,
                "The intent trigger for this workflow is disabled."
            )
        }
        val uriValues = when (val parsed = parseDataUri(envelope.dataUri)) {
            is UriResult.Rejected -> return reject(parsed.code, parsed.message)
            is UriResult.Accepted -> parsed.values
        }
        for (key in envelope.extras.keys + uriValues.keys) {
            if (key !in ALLOWED_KEYS) {
                return reject(
                    WorkflowIntentTriggerErrorCodes.EXTRA_NOT_ALLOWED,
                    "The intent carries an unsupported extra: $key"
                )
            }
        }
        val requestedDefinition = envelope.extras[KEY_DEFINITION]
            ?: uriValues[KEY_DEFINITION]
            ?: registration.definitionId
        if (requestedDefinition != registration.definitionId) {
            return reject(
                WorkflowIntentTriggerErrorCodes.MISSING_DEFINITION,
                "The intent names a workflow that this trigger is not registered for."
            )
        }
        val requestedVersion = when (val raw = envelope.extras[KEY_VERSION] ?: uriValues[KEY_VERSION]) {
            null -> registration.version
            else -> raw.toLongOrNull()
                ?: return reject(
                    WorkflowIntentTriggerErrorCodes.VERSION_INVALID,
                    "The intent version is not a positive integer."
                )
        }
        if (requestedVersion != registration.version) {
            return reject(
                WorkflowIntentTriggerErrorCodes.VERSION_INVALID,
                "The intent names a workflow version that this trigger is not registered for."
            )
        }
        val published = try {
            publishedVersionExists(registration.definitionId, registration.version)
        } catch (_: Exception) {
            false
        }
        if (!published) {
            return reject(
                WorkflowIntentTriggerErrorCodes.DEFINITION_NOT_PUBLISHED,
                "The workflow version behind this trigger is no longer published."
            )
        }
        val runId = envelope.extras[KEY_RUN_ID] ?: uriValues[KEY_RUN_ID]
        if (runId != null && !isAcceptableRunId(runId)) {
            return reject(
                WorkflowIntentTriggerErrorCodes.EXTRA_NOT_ALLOWED,
                "The intent run id is not acceptable."
            )
        }
        return WorkflowIntentParseResult.Accepted(
            WorkflowIntentRequest(
                commandId = intentCommandId(action, envelope.senderPackage, registration, runId),
                definitionId = registration.definitionId,
                version = registration.version,
                action = action,
                senderPackage = envelope.senderPackage,
                runId = runId
            )
        )
    }

    private fun parseDataUri(raw: String?): UriResult {
        if (raw.isNullOrBlank()) return UriResult.Accepted(emptyMap())
        // Parsed by hand on purpose: this package stays free of the JDK URI/socket families, and
        // the only shapes that matter are `mca-workflow://run?k=v` and `mca-workflow:run?k=v`.
        val prefix = "$DATA_SCHEME:"
        if (!raw.startsWith(prefix)) {
            return UriResult.Rejected(
                WorkflowIntentTriggerErrorCodes.MALFORMED_URI,
                "The intent data URI must use the $DATA_SCHEME scheme."
            )
        }
        val rest = raw.substring(prefix.length)
        if (rest.startsWith("//")) {
            // `scheme://host...` — a following third slash would make the target empty/ambiguous.
            if (rest.startsWith("///")) return malformedUri()
        }
        val trimmed = rest.removePrefix("//")
        val queryStart = trimmed.indexOf('?')
        val target = (if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed)
            .removeSuffix("/")
        if (target.isNotEmpty() && target != ALLOWED_PATH) {
            return UriResult.Rejected(
                WorkflowIntentTriggerErrorCodes.MALFORMED_URI,
                "The intent data URI must address '$ALLOWED_PATH'."
            )
        }
        if (queryStart < 0) return UriResult.Accepted(emptyMap())
        val query = trimmed.substring(queryStart + 1)
        if (query.isEmpty()) return UriResult.Accepted(emptyMap())
        val values = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0 || separator == pair.length - 1) {
                return UriResult.Rejected(
                    WorkflowIntentTriggerErrorCodes.MALFORMED_URI,
                    "The intent data URI has a parameter without a value."
                )
            }
            val key = percentDecode(pair.substring(0, separator)) ?: return malformedUri()
            val value = percentDecode(pair.substring(separator + 1)) ?: return malformedUri()
            if (values.containsKey(key)) {
                return UriResult.Rejected(
                    WorkflowIntentTriggerErrorCodes.MALFORMED_URI,
                    "The intent data URI repeats a parameter."
                )
            }
            values[key] = value
        }
        return UriResult.Accepted(values)
    }

    private fun malformedUri(): UriResult = UriResult.Rejected(
        WorkflowIntentTriggerErrorCodes.MALFORMED_URI,
        "The intent data URI is malformed."
    )

    /** Strict UTF-8 `%XX` decoding; a truncated or non-hex escape is refused, never guessed. */
    private fun percentDecode(value: String): String? {
        if (!value.contains('%')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%') {
                if (index + 2 >= value.length) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun reject(code: String, message: String): WorkflowIntentParseResult =
        WorkflowIntentParseResult.Rejected(code, message)

    private sealed interface UriResult {
        data class Accepted(val values: Map<String, String>) : UriResult

        data class Rejected(val code: String, val message: String) : UriResult
    }

    companion object {
        const val MAX_REGISTRATIONS: Int = 32
        const val DATA_SCHEME: String = "mca-workflow"
        const val ALLOWED_PATH: String = "run"
        const val KEY_DEFINITION: String = "definitionId"
        const val KEY_VERSION: String = "version"
        const val KEY_RUN_ID: String = "runId"

        /** Only these extras/parameters are understood; anything else is refused, not ignored. */
        val ALLOWED_KEYS: Set<String> = setOf(KEY_DEFINITION, KEY_VERSION, KEY_RUN_ID)

        private val RUN_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        fun isAcceptableRunId(runId: String): Boolean = RUN_ID_PATTERN.matches(runId)

        /** Deterministic idempotency key so a duplicated broadcast replays instead of re-running. */
        fun intentCommandId(
            action: String,
            senderPackage: String?,
            registration: WorkflowIntentRegistration,
            runId: String?
        ): String = "intent-" + AgentHashes.sha256HexUtf8(
            listOf(
                action,
                senderPackage.orEmpty(),
                registration.definitionId,
                registration.version.toString(),
                runId.orEmpty()
            ).joinToString("|")
        ).take(48)
    }
}

/**
 * Thin dispatcher: parse, then hand the accepted request to the existing entry facade.
 *
 * It performs no execution of its own and creates no second scheduler. A refusal returns the typed
 * parse result to the caller (the receiver logs it) and never starts a task.
 */
class WorkflowIntentTrigger(
    private val registry: WorkflowIntentTriggerRegistry,
    private val facade: WorkflowTriggerFacade
) {
    suspend fun dispatch(envelope: WorkflowIntentEnvelope): WorkflowIntentDispatchResult {
        val parsed = registry.parse(envelope)
        if (parsed is WorkflowIntentParseResult.Rejected) {
            return WorkflowIntentDispatchResult.Rejected(parsed.code, parsed.safeMessage)
        }
        val request = (parsed as WorkflowIntentParseResult.Accepted).request
        return WorkflowIntentDispatchResult.Started(
            request = request,
            receipt = facade.runIntent(request.toCommand())
        )
    }
}

sealed interface WorkflowIntentDispatchResult {
    data class Started(
        val request: WorkflowIntentRequest,
        val receipt: WorkflowEntryReceipt
    ) : WorkflowIntentDispatchResult

    data class Rejected(val code: String, val safeMessage: String) : WorkflowIntentDispatchResult
}
