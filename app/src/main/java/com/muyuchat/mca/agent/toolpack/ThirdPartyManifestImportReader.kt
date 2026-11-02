package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJsonValue

/**
 * Builds a normalised third-party descriptor and compatibility report from already-decoded JSON values.
 *
 * The reader is pure: manifest object in, immutable descriptor/report out. It reads no file, starts
 * no engine, resolves no dependency and never translates anything into an executable MCA package.
 */
internal class ThirdPartyManifestImportReader {

    fun read(
        root: ToolPackageJsonValue.ObjectValue,
        manifestFormat: ThirdPartyManifestFormat,
        manifestSha256: String,
        listing: ThirdPartyNormalizedListing,
        declaredContexts: List<ThirdPartyDeclaredContext>
    ): ThirdPartyToolPackageImportResult.Previewed {
        val session = ThirdPartyManifestSession(root, listing, declaredContexts)
        val header = ThirdPartyManifestHeaderReader(session).read()
        val resourceReader = ThirdPartyManifestResourceReader(session)
        val resources = resourceReader.readResources()
        resourceReader.readLogo(resources)
        val wasmModules = resourceReader.readWasmModules()
        val workflowTemplates = resourceReader.readTemplates(
            field = "workflow_templates",
            requireDirectory = false,
            resources = resources
        )
        val workspaceTemplates = resourceReader.readTemplates(
            field = "workspace_templates",
            requireDirectory = true,
            resources = resources
        )
        scanUndeclaredBinaryArtifacts(session, header, resources, wasmModules)
        val contexts = ThirdPartyManifestContextReader(session).read(header.subpackages)
        val descriptor = ThirdPartyToolPackageDescriptor(
            manifestFormat = manifestFormat,
            toolPkgId = header.toolPkgId,
            normalizedPackageId = header.normalizedPackageId,
            version = header.version,
            apiVersion = header.apiVersion,
            displayName = header.displayName,
            description = header.description,
            mainEntry = header.mainEntry,
            subpackages = header.subpackages,
            resources = resources.values.toList(),
            wasmModules = wasmModules,
            workflowTemplates = workflowTemplates,
            workspaceTemplates = workspaceTemplates,
            dependencies = header.dependencies,
            manifestSha256 = manifestSha256,
            contentSha256 = ThirdPartyContentHash.compute(manifestSha256, listing.entries)
        )
        return ThirdPartyToolPackageImportResult.Previewed(
            descriptor = descriptor,
            report = ThirdPartyCompatibilityReport(session.findings.toList(), contexts)
        )
    }

    /**
     * Surfaces executable/binary files that no manifest field declares. They are not loaded, but
     * they must not disappear from the report: an unlisted DEX/JNI archive is exactly the kind of
     * second channel the compatibility report has to make visible.
     */
    private fun scanUndeclaredBinaryArtifacts(
        session: ThirdPartyManifestSession,
        header: ThirdPartyManifestHeader,
        resources: Map<String, ThirdPartyResourceDescriptor>,
        wasmModules: List<ThirdPartyWasmModuleDescriptor>
    ) {
        val declared = HashSet<String>()
        declared += header.mainEntry
        header.subpackages.forEach { declared += it.entry }
        resources.values.forEach { declared += it.path }
        wasmModules.forEach { declared += it.path }
        session.listing.entries.forEachIndexed { index, entry ->
            if (entry.isDirectory || entry.path in declared) return@forEachIndexed
            val suffix = entry.path.substringAfterLast('.', "").lowercase()
            if (suffix !in THIRD_PARTY_BINARY_SUFFIXES) return@forEachIndexed
            session.add(
                ThirdPartyCompatibilityCategory.RESOURCE,
                entry.path.take(256),
                "listing[$index]",
                ThirdPartyCompatibilityStatus.UNSUPPORTED,
                "An executable/binary artifact is present but not declared by the manifest; it is never loaded.",
                "Remove the artifact or declare a reviewed resource with a matching non-executable format."
            )
        }
    }
}
