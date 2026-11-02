package com.muyuchat.mca.agent.toolpack

/**
 * What one archive turned out to be, decided by its own declaration.
 *
 * The three package formats are parsed by different owners, so an import surface must not pick one
 * by hand. [ToolPackageLifecycleService.previewImport] classifies the archive first and routes to the
 * parser that owns that format; every branch is read-only and nothing here installs, enables,
 * extracts to disk or executes package content.
 */
sealed interface ToolPackageImportPreview {

    /**
     * Legacy inert v1: its declaration is readable, but this runtime neither installs nor runs it.
     * The preview store keeps its own read path for that format.
     */
    data class LegacyInert(val note: String) : ToolPackageImportPreview

    /** MCA executable v2: the parsed manifest plus the scripts the strict archive reader accepted. */
    data class Executable(
        val manifest: ExecutableToolPackageManifest,
        val manifestBytes: ByteArray,
        val scripts: Map<String, String>
    ) : ToolPackageImportPreview

    /**
     * third-party package: translated by the existing translation service. A [ThirdPartyTranslationResult]
     * rejection carries the exact field path and the unsupported symbol; a translation never
     * installs anything on its own.
     */
    data class ThirdParty(val translation: ThirdPartyTranslationResult) : ToolPackageImportPreview

    /** The archive declared nothing usable, or its own parser refused it. */
    data class Rejected(val code: ToolPackageErrorCode, val safeMessage: String) : ToolPackageImportPreview

    companion object {
        const val LEGACY_NOTE: String =
            "A legacy inert v1 package is readable but this runtime does not install or run it."
    }
}
