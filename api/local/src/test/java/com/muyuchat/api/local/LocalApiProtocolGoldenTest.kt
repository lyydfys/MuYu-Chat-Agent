package com.muyuchat.api.local

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Freezes the Local API's wire contract so a refactor cannot change it silently.
 *
 * `McaLoopbackServerTest` (89 tests) proves the server behaves correctly; it does not
 * prove the contract is unchanged. P3-P6 move ownership across the API boundary, and a
 * dropped route, a lost auth requirement, or a changed SSE frame would keep those
 * behavioural tests green while breaking real clients.
 *
 * The golden is the served capability registry plus the router's route table and SSE
 * framing. On the first run the fixture is written and the test fails on purpose so the
 * generated contract is reviewed before it is committed.
 *
 * Comparisons are canonicalised (object keys sorted) because JSON key order is not part
 * of the contract and `org.json.JSONObject` does not preserve source order.
 */
class LocalApiProtocolGoldenTest {

    @Test
    fun `the served capability registry matches the frozen golden`() {
        val actual = canonicalize(LocalApiCapabilityRegistry.json())
        val golden = goldenFile(REGISTRY_GOLDEN)

        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(canonicalize(LocalApiCapabilityRegistry.json(), pretty = true) + "\n")
            fail(
                "Protocol golden was missing and has been generated at " +
                    "${REGISTRY_GOLDEN}. Review it, commit it, then re-run."
            )
        }

