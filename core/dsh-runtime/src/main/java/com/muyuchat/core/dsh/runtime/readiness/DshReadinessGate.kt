package com.muyuchat.core.dsh.runtime.readiness

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage

/**
 * Official readiness gate.
 *
 * A listening port is not readiness. The host is READY only when the official surface is
 * actually serving for this generation:
 *
 * 1. the launch-token URL performs the official 303 exchange and sets the HttpOnly
 *    SameSite cookie for the loopback authority;
 * 2. the cookie-authenticated index returns the official boot-injected HTML containing
 *    `__DSH_BOOT_READY__` and the client-modules preload — this means the Loader tree settled
 *    and its client plugin resources are registered;
 * 3. the bootstrap client plugin asset is served as JavaScript;
 * 4. a second index fetch after a short delay still succeeds (the server is stable, not a
 *    one-shot during boot);
 * 5. an unauthenticated index request is refused (the auth fence is active).
 *
 * Every verified boundary is reported through [awaitReady]'s `onStage` callback as a typed
 * [DshStartupStage], so callers can observe "port answered" vs "cookie-authenticated page" vs
 * "client plugins loaded" instead of a single opaque STARTING phase. The gate only reports
 * stages it actually observed and never reports [DshStartupStage.FIRST_FRAME_RENDERED]: a frame
 * is not visible to this process and must come from the WebView owner.
 *
 * Every failure names a concrete contract failure code; retries are bounded by the configured
 * readiness timeout and abort promptly when the Node process exits.
 */
