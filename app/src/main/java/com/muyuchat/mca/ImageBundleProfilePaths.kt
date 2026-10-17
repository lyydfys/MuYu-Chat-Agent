package com.muyuchat.mca

import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：executionProfile 路径解析（ParsedExecutionProfilePaths /
 * requiredExecutionProfilePaths ×2）自 LocalImageProviderBundleManifest.kt 逐字节平移；
 * 声明行登记为 internal。
 */

internal data class ParsedExecutionProfilePaths(
    val graphPaths: Map<String, String>,
    val requiredPaths: List<String>
) {
    companion object {
        val Empty = ParsedExecutionProfilePaths(emptyMap(), emptyList())
    }
}
internal fun JSONObject.requiredExecutionProfilePaths(): ParsedExecutionProfilePaths {
    val graph = strictOptionalObject("graph")
        ?: throw IllegalArgumentException(
            "Image executionProfile must declare a graph object."
        )
    val graphPaths = linkedMapOf<String, String>()
    val requiredPaths = mutableListOf<String>()
    listOf("textEncoder", "unet", "vae", "vaeEncoder", "controlNet").forEach { field ->
        if (!graph.has(field) || graph.isNull(field)) return@forEach
        val artifact = graph.strictOptionalObject(field)
            ?: throw IllegalArgumentException("Image executionProfile.graph.$field must be an object.")
        val relativePath = artifact.strictRequiredString("relativePath")
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field.relativePath")
        graphPaths[field] = relativePath
        requiredPaths += relativePath
    }
    listOf("schedulerSidecar", "tokenizerSidecar").forEach { field ->
        if (!graph.has(field) || graph.isNull(field)) return@forEach
        val relativePath = graph.strictRequiredString(field)
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field")
        requiredPaths += relativePath
    }
    graph.strictOptionalArray("configSidecars")?.let { sidecars ->
        for (index in 0 until sidecars.length()) {
            val value = sidecars.opt(index)
            val relativePath = (value as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException(
                    "Image executionProfile.graph.configSidecars[$index] must be a non-empty string."
                )
            requireSafeImageRuntimePath(
                relativePath,
                "executionProfile.graph.configSidecars[$index]"
            )
            requiredPaths += relativePath
        }
    }
    return ParsedExecutionProfilePaths(
        graphPaths = graphPaths,
        requiredPaths = requiredPaths.distinct()
    )
}
internal fun ImageExecutionProfile.requiredExecutionProfilePaths(): ParsedExecutionProfilePaths {
    val graphPaths = linkedMapOf<String, String>()
    val requiredPaths = mutableListOf<String>()
    listOf(
        "textEncoder" to graph.textEncoder,
        "unet" to graph.unet,
        "vae" to graph.vae,
        "vaeEncoder" to graph.vaeEncoder,
        "controlNet" to graph.controlNet
    ).forEach { (field, artifact) ->
        val relativePath = artifact?.relativePath ?: return@forEach
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field.relativePath")
        graphPaths[field] = relativePath
        requiredPaths += relativePath
    }
    listOfNotNull(graph.schedulerSidecar, graph.tokenizerSidecar)
        .forEachIndexed { index, relativePath ->
            requireSafeImageRuntimePath(
                relativePath,
                "executionProfile.graph.sidecars[$index]"
            )
            requiredPaths += relativePath
        }
    graph.configSidecars.forEachIndexed { index, relativePath ->
        requireSafeImageRuntimePath(
            relativePath,
            "executionProfile.graph.configSidecars[$index]"
        )
        requiredPaths += relativePath
    }
    return ParsedExecutionProfilePaths(
        graphPaths = graphPaths,
        requiredPaths = requiredPaths.distinct()
    )
}
