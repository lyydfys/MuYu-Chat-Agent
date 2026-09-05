package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenieXNativeCompatibilityContractTest {
    @Test
    fun geniexSdkInitializationIsDeferredUntilItsRunnerLoads() {
        val runnerSource = sourceFile(
            "core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt"
        ).substringAfter("internal class GenieXChatRunner(")
        val initBody = functionBody(runnerSource, "override fun initBackends(nativeLibDir: String)")
        val loadBody = functionBody(
            runnerSource,
            "override fun loadModel(modelPath: String, paramsJson: String)"
        )

        assertTrue(initBody.contains("requestedNativeLibDir = nativeLibDir.trim()"))
        assertFalse(initBody.contains("GenieXSdk"))
        assertTrue(loadBody.contains("ensureSdkInitialized("))
    }

    @Test
    fun geniexRequiredSamplerAndJsonEntriesAreProvidedWithoutDuplicateDefinitions() {
        val cmake = sourceFile("core/native/src/main/cpp/CMakeLists.txt")
        val compatibility = sourceFile(
            "core/native/src/main/cpp/llama_common_legacy_compat.cpp"
        )
        val samplingHeader = sourceFile("third_party/llama.cpp/common/sampling.h")
        val samplingSource = sourceFile("third_party/llama.cpp/common/sampling.cpp")
        val vendoredJson = sourceFile("third_party/llama.cpp/vendor/nlohmann/json_fwd.hpp")

        assertTrue(cmake.contains("target_sources(llama-common PRIVATE"))
        assertTrue(cmake.contains("llama_common_legacy_compat.cpp"))
        val samplerSignature =
            """common_sampler\s*\*\s*common_sampler_init\s*\(\s*""" +
                """const\s+struct\s+llama_model\s*\*\s*model\s*,\s*""" +
                """struct\s+common_params_sampling\s*&\s*params\s*\)"""
        assertTrue(Regex("$samplerSignature\\s*;").containsMatchIn(samplingHeader))
        assertTrue(Regex("$samplerSignature\\s*\\{").containsMatchIn(samplingSource))
        assertTrue(compatibility.contains("geniex_sampler_init_fn"))
        assertTrue(compatibility.contains("&common_sampler_init"))
        assertTrue(compatibility.contains("const nlohmann::ordered_json & tools"))
        assertTrue(
            compatibility.contains(
                "common_chat_tools_parse_oaicompat(common_json::parse(tools.dump()))"
            )
        )
        assertFalse(compatibility.contains("return common_sampler_init(model, params,"))
        assertTrue(vendoredJson.contains("NLOHMANN_JSON_VERSION_MAJOR 3"))
        assertTrue(vendoredJson.contains("NLOHMANN_JSON_VERSION_MINOR 12"))
        assertTrue(vendoredJson.contains("NLOHMANN_JSON_VERSION_PATCH 0"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }
}