class DshReadinessGate(
    private val probe: DshHttpProbe,
    private val stabilityDelayMillis: Long = 350L,
    private val readinessTimeoutMillis: Long = 60_000L,
    private val retryDelayMillis: Long = 250L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) }
) {
    sealed interface Outcome {
        data class Ready(
            val origin: String,
            val cookie: String,
            val indexBytes: Int,
            val pluginAsset: String
        ) : Outcome

        data class Failed(
            val code: DshFailureCode,
            val message: String,
            val diagnostics: String?
        ) : Outcome
    }

    private data class StepFailure(
        val code: DshFailureCode,
        val message: String,
        val detail: String
    )

    /**
     * Waits for the official surface and reports every verified startup boundary as a typed
     * [DshStartupStage] through [onStage]. A retry may report an already observed stage again;
     * [com.muyuchat.core.dsh.runtime.startup.DshStartupRecorder] deduplicates it. [shouldAbort]
     * is the last parameter so existing trailing-lambda abort callbacks keep binding to it.
     */
    fun awaitReady(
        entry: DshLaunchEntry,
        onStage: (DshStartupStage) -> Unit = {},
        shouldAbort: () -> Boolean = { false }
    ): Outcome {
        val deadline = clock() + readinessTimeoutMillis
        var lastFailure = StepFailure(
            DshFailureCode.LOADER_NOT_READY,
            "the official DSH surface did not become ready",
            null.orEmpty()
        )
        while (clock() < deadline) {
            if (shouldAbort()) {
                return Outcome.Failed(
                    DshFailureCode.NODE_START_FAILED,
                    "the DSH host process exited before readiness",
                    lastFailure.detail
                )
            }
            when (val result = probeOnce(entry, onStage)) {
                is Outcome.Ready -> return result
                is Outcome.Failed -> {
                    lastFailure = StepFailure(result.code, result.message, result.diagnostics.orEmpty())
                    if (result.code == DshFailureCode.AUTH_ENTRY_MISSING) return result
                }
            }
            sleeper(retryDelayMillis)
        }
        return Outcome.Failed(
            lastFailure.code,
            lastFailure.message,
            lastFailure.detail.takeIf { it.isNotBlank() }
        )
    }

    private fun probeOnce(
        entry: DshLaunchEntry,
        onStage: (DshStartupStage) -> Unit
    ): Outcome {
        val exchange = probe.get(entry.authenticatedUrl, null)
        if (exchange.failure != null) {
            return failure(DshFailureCode.LOADER_NOT_READY, "launch-token exchange failed: ${exchange.failure}")
        }
        // Any HTTP status proves a listener; it does not prove authentication, loader readiness
        // or a rendered page. Those are separate stages below.
        onStage(DshStartupStage.PORT_LISTENING)
        if (exchange.code != 303) {
            return failure(
                DshFailureCode.LOADER_NOT_READY,
                "launch-token exchange returned HTTP ${exchange.code}",
                "expected 303 with Location ./ and an HttpOnly session cookie"
            )
        }
        val cookie = DshSetCookie.firstCookie(exchange.setCookie)
            ?: return failure(DshFailureCode.LOADER_NOT_READY, "launch-token exchange set no dsh-auth cookie")
        if (!DshSetCookie.hasAttribute(exchange.setCookie, "HttpOnly")) {
            return failure(DshFailureCode.LOADER_NOT_READY, "launch-token cookie is not HttpOnly")
        }
        val location = exchange.header("Location")
        if (location == null || (location != "./" && !location.endsWith("/"))) {
            return failure(DshFailureCode.LOADER_NOT_READY, "launch-token exchange redirected to a non-clean URL")
        }

        val index = probe.get("${entry.origin}/", cookie)
        if (index.failure != null) {
            return failure(DshFailureCode.LOADER_NOT_READY, "authenticated index fetch failed: ${index.failure}")
        }
        if (index.code != 200) {
            return failure(
                DshFailureCode.LOADER_NOT_READY,
                "authenticated index returned HTTP ${index.code}",
                "cookie=${if (cookie.isBlank()) "absent" else "present"}"
            )
        }
        if (index.contentType?.contains("text/html", ignoreCase = true) != true) {
            return failure(
                DshFailureCode.LOADER_NOT_READY,
                "authenticated index is not HTML (${index.contentType})"
            )
        }
        // The cookie was accepted for a real page request; the boot marker and the second
        // authenticated request still have to prove the official loader surface.
        onStage(DshStartupStage.HTTP_AUTHENTICATED)
        if (!index.body.contains(BOOT_READY_MARKER)) {
            return failure(
                DshFailureCode.LOADER_NOT_READY,
                "index lacks the official boot-readiness marker",
                "expected $BOOT_READY_MARKER in the injected index HTML"
            )
        }
        val bootstrap = BOOTSTRAP_URL_PATTERN.find(index.body)?.value
            ?: BOOTSTRAP_PRELOAD.takeIf { index.body.contains(it) }
            ?: return failure(
                DshFailureCode.LOADER_NOT_READY,
                "index lacks the official client-modules bootstrap batch",
                "expected a /plugins/??...dsh-client-modules/client.js&rev=... URL or $BOOTSTRAP_PRELOAD"
            )
        // The served form is an HTML attribute; attribute escaping turns `&` into `&amp;`.
        // The route itself needs the real query string.
        val bootstrapPath = normalizeBootstrapPath(bootstrap)
        val plugin = probe.get("${entry.origin}$bootstrapPath", cookie)
        val pluginType = plugin.contentType?.lowercase().orEmpty()
        if (plugin.failure != null ||
            plugin.code != 200 ||
            plugin.body.length < MIN_CLIENT_ASSET_BYTES ||
            (!pluginType.contains("javascript") && !pluginType.contains("ecmascript"))
        ) {
            return failure(
                DshFailureCode.LOADER_NOT_READY,
                "bootstrap client plugin asset is not served",
                "path=$bootstrapPath status=${plugin.code} bytes=${plugin.body.length} " +
                    "type=${plugin.contentType} failure=${plugin.failure}"
            )
        }
        // Boot marker and client-modules asset are both served: the loader settled and its
        // client plugin resources are registered. Stability and the auth fence below still gate
        // DshRuntimePhase.READY; a rendered frame is never inferred here.
        onStage(DshStartupStage.PLUGINS_LOADED)

        sleeper(stabilityDelayMillis)
        val second = probe.get("${entry.origin}/", cookie)
        if (second.code != 200 || !second.body.contains(BOOT_READY_MARKER)) {
            return failure(DshFailureCode.LOADER_NOT_READY, "second index fetch was not stable (HTTP ${second.code})")
        }

        val unauth = probe.get("${entry.origin}/", null)
        if (unauth.code != 401) {
            return failure(
                DshFailureCode.LOADER_NOT_READY,
                "unauthenticated index was not refused (HTTP ${unauth.code})",
                "the official browser-auth fence must answer 401 without a session cookie"
            )
        }
        return Outcome.Ready(
            origin = entry.origin,
            cookie = cookie,
            indexBytes = index.body.length,
            pluginAsset = bootstrapPath
        )
    }

    /**
     * The official client-modules plugin injects its bootstrap batch as the combo route
     * `./plugins/??<id>/client.js,...&rev=<hash>` (HTML-attribute escaped). Accept both the combo
     * route and, for forward compatibility, the single-plugin route used by earlier builds.
     */
    private fun normalizeBootstrapPath(raw: String): String {
        val unescaped = raw.replace("&amp;", "&")
        return when {
            unescaped.startsWith("./") -> unescaped.substring(1)
            unescaped.startsWith("/") -> unescaped
            else -> "/$unescaped"
        }
    }

    private fun failure(code: DshFailureCode, message: String, detail: String? = null): Outcome =
        Outcome.Failed(code, message, detail)

    companion object {
        /** Official index marker resolving the boot-readiness deferred (webserver/injections.ts). */
        const val BOOT_READY_MARKER = "__DSH_BOOT_READY__"

        /** Official client-modules bootstrap plugin id. */
        const val BOOTSTRAP_PLUGIN_ID = "@deepseek-ai/dsh-client-modules"

        /** Preload the injected index must carry once the client plugin loader is registered. */
        const val BOOTSTRAP_PRELOAD = "/plugins/$BOOTSTRAP_PLUGIN_ID/client.js"

        const val BOOTSTRAP_ASSET = "/plugins/$BOOTSTRAP_PLUGIN_ID/client.js"

        /**
         * The served bootstrap batch is a combo route. The official renderer strips the leading
         * slash for document-relative browser routes, so the index attribute is normally
         * `plugins/??@deepseek-ai/dsh-client-modules/client.js&rev=...` (no leading slash, and
         * possibly `./plugins/...` or an absolute `/plugins/...` on other profiles). Match all
         * three forms before HTML unescaping; [normalizeBootstrapPath] turns them into a request
         * path and reverses `&amp;` to `&`.
         */
        private val BOOTSTRAP_URL_PATTERN = Regex(
            """(?:\./|/)?plugins/\?\?[^"'\s<>]*?dsh-client-modules/client\.js[^"'\s<>]*"""
        )

        const val MIN_CLIENT_ASSET_BYTES = 512
    }
}
