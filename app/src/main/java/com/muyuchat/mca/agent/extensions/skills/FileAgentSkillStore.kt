package com.muyuchat.mca.agent.extensions.skills

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable Skill manifest store backed by one private JSON file under `files/agent/skills`.
 *
 * The file is small (manifest metadata only; package files live in immutable snapshots) and is
 * rewritten atomically on every mutation. Corrupt state fails closed with [AgentSkillErrorCode.STORE_FAILURE]
 * instead of silently dropping installed Skills. Version CAS is enforced under one lock, matching the
 * contract of [AgentSkillStore].
 */
class FileAgentSkillStore(
    private val rootDirectory: File,
    private val fileName: String = "manifests.json"
) : AgentSkillStore {
    private val lock = Any()
    private val file: File = File(rootDirectory, fileName).also { target ->
        require(target.canonicalFile.parentFile == rootDirectory.canonicalFile) {
            "skill store file must stay inside the private root"
        }
    }
    private val manifests = LinkedHashMap<String, AgentSkillManifest>()

    init {
        rootDirectory.mkdirs()
        if (!rootDirectory.isDirectory) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "skill store root is unavailable")
        }
        if (file.isFile) load()
    }

    override fun list(): List<AgentSkillManifest> = synchronized(lock) {
        manifests.values.sortedBy { it.id }
    }

    override fun find(skillId: String): AgentSkillManifest? = synchronized(lock) { manifests[skillId] }

    override fun create(manifest: AgentSkillManifest): AgentSkillManifest = synchronized(lock) {
        if (manifests.containsKey(manifest.id)) {
            skillFailure(AgentSkillErrorCode.VERSION_CONFLICT, "skill '${manifest.id}' is already installed")
        }
        manifests[manifest.id] = manifest
        persistLocked()
        manifest
    }

    override fun update(manifest: AgentSkillManifest, expectedVersion: Long): AgentSkillManifest =
        synchronized(lock) {
            val current = manifests[manifest.id]
                ?: skillFailure(AgentSkillErrorCode.SKILL_NOT_FOUND, "skill '${manifest.id}' is not installed")
            if (current.version != expectedVersion) {
                skillFailure(
                    AgentSkillErrorCode.VERSION_CONFLICT,
                    "skill '${manifest.id}' changed from expected version $expectedVersion"
                )
            }
            manifests[manifest.id] = manifest
            persistLocked()
            manifest
        }

    override fun setEnabled(skillId: String, enabled: Boolean): AgentSkillManifest = synchronized(lock) {
        val current = manifests[skillId]
            ?: skillFailure(AgentSkillErrorCode.SKILL_NOT_FOUND, "skill '$skillId' is not installed")
        val updated = current.copy(enabled = enabled)
        manifests[skillId] = updated
        persistLocked()
        updated
    }

    override fun delete(skillId: String): AgentSkillManifest = synchronized(lock) {
        val removed = manifests.remove(skillId)
            ?: skillFailure(AgentSkillErrorCode.SKILL_NOT_FOUND, "skill '$skillId' is not installed")
        persistLocked()
        removed
    }

    private fun load() {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "skill store could not be read")
        }
        try {
            val array = JSONArray(text)
            for (index in 0 until array.length()) {
                val manifest = decodeManifest(array.getJSONObject(index))
                manifests[manifest.id] = manifest
            }
        } catch (failure: AgentSkillException) {
            throw failure
        } catch (failure: Exception) {
            manifests.clear()
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "skill store is corrupt")
        }
    }

    private fun persistLocked() {
        rootDirectory.mkdirs()
        val array = JSONArray()
        manifests.values.sortedBy { it.id }.forEach { array.put(encodeManifest(it)) }
        val temp = File(rootDirectory, "$fileName.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStreamSyncer.write(temp, array.toString())
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: AgentSkillException) {
            temp.delete()
            throw failure
        } catch (failure: Exception) {
            temp.delete()
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "skill store could not be written")
        }
    }

    private fun encodeManifest(manifest: AgentSkillManifest): JSONObject = JSONObject().apply {
        put("id", manifest.id)
        put("name", manifest.name)
        put("description", manifest.description)
        put("packageVersion", manifest.packageVersion)
        put("sourceUri", manifest.source.uri)
        put("sourceFingerprint", manifest.source.fingerprint)
        put("sha256", manifest.sha256)
        put("entryPath", manifest.entryPath)
        put("enabled", manifest.enabled)
        put("declaredToolPatterns", JSONArray(manifest.declaredToolPatterns))
        put("resourcePaths", JSONArray(manifest.resourcePaths))
        put("version", manifest.version)
        put("snapshotId", manifest.snapshotId)
        put("installedAtMillis", manifest.installedAtMillis)
        put("schemaVersion", manifest.schemaVersion)
        val metadata = JSONObject()
        manifest.metadata.toSortedMap().forEach { (key, value) ->
            metadata.put(
                key,
                when (value) {
                    is AgentSkillMetadataValue.Text -> JSONObject()
                        .put("type", "text")
                        .put("value", value.value)
                    is AgentSkillMetadataValue.ListValue -> JSONObject()
                        .put("type", "list")
                        .put("value", JSONArray(value.values))
                }
            )
        }
        put("metadata", metadata)
    }

    private fun decodeManifest(json: JSONObject): AgentSkillManifest {
        val patterns = json.getJSONArray("declaredToolPatterns").toStringList()
        val resources = json.getJSONArray("resourcePaths").toStringList()
        val metadataJson = json.optJSONObject("metadata") ?: JSONObject()
        val metadata = LinkedHashMap<String, AgentSkillMetadataValue>()
        metadataJson.keys().forEach { key ->
            val entry = metadataJson.getJSONObject(key)
            metadata[key] = when (entry.getString("type")) {
                "text" -> AgentSkillMetadataValue.Text(entry.getString("value"))
                "list" -> AgentSkillMetadataValue.ListValue(entry.getJSONArray("value").toStringList())
                else -> skillFailure(AgentSkillErrorCode.STORE_FAILURE, "skill metadata type is unsupported")
            }
        }
        return AgentSkillManifest(
            id = json.getString("id"),
            name = json.getString("name"),
            description = json.getString("description"),
            packageVersion = json.getString("packageVersion"),
            source = AgentSkillSource(
                uri = json.getString("sourceUri"),
                fingerprint = json.getString("sourceFingerprint")
            ),
            sha256 = json.getString("sha256"),
            entryPath = json.getString("entryPath"),
            enabled = json.getBoolean("enabled"),
            declaredToolPatterns = patterns,
            resourcePaths = resources,
            version = json.getLong("version"),
            snapshotId = json.getString("snapshotId"),
            installedAtMillis = json.getLong("installedAtMillis"),
            metadata = metadata,
            schemaVersion = json.getInt("schemaVersion")
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { index -> getString(index) }

    private object FileOutputStreamSyncer {
        fun write(file: File, text: String) {
            java.io.FileOutputStream(file).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
        }
    }
}
