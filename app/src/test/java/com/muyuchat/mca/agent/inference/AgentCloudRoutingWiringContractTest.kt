package com.muyuchat.mca.agent.inference

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P05 wiring evidence at the source level.
 *
 * The picker may only offer cloud models when the production Agent runtime actually routes them:
 * the assembly must construct the cloud adapter + bounded Responses transport behind
 * AgentRoutingModelPort, and the Agent inference package must stay independent of the DSH runtime.
 */
class AgentCloudRoutingWiringContractTest {

    @Test
    fun productionAssemblyRoutesLocalAndCloudThroughTheSameModelPort() {
        val assembly = sourceFile("app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt")
        val body = functionBody(assembly, "fun runtime(): AgentRuntimeAssembly")

        assertTrue(body.contains("McaAgentLocalModelAdapter("))
        assertTrue(body.contains("AgentCloudModelAdapter("))
        assertTrue(body.contains("OpenAiResponsesBoundedTransport(agentCloudHttpClient())"))
        assertTrue(body.contains("CloudStoreModelConfigResolver(cloudApiStore)"))
        assertTrue(body.contains("AgentRoutingModelPort("))
        assertTrue(body.contains("modelPort = modelPort"))
    }

    @Test
    fun cloudCatalogVisibilityIsDerivedFromTheSameAssemblyThatWiresRouting() {
        val assembly = sourceFile("app/src/main/java/com/muyuchat/mca/composition/AgentHostAssembly.kt")
        val body = functionBody(assembly, "fun runtime(): AgentRuntimeAssembly")
        val ports = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModelAgentPorts.kt")

        // The flag is truthful only while the wiring above exists in this exact assembly.
        assertTrue(assembly.contains("internal val cloudModelRoutingWired: Boolean = true"))
        assertTrue(body.contains("AgentRoutingModelPort("))
        assertTrue(
            "the assembly-owned client must disable implicit transport retries",
            assembly.contains(".retryOnConnectionFailure(false)")
        )
        // Production visibility is not a standalone switch: it reads the assembly that routes cloud
        // bindings, so the picker cannot offer a cloud model before the cloud adapter is wired.
        assertTrue(
            ports.contains("includeCloudModels = host.cloudModelRoutingWired")
        )
    }

    @Test
    fun cloudAdapterAcceptsARequestScopedConfigResolverWithLegacyDefault() {
        val adapter =
            sourceFile("app/src/main/java/com/muyuchat/mca/agent/inference/AgentCloudModelAdapter.kt")

        assertTrue(adapter.contains("private val requestConfig: AgentCloudRequestConfigResolver? = null"))
        assertTrue(adapter.contains("resolver.resolve(request.binding)"))
        assertTrue(adapter.contains("configSource.load()"))
        assertTrue(
            "a null request-scoped resolution must stay MODEL_NOT_LOADED",
            adapter.contains("MODEL_NOT_LOADED")
        )
    }

    @Test
    fun routePredicateUsesOnlyTheFrozenBindingMarkers() {
        val router = sourceFile(
            "app/src/main/java/com/muyuchat/mca/agent/inference/AgentRoutingModelPort.kt"
        )
        val catalog = sourceFile(
            "app/src/main/java/com/muyuchat/mca/presentation/AgentModelCatalogAdapter.kt"
        )

        assertTrue(router.contains("AGENT_CLOUD_RUNTIME_PREFIX: String = \"CLOUD_\""))
        assertTrue(router.contains("AGENT_CLOUD_PROVIDER_PREFIX: String = \"cloud:\""))
        assertTrue(router.contains("binding.runtime.startsWith(AGENT_CLOUD_RUNTIME_PREFIX)"))
        assertTrue(router.contains("binding.providerId.startsWith(AGENT_CLOUD_PROVIDER_PREFIX)"))
        assertTrue(router.contains("routedAttempts[request.attempt.attemptId] = routed"))
        assertTrue(router.contains("routedAttempts.remove(request.attempt.attemptId, routed)"))
        // The catalog must keep writing exactly the markers the router reads.
        assertTrue(catalog.contains("CLOUD_PROVIDER_PREFIX: String = \"cloud:\""))
        assertTrue(catalog.contains("CLOUD_RUNTIME_PREFIX: String = \"CLOUD_\""))
    }

    @Test
    fun catalogProjectionAndRouterNeverCarryCredentials() {
        val catalog = sourceFile(
            "app/src/main/java/com/muyuchat/mca/presentation/AgentModelCatalogAdapter.kt"
        )
        val router = sourceFile(
            "app/src/main/java/com/muyuchat/mca/agent/inference/AgentRoutingModelPort.kt"
        )
        val projection = functionBody(catalog, "private fun buildChoices")

        // AgentModelBinding/AgentModelChoice have no credential field; the picker projection must
        // not reach into the credential-bearing store config either.
        assertFalse(projection.contains("apiKey"))
        assertFalse(projection.contains("Authorization"))
        assertFalse(router.contains("apiKey"))
        assertFalse(router.contains("Authorization"))
    }

    @Test
    fun agentInferencePackageHasNoDshImports() {
        val directory = File(
            projectRoot(),
            "app/src/main/java/com/muyuchat/mca/agent/inference"
        )
        val sources = directory.listFiles()
            ?.filter { it.isFile && it.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("agent/inference sources must exist", sources.size >= 7)

        sources.forEach { file ->
            val imports = file.readLines(Charsets.UTF_8).filter { it.startsWith("import ") }
            imports.forEach { line ->
                assertFalse(
                    file.name + " must not depend on the DSH runtime: " + line,
                    line.contains("com.muyuchat.mca.dsh") || line.contains("com.muyuchat.core.dsh")
                )
            }
        }
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
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
        error("Unterminated function body: $signature")
    }

    private fun sourceFile(name: String): String =
        File(projectRoot(), name).also {
            require(it.isFile) { "Missing source file: ${it.absolutePath}" }
        }.readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            if (File(root, "app/src/main/java/com/muyuchat/mca").isDirectory) return root
            directory = root.parentFile
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }
}
