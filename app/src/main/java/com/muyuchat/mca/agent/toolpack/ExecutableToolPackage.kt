package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson

/**
 * One validated executable package: the parsed manifest plus the immutable script snapshot the
 * isolated engine may read.
 *
 * The bundle is the only package content a script engine ever receives. It has no host path, no
 * `File`, no `Context` and no resource handle; [scripts] is a read-only map normalised to relative
 * package paths. Every manifest-declared entry (the package main entry and each tool entry) must be
 * present, so a package cannot pass validation and then fail because a script was omitted.
 *
 * [contentHash] binds the canonical manifest and every script byte. The value is part of the tool
 * descriptor version and of every host-call audit record; re-using a run against different bytes is
 * therefore impossible without changing the hash.
 */
class ExecutableToolPackage private constructor(
    val manifest: ExecutableToolPackageManifest,
    val scripts: Map<String, String>,
    val contentHash: String
) {
    val packageId: String get() = manifest.packageId

    val packageVersion: String get() = manifest.packageVersion

    val declaredHostApis: Set<String> get() = manifest.hostApis.toSet()

    /** `<packageVersion>+<contentHash16>`; a stable identity for logs, descriptors and ledgers. */
    val versionIdentity: String get() = manifest.packageVersion + "+" + contentHash.take(16)

    fun runtimeToolKey(toolName: String): String = manifest.runtimeToolKey(toolName)

    fun script(path: String): String? = scripts[path]

    companion object {
        /** Domain separator of the package content hash; changing it is a format revision. */
        private const val HASH_DOMAIN = "mca-tool-package/v2+scripts"

        /**
         * Validates and freezes the script snapshot.
         *
         * @throws ToolPackageException when a script path is unsafe, a budget is exceeded or a
         *   manifest-declared entry is absent.
         */
        fun load(
            manifest: ExecutableToolPackageManifest,
            scripts: Map<String, String>
        ): ExecutableToolPackage {
            if (scripts.size > ToolPackageLimits.MAX_SCRIPTS) {
                toolPackageFailure(
                    ToolPackageErrorCode.SCRIPT_TOO_MANY,
                    "script bundle declares more than ${ToolPackageLimits.MAX_SCRIPTS} files"
                )
            }
            val normalized = LinkedHashMap<String, String>(scripts.size)
            var totalBytes = 0L
            for ((rawPath, source) in scripts) {
                val path = ToolPackageScriptPaths.normalize(rawPath)
                    ?: toolPackageFailure(
                        ToolPackageErrorCode.SCRIPT_INVALID_PATH,
                        "script bundle contains an unsafe or non-JavaScript path"
                    )
                if (normalized.containsKey(path)) {
                    toolPackageFailure(
                        ToolPackageErrorCode.SCRIPT_INVALID_PATH,
                        "script bundle contains two paths that normalise to the same file"
                    )
                }
                val bytes = source.toByteArray(Charsets.UTF_8)
                if (bytes.size.toLong() > ToolPackageLimits.MAX_SCRIPT_BYTES) {
                    toolPackageFailure(
                        ToolPackageErrorCode.SCRIPT_TOO_LARGE,
                        "script bundle contains a file above the per-script budget"
                    )
                }
                totalBytes += bytes.size
                if (totalBytes > ToolPackageLimits.MAX_SCRIPTS_TOTAL_BYTES) {
                    toolPackageFailure(
                        ToolPackageErrorCode.SCRIPT_BUNDLE_TOO_LARGE,
                        "script bundle exceeds the total script budget"
                    )
                }
                normalized[path] = source
            }
            if (normalized.isEmpty()) {
                toolPackageFailure(ToolPackageErrorCode.SCRIPT_MISSING, "script bundle is empty")
            }
            if (!normalized.containsKey(manifest.entry)) {
                toolPackageFailure(
                    ToolPackageErrorCode.SCRIPT_MISSING,
                    "script bundle is missing the package main entry"
                )
            }
            for (tool in manifest.tools) {
                if (!normalized.containsKey(tool.entry)) {
                    toolPackageFailure(
                        ToolPackageErrorCode.SCRIPT_MISSING,
                        "script bundle is missing an entry declared by a tool"
                    )
                }
            }
            val contentHash = contentHash(manifest.manifestSha256, normalized)
            return ExecutableToolPackage(manifest, normalized.toMap(), contentHash)
        }

        private fun contentHash(manifestSha256: String, scripts: Map<String, String>): String {
            val builder = StringBuilder(HASH_DOMAIN.length + 128 + scripts.size * 80)
            builder.append(HASH_DOMAIN).append('\n')
            builder.append(manifestSha256).append('\n')
            for ((path, source) in scripts.toSortedMap()) {
                val scriptHash = ToolPackageJson.sha256Hex(source.toByteArray(Charsets.UTF_8))
                builder.append(path).append(' ').append(scriptHash).append('\n')
            }
            return ToolPackageJson.sha256Hex(builder.toString().toByteArray(Charsets.UTF_8))
        }
    }
}

/** Convenience entry point used by an importer/tests: parse the manifest, then freeze the scripts. */
class ExecutableToolPackageLoader(
    private val parser: ExecutableToolPackageManifestParser = ExecutableToolPackageManifestParser()
) {
    fun load(manifestBytes: ByteArray, scripts: Map<String, String>): ExecutableToolPackage =
        ExecutableToolPackage.load(parser.parse(manifestBytes), scripts)

    fun load(manifestText: String, scripts: Map<String, String>): ExecutableToolPackage =
        ExecutableToolPackage.load(parser.parse(manifestText), scripts)
}
