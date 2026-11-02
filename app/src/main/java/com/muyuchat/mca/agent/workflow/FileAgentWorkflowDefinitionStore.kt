package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionValidator
import com.muyuchat.core.agent.runtime.workflow.WorkflowDefinitionHasher
import com.muyuchat.core.agent.runtime.workflow.WorkflowValidationResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Bounded summary for the workflow list surface. */
data class AgentWorkflowDefinitionSummary(
    val definitionId: String,
    val displayName: String,
    val latestPublishedVersion: Long,
    val draftRevision: Long?,
    val updatedAtMillis: Long
)

/** One editable draft plus its CAS revision (monotonic per definition). */
data class AgentWorkflowDefinitionDraft(
    val definition: WorkflowDefinition,
    val revision: Long,
    val updatedAtMillis: Long
)

/**
 * Draft/published store for workflow graph definitions (N17b).
 *
 * Drafts are editable with a monotonic `revision` CAS; published versions are immutable, numbered and
 * carry a canonical `definitionHash`. A published version is never rewritten by later draft edits.
 * The file store is the first durable implementation; a Room-backed store can replace it later
 * without changing this interface.
 */
interface AgentWorkflowDefinitionStore {
    fun list(): List<AgentWorkflowDefinitionSummary>

    fun draft(definitionId: String): AgentWorkflowDefinitionDraft?

    /** Creates a draft when [expectedRevision] is null, else CAS-updates it. */
    fun saveDraft(
        definition: WorkflowDefinition,
        expectedRevision: Long?,
        nowMillis: Long
    ): AgentWorkflowDefinitionDraft

    fun publish(
        definitionId: String,
        expectedDraftRevision: Long,
        nowMillis: Long,
        referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition> = emptyMap()
    ): WorkflowDefinition

    fun latest(definitionId: String): WorkflowDefinition?

    fun version(definitionId: String, version: Long): WorkflowDefinition?
}

/**
 * One private JSON file holding every draft and published version.
 *
 * Every mutation is staged to a temp file and moved into place atomically; a corrupt or oversized
 * file fails closed with `STORE_CORRUPT` instead of dropping definitions. All limits are explicit.
 */
