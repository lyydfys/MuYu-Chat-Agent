package com.muyuchat.api.local

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the served capability registry describes the router that actually exists.
 *
 * The registry is what a client reads to discover "which endpoints are there, does this one
 * need a key, does it stream". Before this test the registry listed 9 endpoints while the
 * router dispatched 29 method/path pairs, so a client could not see `/metrics`, the chat
 * page, the `/models` and `/completion` aliases, the whole tuning-job surface, or the image
 * textual-inversion inventory -- and had no way to learn they existed.
 *
 * `LocalApiProtocolGoldenTest` freezes the registry verbatim in a golden fixture, which is
 * the right guard against silent wire changes but the wrong guard against an omission:
 * regenerating the fixture also ratifies a registry that is missing entries. This test is
 * built to be immune to that. It re-derives the route table and the key-free route set from
 * the router source files themselves and compares them with the registry, and it additionally
 * pins a hand-written freeze of the original entries so that "make the coverage test pass by
 * deleting an entry" fails loudly.
 *
 * Everything is plain JUnit4 plus the source text and the published JSON, matching
 * `McaLoopbackServerWhitespaceContractTest`. No new dependency, no server, no mock.
 */
class LocalApiCapabilityRegistryCoverageTest {

    // ---------------------------------------------------------------------------------------
    // Coverage: the router and the registry must describe the same surface.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every route the server dispatches is discoverable in the capability registry`() {
        val routed = routedRoutes()
        val documented = registryRoutes()