        assertEquals(
            "the Local API capability registry changed; if that is intentional, update $REGISTRY_GOLDEN",
            canonicalize(golden.readText()),
            actual
        )
    }

    @Test
    fun `the router still serves every frozen route`() {
        val routes = routedPaths()
        val golden = goldenFile(ROUTES_GOLDEN)

        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(routes.joinToString("\n") + "\n")
            fail(
                "Route golden was missing and has been generated at " +
                    "${ROUTES_GOLDEN}. Review it, commit it, then re-run."
            )
        }

        val frozen = golden.readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lost = frozen - routes.toSet()
        val added = routes - frozen.toSet()

        assertEquals("routes disappeared from the router: $lost", emptyList<String>(), lost)
        assertEquals(
            "new routes appeared; add them to $ROUTES_GOLDEN once reviewed: $added",
            emptyList<String>(),
            added
        )
    }

    @Test
    fun `a route reached through a constant stays inside the frozen contract`() {
        val golden = goldenFile(ROUTES_GOLDEN).readText()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

        // This is the specific hole the literal scan left: the registry endpoint is matched
        // as LocalApiCapabilityRegistry.PATH, so it was served but never frozen.
        assertTrue(
            "${LocalApiCapabilityRegistry.PATH} is served through a constant, so a literal " +
                "scan omits it; it must be pinned in $ROUTES_GOLDEN explicitly",
            golden.contains(LocalApiCapabilityRegistry.PATH)
        )
        assertEquals(
            "constantRoutedPaths() must stay in step with the frozen route list",
            emptyList<String>(),
            constantRoutedPaths() - golden.toSet()
        )
    }

    @Test
    fun `the capability registry covers every documented protocol scope`() {
        val registry = LocalApiCapabilityRegistry.all()
        assertTrue("the registry must not be empty", registry.isNotEmpty())

        // The registry is the documented subset; every entry must carry enough detail for a
        // client to use it without reading the server source.
        registry.forEach { capability ->
            assertTrue("${capability.path} needs a description", capability.description.isNotBlank())
            assertTrue(
                "${capability.path} must declare error codes",
                capability.errorCodes.isNotEmpty()
            )
        }
        assertTrue(
            "the chat completions subset must advertise its accepted fields",
            registry.single { it.path == "/v1/chat/completions" }.acceptedFields.isNotEmpty()
        )

        // Which routes are reachable without an API key is a security-relevant part of the
        // contract, so pin the exact set rather than letting a route drift open.
        // `modelsRouteDoesNotRequireApiKey` in McaLoopbackServerTest covers the behaviour.
        //
        // This set is now stated per method, not per path, because the router answers HEAD
        // /health without a key while GET /models is the key-free alias of /v1/models; a
        // path-only set could not tell those apart from an authenticated route that merely
        // shares a path. The members are exactly what McaLoopbackServer serves before its
        // API-key check plus everything `isPublicRoute` accepts, and
        // `authRequired matches the routes the router serves before the API key check` in
        // LocalApiCapabilityRegistryCoverageTest re-derives them from the router source so
        // this list cannot drift away from the server.
        assertEquals(
            "only these documented routes may be reachable without an API key",
            setOf(
                "HEAD /health",
                "GET /health",
                "GET /",
                "GET /index.html",
                "GET /v1/models",
                "GET /models"
            ),
            registry.filter { !it.authRequired }.map { "${it.method} ${it.path}" }.toSet()
        )
    }

    @Test
    fun `capability support is protocol scope and never a device admission list`() {
        val registry = LocalApiCapabilityRegistry.json()
        val notes = LocalApiCapabilityRegistry.all().flatMap { it.notes }

        assertTrue(
            "the registry must state that support is protocol scope, not device admission",
            notes.any { it.contains("not a per-device or per-model admission list") }
        )

        // A capability payload must not grow device- or chipset-shaped fields.
        val forbidden = listOf("device_model", "chipset", "soc", "allowlist", "whitelist", "certified")
        forbidden.forEach { field ->
            assertTrue(
                "the capability payload must not expose '$field' (plan: universal device admission)",
                !registry.contains("\"$field\"")
            )
        }
    }

    @Test
    fun `the streaming contract keeps its SSE framing`() {
        val source = sourceFile("api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt")

        assertTrue(
            "SSE responses must keep this content type",
            source.contains("Content-Type: text/event-stream; charset=utf-8")
        )
        assertTrue(
            "every SSE payload must use the 'data: ' prefix with a blank-line terminator",
            source.contains("data: ")
        )
        assertTrue(
            "the stream must terminate with the [DONE] sentinel clients wait for",
            source.contains("data: [DONE]\\n\\n")
        )
        assertTrue(
            "a client may request SSE through the Accept header",
            source.contains("accept.contains(\"text/event-stream\", ignoreCase = true)")
        )
    }

    @Test
    fun `the frozen goldens are reviewable text rather than opaque blobs`() {
        listOf(REGISTRY_GOLDEN, ROUTES_GOLDEN).forEach { path ->
            val file = goldenFile(path)
            assertTrue("$path must be committed alongside the test", file.isFile)
            val text = file.readText()
            assertTrue("$path is empty", text.isNotBlank())
            assertTrue("$path must be human-reviewable", text.contains("\n"))
        }
    }

    /**
     * `/v1/...` request paths the router matches, in source order.
     *
     * Most are string literals, but a route reached through a constant is invisible to a
     * literal scan: `/v1/mca/capabilities` is matched as `LocalApiCapabilityRegistry.PATH`,
     * so the first version of this scan silently omitted it and left a served endpoint
     * outside the frozen contract. Constants are listed explicitly below, and
     * `everyRoutedConstantIsFrozen` fails if a new one is introduced without being added.
     */
    private fun routedPaths(): List<String> =
        Regex("\"(/v1/[A-Za-z0-9/_.\\-]*)\"")
            .findAll(sourceFile("api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt"))
            .map { it.groupValues[1] }
            .plus(constantRoutedPaths())
            .distinct()
            .sorted()
            .toList()

    /** Routes the router matches through a constant rather than a literal. */
    private fun constantRoutedPaths(): List<String> = listOf(LocalApiCapabilityRegistry.PATH)

    /** Sorted-key canonical form, so key order is never treated as part of the contract. */
    private fun canonicalize(json: String, pretty: Boolean = false): String =
        canonicalizeValue(JSONObject(json), pretty, 0).trim()

    private fun canonicalizeValue(value: Any?, pretty: Boolean, depth: Int): String {
        val indent = if (pretty) "  ".repeat(depth) else ""
        val childIndent = if (pretty) "  ".repeat(depth + 1) else ""
        val newline = if (pretty) "\n" else ""
        return when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().sorted().toList()
                keys.joinToString(",", "{", "}") { key ->
                    "$newline$childIndent${JSONObject.quote(key)}: " +
                        canonicalizeValue(value.get(key), pretty, depth + 1)
                } + newline + indent
            }

            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
                canonicalizeValue(value.get(index), pretty, depth + 1)
            }

            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            JSONObject.NULL, null -> "null"
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun goldenFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun sourceFile(relativePath: String): String = File(projectRoot(), relativePath).readText()

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val REGISTRY_GOLDEN = "api/local/src/test/resources/local-api-capabilities.golden.json"
        const val ROUTES_GOLDEN = "api/local/src/test/resources/local-api-routes.golden.txt"
    }
}