class FileAgentWorkflowDefinitionStore(
    rootDirectory: File,
    private val fileName: String = "workflow-definitions.json",
    private val validator: WorkflowDefinitionValidator = WorkflowDefinitionValidator()
) : AgentWorkflowDefinitionStore {
    private val lock = Any()
    private val root: File = rootDirectory.absoluteFile
    private val file: File = File(root, fileName).also { target ->
        require(target.canonicalFile.parentFile == root.canonicalFile) {
            "workflow definition store file must stay inside the private root"
        }
    }
    private var definitions: Map<String, Record> = emptyMap()

    init {
        root.mkdirs()
        if (!root.isDirectory) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_FAILURE, "definition store root is unavailable")
        }
        if (file.isFile) load()
    }

    override fun list(): List<AgentWorkflowDefinitionSummary> = synchronized(lock) {
        definitions.values
            .map { record ->
                AgentWorkflowDefinitionSummary(
                    definitionId = record.definitionId,
                    displayName = record.displayName,
                    latestPublishedVersion = record.published.lastOrNull()?.version ?: 0L,
                    draftRevision = record.draft?.revision,
                    updatedAtMillis = record.updatedAtMillis
                )
            }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.definitionId }))
    }

    override fun draft(definitionId: String): AgentWorkflowDefinitionDraft? = synchronized(lock) {
        definitions[definitionId]?.draft?.let {
            AgentWorkflowDefinitionDraft(it.definition, it.revision, it.updatedAtMillis)
        }
    }

    override fun saveDraft(
        definition: WorkflowDefinition,
        expectedRevision: Long?,
        nowMillis: Long
    ): AgentWorkflowDefinitionDraft = synchronized(lock) {
        val existing = definitions[definition.definitionId]
        require(definition.displayName.isNotBlank()) { "displayName must not be blank" }
        require(definition.displayName.length <= MAX_DISPLAY_NAME_CHARS) {
            "displayName exceeds $MAX_DISPLAY_NAME_CHARS characters"
        }
        if (existing == null) {
            if (expectedRevision != null && expectedRevision != 0L) {
                workflowFailure(
                    AgentWorkflowErrorCodes.VERSION_CONFLICT,
                    "definition '${definition.definitionId}' does not exist"
                )
            }
            if (definitions.size >= MAX_DEFINITIONS) {
                workflowFailure(
                    AgentWorkflowErrorCodes.LIMIT_EXCEEDED,
                    "definition count exceeds $MAX_DEFINITIONS"
                )
            }
        } else if (expectedRevision != null && expectedRevision != existing.draft?.revision) {
            workflowFailure(
                AgentWorkflowErrorCodes.VERSION_CONFLICT,
                "draft revision changed; reload before saving"
            )
        }
        val revision = (existing?.draft?.revision ?: 0L) + 1L
        val stored = definition.copy(definitionHash = "")
        val next = definitions + (definition.definitionId to Record(
            definitionId = definition.definitionId,
            displayName = stored.displayName,
            updatedAtMillis = nowMillis.coerceAtLeast(0L),
            draft = StoredDraft(stored, revision, nowMillis.coerceAtLeast(0L)),
            published = existing?.published ?: emptyList()
        ))
        persist(next)
        definitions = next
        AgentWorkflowDefinitionDraft(stored, revision, nowMillis.coerceAtLeast(0L))
    }

    override fun publish(
        definitionId: String,
        expectedDraftRevision: Long,
        nowMillis: Long,
        referencedDefinitions: Map<WorkflowDefinitionValidator.SubworkflowRef, WorkflowDefinition>
    ): WorkflowDefinition = synchronized(lock) {
        val record = definitions[definitionId]
            ?: workflowFailure(AgentWorkflowErrorCodes.NOT_FOUND, "definition '$definitionId' does not exist")
        val draft = record.draft
            ?: workflowFailure(AgentWorkflowErrorCodes.NOT_FOUND, "definition '$definitionId' has no draft")
        if (draft.revision != expectedDraftRevision) {
            workflowFailure(AgentWorkflowErrorCodes.VERSION_CONFLICT, "draft revision changed; reload before publishing")
        }
        if (record.published.size >= MAX_PUBLISHED_VERSIONS) {
            workflowFailure(
                AgentWorkflowErrorCodes.LIMIT_EXCEEDED,
                "published version count exceeds $MAX_PUBLISHED_VERSIONS"
            )
        }
        val nextVersion = (record.published.lastOrNull()?.version ?: 0L) + 1L
        val candidate = draft.definition.copy(version = nextVersion, definitionHash = "")
        val hash = WorkflowDefinitionHasher.hash(candidate)
        val published = candidate.copy(definitionHash = hash)
        when (val result = validator.validate(published, referencedDefinitions)) {
            is WorkflowValidationResult.Valid -> Unit
            is WorkflowValidationResult.Invalid -> workflowFailure(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "definition is invalid: " + result.issues.joinToString { it.code }.take(240)
            )
        }
        val next = definitions + (definitionId to record.copy(
            updatedAtMillis = nowMillis.coerceAtLeast(0L),
            published = record.published + published
        ))
        persist(next)
        definitions = next
        published
    }

    override fun latest(definitionId: String): WorkflowDefinition? = synchronized(lock) {
        definitions[definitionId]?.published?.lastOrNull()
    }

    override fun version(definitionId: String, version: Long): WorkflowDefinition? = synchronized(lock) {
        definitions[definitionId]?.published?.firstOrNull { it.version == version }
    }

    // ------------------------------------------------------------------ persistence

    private fun load() {
        if (file.length() > MAX_STORE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "definition store exceeds its byte limit")
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_FAILURE, "definition store could not be read", failure)
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_STORE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "definition store exceeds its byte limit")
        }
        try {
            val json = JSONObject(text)
            if (json.optInt("schemaVersion") != SCHEMA_VERSION) {
                workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "unsupported definition store schema")
            }
            val array = json.optJSONArray("definitions") ?: JSONArray()
            val loaded = LinkedHashMap<String, Record>(array.length())
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val record = decodeRecord(item)
                    ?: workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "a stored definition is malformed")
                if (loaded.put(record.definitionId, record) != null) {
                    workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "duplicate stored definition id")
                }
            }
            definitions = loaded
        } catch (failure: AgentWorkflowException) {
            throw failure
        } catch (failure: Exception) {
            workflowFailure(AgentWorkflowErrorCodes.STORE_CORRUPT, "definition store is not valid JSON", failure)
        }
    }

    private fun persist(next: Map<String, Record>) {
        val array = JSONArray()
        next.values.sortedBy { it.definitionId }.forEach { record -> array.put(encodeRecord(record)) }
        val text = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("definitions", array)
            .toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STORE_BYTES) {
            workflowFailure(AgentWorkflowErrorCodes.LIMIT_EXCEEDED, "definition store exceeds its byte limit")
        }
        val temp = File(root, "$fileName.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (failure: Exception) {
            temp.delete()
            workflowFailure(AgentWorkflowErrorCodes.STORE_FAILURE, "definition store could not be written", failure)
        }
    }

    private fun encodeRecord(record: Record): JSONObject = JSONObject()
        .put("definitionId", record.definitionId)
        .put("displayName", record.displayName)
        .put("updatedAtMillis", record.updatedAtMillis)
        .put("draftRevision", record.draft?.revision ?: JSONObject.NULL)
        .put("draft", record.draft?.let { WorkflowDefinitionJson.encode(it.definition) } ?: JSONObject.NULL)
        .put(
            "published",
            JSONArray().also { array ->
                record.published.forEach { definition -> array.put(WorkflowDefinitionJson.encode(definition)) }
            }
        )

    private fun decodeRecord(json: JSONObject): Record? = runCatching {
        val publishedArray = json.optJSONArray("published") ?: JSONArray()
        val published = (0 until publishedArray.length()).map { index ->
            val definition = WorkflowDefinitionJson.decode(publishedArray.getJSONObject(index)) ?: return null
            if (definition.definitionHash.isEmpty() ||
                WorkflowDefinitionHasher.hash(definition) != definition.definitionHash
            ) {
                return null
            }
            definition
        }
        val draftJson = if (json.isNull("draft")) null else json.getJSONObject("draft")
        val draft = draftJson?.let { definitionJson ->
            val definition = WorkflowDefinitionJson.decode(definitionJson) ?: return null
            StoredDraft(
                definition = definition,
                revision = json.getLong("draftRevision"),
                updatedAtMillis = json.getLong("updatedAtMillis")
            )
        }
        Record(
            definitionId = json.getString("definitionId"),
            displayName = json.getString("displayName"),
            updatedAtMillis = json.getLong("updatedAtMillis"),
            draft = draft,
            published = published
        )
    }.getOrNull()

    private data class StoredDraft(
        val definition: WorkflowDefinition,
        val revision: Long,
        val updatedAtMillis: Long
    )

    private data class Record(
        val definitionId: String,
        val displayName: String,
        val updatedAtMillis: Long,
        val draft: StoredDraft?,
        val published: List<WorkflowDefinition>
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_DEFINITIONS = 200
        const val MAX_PUBLISHED_VERSIONS = 100
        const val MAX_DISPLAY_NAME_CHARS = 120
        const val MAX_STORE_BYTES = 4 * 1024 * 1024
    }
}
