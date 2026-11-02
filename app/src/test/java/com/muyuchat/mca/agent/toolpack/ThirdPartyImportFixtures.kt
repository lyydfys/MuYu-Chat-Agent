package com.muyuchat.mca.agent.toolpack

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deterministic third-party manifest/listing fixtures shared by the import adapter tests. */
internal fun thirdpartyManifestJson(
    schemaVersion: Int = 1,
    id: String? = "com.thirdparty.windows_bundle",
    version: String? = "0.2.0",
    apiVersion: String? = "1.0.1",
    main: String? = "main.js",
    description: String = "Controls a Windows PC.",
    extra: String = "",
    wasm: String = ""
): String = buildString {
    append("{\"schema_version\":").append(schemaVersion)
    id?.let { append(",\"toolpkg_id\":\"").append(it).append('"') }
    version?.let { append(",\"version\":\"").append(it).append('"') }
    apiVersion?.let { append(",\"api_version\":\"").append(it).append('"') }
    main?.let { append(",\"main\":\"").append(it).append('"') }
    append(",\"display_name\":\"Windows Bundle\"")
    append(",\"description\":\"").append(description).append('"')
    append(",\"subpackages\":[{\"id\":\"windows_control\",\"entry\":\"packages/windows_control.js\",\"enabled_by_default\":true}]")
    append(",\"resources\":$THIRD_PARTY_RESOURCES")
    append(",\"workflow_templates\":$THIRD_PARTY_WORKFLOW_TEMPLATES")
    if (wasm.isNotEmpty()) append(",\"wasm_modules\":").append(wasm)
    if (extra.isNotEmpty()) append(',').append(extra)
    append('}')
}

internal fun thirdpartyHjsonManifest(): String = """
{
  // third-party sample manifest in HJSON syntax.
  schema_version: 1
  toolpkg_id: com.thirdparty.windows_bundle
  version: 0.2.0
  api_version: 1.0.1
  main: main.js
  display_name: Windows Bundle
  description: 'Controls a Windows PC.'
  subpackages: [
    {
      id: windows_control
      entry: packages/windows_control.js
      enabled_by_default: true
    }
  ]
  resources: [
    {
      key: logo
      path: resources/logo.svg
      mime: image/svg+xml
    }
    {
      key: workflow_template
      path: resources/workflow.json
      mime: application/json
    }
  ]
  workflow_templates: [
    {
      id: quick_chat_workflow
      resource_key: workflow_template
    }
  ]
}
""".trimIndent()

internal fun thirdpartyListing(extra: List<ThirdPartyListedEntry> = emptyList()): ThirdPartyPackageListing =
    ThirdPartyPackageListing(
        listOf(
            ThirdPartyListedEntry("main.js", sizeBytes = 12),
            ThirdPartyListedEntry("packages/windows_control.js", sizeBytes = 32),
            ThirdPartyListedEntry("resources/logo.svg", sizeBytes = 8, sha256 = THIRD_PARTY_CONTENT_HASH),
            ThirdPartyListedEntry("resources/workflow.json", sizeBytes = 16, sha256 = THIRD_PARTY_CONTENT_HASH)
        ) + extra
    )

internal fun thirdpartyPackageEntries(
    manifestName: String,
    manifest: String
): List<Pair<String, ByteArray>> = listOf(
    manifestName to manifest.toByteArray(),
    "main.js" to "// main\n".toByteArray(),
    "packages/windows_control.js" to "// subpackage\n".toByteArray(),
    "resources/logo.svg" to "<svg/>".toByteArray(),
    "resources/workflow.json" to "{}".toByteArray()
)

internal fun thirdpartyZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

private val THIRD_PARTY_RESOURCES =
    """[{"key":"logo","path":"resources/logo.svg","mime":"image/svg+xml"},""" +
        """{"key":"workflow_template","path":"resources/workflow.json","mime":"application/json"}]"""
private const val THIRD_PARTY_WORKFLOW_TEMPLATES =
    """[{"id":"quick_chat_workflow","resource_key":"workflow_template"}]"""
internal val THIRD_PARTY_CONTENT_HASH = "b".repeat(64)
