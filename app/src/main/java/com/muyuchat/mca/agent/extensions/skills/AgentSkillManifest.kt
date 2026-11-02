package com.muyuchat.mca.agent.extensions.skills

/**
 * Where an installed Skill package came from. [fingerprint] is a content-derived identity of the
 * imported tree (`tree:<sha256>` or `zip:<sha256>`) and is stable across re-imports.
 */
data class AgentSkillSource(
    val uri: String,
    val fingerprint: String
)

/**
 * MCA installation manifest for one Skill (architecture 12.1).
 *
 * [version] is MCA's installation version and increases on update; [packageVersion] is the version
 * declared inside the package's frontmatter ("1" when absent). [snapshotId] identifies the private
 * immutable file snapshot. [sha256] is the lowercase hex content hash of installed files.
 */
data class AgentSkillManifest(
    val id: String,
    val name: String,
    val description: String,
    val packageVersion: String,
    val source: AgentSkillSource,
    val sha256: String,
    val entryPath: String,
    val enabled: Boolean,
    val declaredToolPatterns: List<String>,
    val resourcePaths: List<String>,
    val version: Long,
    val snapshotId: String,
    val installedAtMillis: Long,
    val metadata: Map<String, AgentSkillMetadataValue> = emptyMap(),
    val schemaVersion: Int = AgentSkillLimits.SCHEMA_VERSION
) {
    init {
        require(id.matches(ID_PATTERN)) { "skill id is not a stable lowercase identifier" }
        requireValidName(name)
        requireValidDescription(description)
        require(packageVersion.isNotBlank() && packageVersion.length <= 64) {
            "packageVersion must be non-blank and at most 64 characters"
        }
        require(source.uri.isNotBlank() && source.uri.length <= 2_048) { "source uri is not bounded" }
        require(source.fingerprint.matches(FINGERPRINT_PATTERN)) { "source fingerprint is invalid" }
        require(sha256.matches(SHA256_PATTERN)) { "sha256 must be lowercase hex" }
        require(entryPath == ENTRY_PATH) { "V1 requires the entry path to be $ENTRY_PATH" }
        require(version >= 1L) { "installation version must be positive" }
        require(snapshotId.matches(SNAPSHOT_ID_PATTERN)) { "snapshotId is invalid" }
        require(installedAtMillis >= 0L) { "installedAtMillis must not be negative" }
        require(schemaVersion == AgentSkillLimits.SCHEMA_VERSION) {
            "unsupported Skill manifest schema version: $schemaVersion"
        }
        require(resourcePaths == resourcePaths.distinct().sorted()) {
            "resourcePaths must be unique and sorted"
        }
        resourcePaths.forEach { path ->
            require(path != ENTRY_PATH) { "resourcePaths must not repeat the entry path" }
            require(AgentSkillPathPolicy.isNormalizedRelativeFile(path)) {
                "resourcePaths contains a non-normalized path: '$path'"
            }
        }
        require(declaredToolPatterns == declaredToolPatterns.distinct()) {
            "declaredToolPatterns must be unique"
        }
        AgentSkillToolDeclaration(declaredToolPatterns)
        require(metadata.size <= AgentSkillLimits.MAX_METADATA_KEYS) { "too many metadata keys" }
    }

    companion object {
        const val ENTRY_PATH: String = "SKILL.md"
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val SNAPSHOT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,79}$")
        private val FINGERPRINT_PATTERN = Regex("^(tree|zip):[0-9a-f]{64}$")

        fun requireValidName(name: String) {
            require(name.isNotBlank()) { "skill name must not be blank" }
            require(name == name.trim()) { "skill name must not start or end with whitespace" }
            require(name.codePointCount(0, name.length) <= AgentSkillLimits.MAX_NAME_CODE_POINTS) {
                "skill name exceeds ${AgentSkillLimits.MAX_NAME_CODE_POINTS} characters"
            }
            require(name.none { it == '\n' || it == '\r' || it == '\u0000' || it.isISOControl() }) {
                "skill name must be a single line without control characters"
            }
        }

        fun requireValidDescription(description: String) {
            require(description.isNotBlank()) { "skill description must not be blank" }
            require(description.toByteArray(Charsets.UTF_8).size <= AgentSkillLimits.MAX_DESCRIPTION_BYTES) {
                "skill description exceeds ${AgentSkillLimits.MAX_DESCRIPTION_BYTES} bytes"
            }
            require(!description.contains('\u0000')) { "skill description must not contain NUL" }
        }
    }
}

/** Result of [AgentSkillManager.uninstall]: the removed manifest and snapshot-retention decision. */
data class AgentSkillUninstallResult(
    val removed: AgentSkillManifest,
    val snapshotRetained: Boolean
)
