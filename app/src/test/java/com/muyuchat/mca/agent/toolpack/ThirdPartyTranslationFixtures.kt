package com.muyuchat.mca.agent.toolpack

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixed third-party ToolPkg fixtures for the translation and closed-loop tests. */
internal object ThirdPartyTranslationFixtures {
    const val TOOLPKG_ID: String = "Demo.Writer"
    const val MAIN_ENTRY: String = "main.js"
    const val SUBPACKAGE_ENTRY: String = "demo/write_report.js"
    const val LIB_ENTRY: String = "lib/math.js"

    fun manifest(
        requires: Boolean = false,
        wasm: Boolean = false,
        resources: Boolean = false,
        templates: Boolean = false
    ): String = buildString {
        append("{")
        append("\"schema_version\":1,")
        append("\"toolpkg_id\":\"").append(TOOLPKG_ID).append("\",")
        append("\"version\":\"1.2.3\",")
        append("\"api_version\":\"1.0.0\",")
        append("\"main\":\"").append(MAIN_ENTRY).append("\",")
        append("\"display_name\":\"third-party Demo Writer\",")
        append("\"description\":\"Writes a report through Tools.Files.\",")
        append(
            "\"subpackages\":[{\"id\":\"write_report\",\"entry\":\"" + SUBPACKAGE_ENTRY +
                "\",\"enabled_by_default\":true,\"display_name\":\"Write report\"}]"
        )
        if (requires) {
            append(",\"requires\":[{\"id\":\"thirdparty.dep\",\"description\":\"dependency\",\"min_version\":\"1.0.0\"}]")
        }
        if (wasm) {
            append(",\"wasm_modules\":[{\"id\":\"core\",\"path\":\"modules/core.wasm\",\"exports\":[\"run\"]}]")
        }
        if (resources) {
            append(",\"resources\":[{\"key\":\"logo\",\"path\":\"resources/logo.png\",\"mime\":\"image/png\"}]")
        }
        if (templates) {
            append(
                ",\"workflow_templates\":[{\"id\":\"wf\",\"display_name\":\"Workflow\"," +
                    "\"description\":\"template\",\"resource_key\":\"wf\"}]"
            )
        }
        append("}")
    }

    fun scripts(
        subpackageSource: String = DEFAULT_SUBPACKAGE_SOURCE,
        mainSource: String = "// third-party main registration is not executed by the translator.\n"
    ): Map<String, String> = linkedMapOf(
        MAIN_ENTRY to mainSource,
        SUBPACKAGE_ENTRY to subpackageSource,
        LIB_ENTRY to "exports.compose = function (text, addition) {\n" +
            "  return String(text).trim() + \"|\" + String(addition).toUpperCase();\n" +
            "};\n"
    )

    fun listing(extra: List<String> = emptyList()): ThirdPartyPackageListing =
        ThirdPartyPackageListing(
            (scripts().keys + extra).map { ThirdPartyListedEntry(it) }
        )

    fun archive(
        manifest: String = manifest(),
        scripts: Map<String, String> = scripts()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((path, source) in scripts) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(source.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    const val DEFAULT_SUBPACKAGE_SOURCE: String =
        "var math = require('../lib/math.js');\n" +
            "exports.main = function (input) {\n" +
            "  return Promise.resolve().then(function () {\n" +
            "    var source = Tools.Files.read(input.path);\n" +
            "    var value = math.compose(source, input.content);\n" +
            "    var written = Tools.Files.write(input.outPath, value);\n" +
            "    return { value: value, artifactId: written.artifactId, sizeBytes: written.sizeBytes };\n" +
            "  });\n" +
            "};\n"
}