        val missing = routed - documented
        assertEquals(
            "these routes are served by McaLoopbackServer but are invisible to a client reading " +
                "the capability registry; add a LocalApiEndpointCapability for each, or the " +
                "capability surface is lying by omission: ${missing.sortedBy { it.toString() }}",
            emptySet<Route>(),
            missing
        )
    }

    @Test
    fun `the registry does not advertise a route the server cannot dispatch`() {
        val routed = routedRoutes()
        val documented = registryRoutes()

        val invented = documented - routed
        assertEquals(
            "the registry advertises routes the router does not serve: " +
                "${invented.sortedBy { it.toString() }}. Capability support is protocol scope, " +
                "so an advertised route must exist.",
            emptySet<Route>(),
            invented
        )
    }

    @Test
    fun `the route scan actually reads the router rather than finding nothing`() {
        // A source scan that silently stops matching would make every assertion above pass
        // vacuously. Pin the extracted surface so a refactor that moves the router breaks this
        // test instead of quietly disabling the coverage guarantee.
        val routed = routedRoutes().map { it.toString() }.toSet()

        assertTrue(
            "the router scan found only ${routed.size} routes; it is no longer parsing the " +
                "dispatch block in McaLoopbackServer.handle()",
            routed.size >= 29
        )
        listOf(
            "GET /health",
            "HEAD /health",
            "GET /v1/models",
            "GET /models",
            "GET /v1/mca/capabilities",
            "GET /metrics",
            "POST /v1/chat/completions",
            "POST /chat/completions",
            "POST /v1/completions",
            "POST /completion",
            "POST /v1/generate/stop",
            "GET /v1/tuning/jobs/{jobId}",
            "POST /v1/tuning/jobs/{jobId}"
        ).forEach { expected ->
            assertTrue("the router scan lost $expected", expected in routed)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Registry self-health.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the registry lists the endpoint that serves it`() {
        val self = LocalApiCapabilityRegistry.all()
            .filter { it.path == LocalApiCapabilityRegistry.PATH }

        assertEquals(
            "${LocalApiCapabilityRegistry.PATH} is served by the router through " +
                "LocalApiCapabilityRegistry.PATH, so a client that discovered the registry from " +
                "the registry must find it there; exactly one entry must describe it",
            1,
            self.size
        )
        assertEquals("GET", self.single().method)
    }

    @Test
    fun `no original capability entry was edited or removed`() {
        val entries = LocalApiCapabilityRegistry.all()

        ORIGINAL_ENTRIES.forEach { expected ->
            val matching = entries.filter { it.method == expected.method && it.path == expected.path }
            assertEquals(
                "the original entry ${expected.method} ${expected.path} must remain in the " +
                    "registry exactly once; the capability surface is append-only because clients " +
                    "already read it",
                1,
                matching.size
            )
            assertEquals(
                "the original entry ${expected.method} ${expected.path} was modified",
                expected.toString(),
                FrozenEntry.of(matching.single()).toString()
            )
        }
    }

    @Test
    fun `the original entries were not quietly dropped to make the coverage test pass`() {
        val entries = LocalApiCapabilityRegistry.all()

        assertTrue(
            "the registry must still carry the ${ORIGINAL_ENTRIES.size} original entries; it now " +
                "has ${entries.size}",
            entries.size >= ORIGINAL_ENTRIES.size
        )
        val surviving = entries.map { Route(it.method, it.path) }.toSet()
        val lost = ORIGINAL_ENTRIES.map { Route(it.method, it.path) }.toSet() - surviving
        assertEquals(
            "the capability surface is append-only, but these original entries are gone: " +
                "${lost.sortedBy { it.toString() }}",
            emptySet<Route>(),
            lost
        )
    }

    @Test
    fun `no two entries describe the same method and path`() {
        val entries = LocalApiCapabilityRegistry.all()
        val duplicates = entries
            .groupBy { Route(it.method, it.path) }
            .filterValues { it.size > 1 }
            .keys

        assertEquals(
            "a duplicated method/path makes discovery ambiguous: " +
                "${duplicates.sortedBy { it.toString() }}",
            emptySet<Route>(),
            duplicates
        )
    }

    @Test
    fun `every entry stays usable without reading the server source`() {
        LocalApiCapabilityRegistry.all().forEach { capability ->
            val label = "${capability.method} ${capability.path}"
            assertTrue("$label needs a description", capability.description.isNotBlank())
            assertTrue("$label must declare error codes", capability.errorCodes.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------------------------------
    // Authentication truth: key-free means key-free in the router.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `authRequired matches the routes the router serves before the API key check`() {
        val keyFreeInRouter = keyFreeRoutes()
        val keyFreeInRegistry = LocalApiCapabilityRegistry.all()
            .filter { !it.authRequired }
            .map { Route(it.method, it.path) }
            .toSet()

        assertEquals(
            "a client must be able to rely on auth_required. These routes are key-free in the " +
                "router but are advertised as needing a key: " +
                "${(keyFreeInRouter - keyFreeInRegistry).sortedBy { it.toString() }}",
            emptySet<Route>(),
            keyFreeInRouter - keyFreeInRegistry
        )
        assertEquals(
            "these routes are advertised as key-free but the router requires a key for them; " +
                "claiming a route is open when it is not is the dangerous direction: " +
                "${(keyFreeInRegistry - keyFreeInRouter).sortedBy { it.toString() }}",
            emptySet<Route>(),
            keyFreeInRegistry - keyFreeInRouter
        )
        // The extraction below is only as good as its anchors, so pin what the router currently
        // says. If isPublicRoute is reshaped, this fails here rather than silently comparing
        // two sets that were both parsed as empty.
        assertEquals(
            "the router's key-free route set changed; re-check this extraction against " +
                "McaLoopbackServer.isPublicRoute and the pre-auth handlers in handle()",
            setOf(
                "HEAD /health",
                "GET /health",
                "GET /",
                "GET /index.html",
                "GET /v1/models",
                "GET /models"
            ),
            keyFreeInRouter.map { it.toString() }.toSet()
        )
    }

    @Test
    fun `the registry payload keeps its published shape`() {
        val root = JSONObject(LocalApiCapabilityRegistry.json())

        assertEquals(LocalApiCapabilityRegistry.SCHEMA, root.getString("schema"))
        assertEquals(1, root.getInt("version"))
        assertEquals("McaLoopbackServer", root.getString("generated_by"))
        assertEquals(LocalApiCapabilityRegistry.all().size, root.getJSONArray("endpoints").length())
        // Capability support is protocol scope, never a per-device admission list.
        listOf("device_model", "chipset", "soc", "allowlist", "whitelist", "certified").forEach { field ->
            assertTrue(
                "the capability payload must not expose '$field'",
                !LocalApiCapabilityRegistry.json().contains("\"$field\"")
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Router source extraction.
    // ---------------------------------------------------------------------------------------

    /** One method/path pair the router can dispatch. `{jobId}` marks a parameterised segment. */
    private data class Route(val method: String, val path: String) {
        override fun toString(): String = "$method $path"
    }

    /**
     * The text of `McaLoopbackServer.handle()`, split at the API-key check.
     *
     * The two anchors are the entry point and the final `else -> writeError(...)` of the
     * dispatch `when`; both are unique in the file, and [routingBlock] fails loudly if either
     * disappears rather than degrading to an empty scan.
     */
    private fun routingBlock(): String {
        val source = routerSource()
        val start = source.indexOf(HANDLE_ANCHOR)
        assertNotNull("McaLoopbackServer.handle() must still exist", start.takeIf { it >= 0 })
        val end = source.indexOf(ROUTE_FALLBACK_ANCHOR, start)
        assertNotNull(
            "the router's final 404 branch must still exist; the route scan anchors on it",
            end.takeIf { it > start }
        )
        return source.substring(start, end)
    }

    /** Routes matched before `handle()` consults the API key. */
    private fun preAuthBlock(): String =
        routingBlock().substringBefore(AUTH_CHECK_ANCHOR, missingDelimiterValue = "")

    /** Routes matched after the API key has been accepted. */
    private fun dispatchedBlock(): String =
        routingBlock().substringAfter(AUTH_CHECK_ANCHOR, missingDelimiterValue = "")

    private fun routerSource(): String = SERVER_SOURCE_FILES.joinToString("\n") { sourceFile(it) }

    /**
     * Every method/path the router can dispatch, read out of the dispatch block.
     *
     * `OPTIONS` is deliberately absent: it is a catch-all answered before routing, it matches
     * every path, and a capability entry per path would be meaningless.
     */
    private fun routedRoutes(): Set<Route> {
        val block = routingBlock()
        val routes = linkedSetOf<Route>()

        ROUTE_CONDITION.findAll(block).forEach { match ->
            routes += Route(match.groupValues[1], match.groupValues[2])
        }
        GROUPED_ROUTE_CONDITION.findAll(block).forEach { match ->
            val method = match.groupValues[1]
            PATH_LITERAL.findAll(match.groupValues[2]).forEach { literal ->
                routes += Route(method, literal.groupValues[1])
            }
        }
        SET_CONDITION.findAll(block).forEach { match ->
            pathSetConstant(match.groupValues[2]).forEach { path ->
                routes += Route(match.groupValues[1], path)
            }
        }
        PREFIX_CONDITION.findAll(block).forEach { match ->
            val prefix = stringConstant(match.groupValues[2])
            routes += Route(match.groupValues[1], "$prefix$PARAMETER_PLACEHOLDER")
        }
        CONSTANT_PATH_CONDITION.findAll(block).forEach { match ->
            routes += Route(match.groupValues[1], LocalApiCapabilityRegistry.PATH)
        }

        assertTrue(
            "the OPTIONS catch-all must stay ahead of the API key check, since the coverage " +
                "comparison deliberately excludes it",
            block.contains("method == \"OPTIONS\"")
        )
        return routes
    }

    /**
     * Routes reachable without an API key, as the router decides it: the pre-auth handlers in
     * `handle()` plus everything `isPublicRoute` accepts.
     */
    private fun keyFreeRoutes(): Set<Route> {
        val routes = linkedSetOf<Route>()

        // Served before the API-key check, so these are key-free by construction.
        GROUPED_ROUTE_CONDITION.findAll(preAuthBlock()).forEach { match ->
            val method = match.groupValues[1]
            PATH_LITERAL.findAll(match.groupValues[2]).forEach { literal ->
                routes += Route(method, literal.groupValues[1])
            }
        }
        ROUTE_CONDITION.findAll(preAuthBlock()).forEach { match ->
            routes += Route(match.groupValues[1], match.groupValues[2])
        }

        val predicate = publicRoutePredicate()
        ROUTE_CONDITION.findAll(predicate).forEach { match ->
            routes += Route(match.groupValues[1], match.groupValues[2])
        }
        GROUPED_ROUTE_CONDITION.findAll(predicate).forEach { match ->
            val method = match.groupValues[1]
            PATH_LITERAL.findAll(match.groupValues[2]).forEach { literal ->
                routes += Route(method, literal.groupValues[1])
            }
        }
        assertTrue(
            "isPublicRoute must keep its OPTIONS clause",
            predicate.contains("method == \"OPTIONS\"")
        )
        return routes
    }

    /** The body of `McaLoopbackServer.isPublicRoute`. */
    private fun publicRoutePredicate(): String {
        val source = routerSource()
        val start = source.indexOf(PUBLIC_ROUTE_ANCHOR)
        assertTrue("McaLoopbackServer.isPublicRoute must still exist", start >= 0)
        val end = source.indexOf(NEXT_MEMBER, start + PUBLIC_ROUTE_ANCHOR.length)
        assertTrue("isPublicRoute must still be followed by another member", end > start)
        return source.substring(start, end)
    }

    /** Resolves `private val NAME = setOf("a", "b", ...)` in the router to its literals. */
    private fun pathSetConstant(name: String): Set<String> {
        val match = Regex("""private val $name = setOf\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(routerSource())
        assertTrue(
            "the route constant $name is referenced by the router but is no longer a " +
                "setOf(\"...\") literal; the coverage scan cannot resolve it",
            match != null
        )
        val literals = STRING_LITERAL.findAll(requireNotNull(match).groupValues[1])
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("the route constant $name resolved to no paths", literals.isNotEmpty())
        return literals
    }

    /** Resolves a `private`/`internal` `const val NAME = "literal"` in the router. */
    private fun stringConstant(name: String): String {
        val match = Regex("""(?:private|internal) const val $name = "([^"]*)"""").find(routerSource())
        assertTrue(
            "the route prefix constant $name is referenced by the router but is no longer a " +
                "string literal; the coverage scan cannot resolve it",
            match != null
        )
        val value = requireNotNull(match).groupValues[1]
        assertTrue("the route prefix constant $name resolved to a blank value", value.isNotBlank())
        return value
    }

    private fun registryRoutes(): Set<Route> =
        LocalApiCapabilityRegistry.all().map { Route(it.method, it.path) }.toSet()

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }

    // ---------------------------------------------------------------------------------------
    // Freeze of the original entries.
    // ---------------------------------------------------------------------------------------

    /**
     * A hand-written copy of the entries that existed before the missing routes were added.
     *
     * `notes` is frozen only by length: the wording is already frozen verbatim by
     * `LocalApiProtocolGoldenTest`'s registry golden, while this freeze exists to survive that
     * fixture being regenerated. `acceptedFields` for POST /v1/responses reuses
     * `LocalResponsesCompat.acceptedFields`, the same constant the production entry uses.
     */
    private data class FrozenEntry(
        val method: String,
        val path: String,
        val support: String,
        val authRequired: Boolean,
        val streaming: Boolean,
        val description: String,
        val acceptedFields: List<String>,
        val notesCount: Int,
        val errorCodes: List<String>
    ) {
        override fun toString(): String = listOf(
            "method=$method",
            "path=$path",
            "support=$support",
            "authRequired=$authRequired",
            "streaming=$streaming",
            "description=$description",
            "acceptedFields=$acceptedFields",
            "notes.size=$notesCount",
            "errorCodes=$errorCodes"
        ).joinToString("\n")

        companion object {
            fun of(capability: LocalApiEndpointCapability): FrozenEntry = FrozenEntry(
                method = capability.method,
                path = capability.path,
                support = capability.support.name.lowercase(),
                authRequired = capability.authRequired,
                streaming = capability.streaming,
                description = capability.description,
                acceptedFields = capability.acceptedFields,
                notesCount = capability.notes.size,
                errorCodes = capability.errorCodes
            )
        }
    }

    private companion object {
        // T2B8/R7-B3: the router now lives in the shell + six same-package successor files;
        // the scan reads their union so every dispatch/list assertion still sees it whole.
        val SERVER_SOURCE_FILES = listOf(
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerHttpTransport.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerChatSse.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerResponsesSse.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerImageGeneration.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerTuningControl.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerProtocolCodec.kt"
        )
        const val PARAMETER_PLACEHOLDER = "{jobId}"

        const val HANDLE_ANCHOR = "private suspend fun handle(socket: Socket) {"
        const val ROUTE_FALLBACK_ANCHOR = "else -> writeError(client,"
        const val AUTH_CHECK_ANCHOR = "if (!isPublicRoute(method, path) && !isAuthorized(headers))"
        // T2B8/R7-B3: readHttpRequest (transport successor) shares this predicate, so the
        // declaration widened from `private ` to `internal `; the anchor follows the code.
        const val PUBLIC_ROUTE_ANCHOR =
            "internal fun isPublicRoute(method: String, path: String): Boolean ="
        const val NEXT_MEMBER = "\n    private fun "

        const val METHODS = "GET|POST|HEAD|PUT|PATCH|DELETE"

        /** `method == "GET" && path == "/metrics"`. */
        val ROUTE_CONDITION = Regex("""method == "($METHODS)"\s*&&\s*path == "([^"]+)"""")

        /** `method == "GET" && (path == "/v1/models" || path == "/models")`. */
        val GROUPED_ROUTE_CONDITION = Regex("""method == "($METHODS)"\s*&&\s*\(([^)]*)\)""")

        /** `method == "GET" && path in IMAGE_TEXTUAL_INVERSION_PATHS`. */
        val SET_CONDITION = Regex("""method == "($METHODS)"\s*&&\s*path in ([A-Z_]+)""")

        /** `method == "GET" && path.startsWith(TUNING_JOB_PATH_PREFIX)`. */
        val PREFIX_CONDITION =
            Regex("""method == "($METHODS)"\s*&&\s*path\.startsWith\(([A-Z_]+)\)""")

        /** `method == "GET" && path == LocalApiCapabilityRegistry.PATH`. */
        val CONSTANT_PATH_CONDITION = Regex(
            """method == "($METHODS)"\s*&&\s*path == LocalApiCapabilityRegistry\.PATH"""
        )

        val PATH_LITERAL = Regex("""path == "([^"]+)"""")
        val STRING_LITERAL = Regex("\"([^\"]*)\"")

        val ORIGINAL_ENTRIES = listOf(
            FrozenEntry(
                method = "GET",
                path = "/health",
                support = "supported",
                authRequired = false,
                streaming = false,
                description = "Process health check.",
                acceptedFields = emptyList(),
                notesCount = 0,
                errorCodes = listOf("unauthorized", "not_found")
            ),
            FrozenEntry(
                method = "GET",
                path = "/v1/models",
                support = "supported",
                authRequired = false,
                streaming = false,
                description = "Loaded/catalog models and runtime-specific readiness.",
                acceptedFields = emptyList(),
                notesCount = 0,
                errorCodes = listOf("unauthorized", "runtime_unavailable")
            ),
            FrozenEntry(
                method = "POST",
                path = "/v1/chat/completions",
                support = "partial",
                authRequired = true,
                streaming = true,
                description = "OpenAI-compatible local chat subset.",
                acceptedFields = listOf(
                    "model", "messages", "prompt", "input", "stream", "temperature", "top_p", "top_k",
                    "min_p", "repeat_penalty", "repetition_penalty", "presence_penalty",
                    "frequency_penalty", "max_tokens", "max_completion_tokens", "n_predict", "stop",
                    "stop_words", "seed", "system_prompt", "reasoning_mode", "thinking_mode",
                    "enable_thinking", "show_reasoning", "hide_reasoning"
                ),
                notesCount = 3,
                errorCodes = listOf(
                    "invalid_request", "parameter_scope_conflict", "invalid_model", "model_not_found",
                    "runtime_busy", "context_length_exceeded", "generation_failed",
                    "generation_cancelled", "unauthorized"
                )
            ),
            FrozenEntry(
                method = "POST",
                path = "/v1/responses",
                support = "partial",
                authRequired = true,
                streaming = true,
                description = "OpenAI Responses-compatible text/message subset.",
                acceptedFields = LocalResponsesCompat.acceptedFields,
                notesCount = 4,
                errorCodes = listOf(
                    "invalid_request", "unsupported_parameter", "parameter_scope_conflict",
                    "model_not_found", "runtime_busy", "context_length_exceeded",
                    "generation_failed", "generation_cancelled", "unauthorized"
                )
            ),
            FrozenEntry(
                method = "POST",
                path = "/v1/images/generations",
                support = "partial",
                authRequired = true,
                streaming = false,
                description = "Authenticated local image-generation subset.",
                acceptedFields = listOf(
                    "model", "prompt", "negative_prompt", "sampler", "size", "n", "response_format",
                    "steps", "cfg_scale", "seed", "task_mode", "input_image", "source_image",
                    "mask_image", "mask", "control_image", "strength", "control_strength", "clip_skip",
                    "loras", "vae_tiling", "textual_inversion_ids", "ultrafix"
                ),
                notesCount = 4,
                errorCodes = listOf(
                    "invalid_json", "unsupported_sampler", "unsupported_image_count",
                    "unsupported_response_format", "unsupported_preview", "image_model_not_ready",
                    "image_generation_busy", "image_worker_unavailable",
                    "image_native_execution_failed", "unauthorized"
                )
            ),
            FrozenEntry(
                method = "POST",
                path = "/v1/generate/stop",
                support = "supported",
                authRequired = true,
                streaming = false,
                description = "Stops the current generation or one matching request_id.",
                acceptedFields = listOf("request_id"),
                notesCount = 2,
                errorCodes = listOf(
                    "unauthorized", "invalid_request", "invalid_request_id",
                    "unsupported_parameter", "generation_not_active", "stop_failed"
                )
            ),
            FrozenEntry(
                method = "GET",
                path = "/v1/mca/capabilities",
                support = "supported",
                authRequired = true,
                streaming = false,
                description = "This endpoint: protocol capability registry for the authenticated Local API.",
                acceptedFields = emptyList(),
                notesCount = 1,
                errorCodes = listOf("unauthorized", "not_found")
            ),
            FrozenEntry(
                method = "GET",
                path = "/v1/mca/runtime",
                support = "supported",
                authRequired = true,
                streaming = false,
                description = "Current worker/coordinator lifecycle state.",
                acceptedFields = emptyList(),
                notesCount = 0,
                errorCodes = listOf("unauthorized", "runtime_unavailable")
            ),
            FrozenEntry(
                method = "GET",
                path = "/v1/mca/profile",
                support = "supported",
                authRequired = true,
                streaming = false,
                description = "Redacted active runtime profile and parameter evidence.",
                acceptedFields = emptyList(),
                notesCount = 0,
                errorCodes = listOf("unauthorized", "runtime_unavailable")
            )
        )
    }
}
