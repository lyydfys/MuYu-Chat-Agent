package com.muyuchat.mca.composition

import android.util.Log
import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.api.dsh.gateway.DshGatewayLogLevel
import com.muyuchat.api.dsh.gateway.DshGatewayLogger
import com.muyuchat.api.dsh.gateway.DshGatewayServer
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshGatewayEndpointProvider
import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.runtime.state.DshInFlightChannel
import com.muyuchat.core.dsh.runtime.state.DshInFlightEvidence
import com.muyuchat.core.dsh.runtime.state.DshInFlightObservation
import com.muyuchat.core.dsh.runtime.state.DshInFlightSnapshot
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Typed, log-safe app-side signal of the model catalog one gateway generation advertises.
 *
 * [revision] is the digest of the model list (ids plus the capability facts the DSH overlay
 * publishes), so two states with the same revision advertise the same catalog. [generation] is the
 * gateway epoch that produced the snapshot; [modelCount] is its size, which makes an empty catalog
 * distinguishable from "no catalog at all" ([None]) without inventing a model.
 *
 * The type carries no token, URL or model content beyond the digest and a count.
 */
internal data class DshCatalogState(
    val revision: String,
    val modelCount: Int,
    val generation: Long
) {
    /** True when a gateway generation actually published this signal. */
    val active: Boolean get() = revision.isNotEmpty()

    /** True when at least one model is advertised (the D20 "model bound" projection). */
    val bound: Boolean get() = modelCount > 0

    companion object {
        /** No gateway generation advertises a catalog (never started, or stopped). */
        val None = DshCatalogState(revision = "", modelCount = 0, generation = 0L)
    }
}

/**
 * Typed answer to "did the catalog of the running generation really change?".
 *
 * The four answers stay distinct on purpose; nothing is merged into a boolean:
 *  - [NoGeneration]: nothing is listening, so a model change is reconciled by the next DSH start;
 *  - [Unchanged]: the generation already advertises exactly this catalog;
 *  - [Changed]: the running host's overlay is stale; only now is a binding refresh meaningful;
 *  - [Unreadable]: the catalog could not be read; [Unreadable.retained] is still the last good
 *    signal, because a failed read must never look like an empty (or changed) catalog.
 */
internal sealed interface DshCatalogChange {
    data object NoGeneration : DshCatalogChange

    data class Unchanged(val state: DshCatalogState) : DshCatalogChange

    data class Changed(val previous: DshCatalogState, val current: DshCatalogState) : DshCatalogChange

    data class Unreadable(val retained: DshCatalogState, val detail: String) : DshCatalogChange
}

/**
 * Composition-level lifecycle owner of the internal DSH model gateway.
 *
 * The gateway is a loopback-only protocol adapter owned by the MCA main process; the DSH Node
 * host runs in the separate `:dsh` process and calls this endpoint. One [DshGatewayServer] is
 * created per DSH host generation so its bearer token, call-id epoch and model catalog snapshot
 * rotate with the host generation. The gateway is started on demand by the runtime owner through
 * [endpointProvider] and stopped by [stop] when that generation is gone.
 *
 * Model refresh: the DSH host applies its provider overlay once, at process start, so a changed
 * model list has to be visible *before* the host decides whether to reload. [endpointProvider]
 * therefore observes the live catalog of the running generation and reports it as the endpoint's
 * `catalogRevision`. This is a change signal only: the loopback port and bearer token of the
 * running generation stay valid, so a model-list change never breaks an in-flight model call by
 * itself. The runtime owner turns a changed revision into a confirmed host reload.
 *
 * The signal itself is typed and single-writer: [observeCatalogChange] is the one place that
 * re-reads the catalog of the running generation and publishes [catalogState]. It never starts,
 * stops, rebinds or reloads anything, never rotates the port/token, and never turns a failed read
 * into an empty catalog. [endpointOrNull] (the attach path) and the JVM tests both consume it, so
 * "did the catalog really change?" has exactly one answer.
 *
 * No engine, facade, cloud store or credential is created here; [backend] is the app-owned
 * adapter over the single existing inference owner.
 */
internal class DshGatewayAssembly(
    private val backend: DshGatewayBackend,
    private val logger: DshGatewayLogger = AndroidDshGatewayLogger,
    /**
     * Second evidence channel: tool executions dispatched through the app-local capability
     * bridge. The bridge owner supplies its own live ledger count. `null` means the channel is not
     * wired in this build; the observation then reports it as *uncovered* instead of inventing a
     * zero. It must never return [DshInFlightEvidence.Observed] with a guessed value.
     */
    private val toolEvidence: () -> DshInFlightEvidence? = { null }
) {
    private val mutex = Mutex()

    @Volatile
    private var server: DshGatewayServer? = null

    private var generation = 0L

    /**
     * Last observed model-list signal of the running generation. It is the app-side change signal
     * only (never the server's own `/v1/models` revision), so it stays stable until the model list
     * actually changes. [DshCatalogState.None] means "no gateway generation advertises a catalog".
     *
     * It is written only by [publishCatalogState] (start / typed probe) and cleared only by [stop],
     * so the revision and the model count can never drift apart.
     */
    @Volatile
    private var catalog: DshCatalogState = DshCatalogState.None

    /** True only while the loopback gateway server is actually listening. */
    val isRunning: Boolean
        get() = server?.isRunning == true

    /**
     * Typed app-side catalog signal of the running generation: revision, model count and the
     * generation it belongs to. Read-only; a changed revision means the running host's provider
     * overlay (seeded once per generation) no longer matches what the gateway advertises.
     */
    val catalogState: DshCatalogState
        get() = catalog

    /**
     * Size of the MCA catalog snapshot the current gateway generation was built from. The D20
     * console projects "model bound" only when this is non-zero; an empty catalog (account-only
     * mode) truthfully reports no MCA model binding.
     */
    val catalogSize: Int
        get() = catalog.modelCount

    /** Provider consumed by [com.muyuchat.core.dsh.runtime.DshRuntimeOwner] before a start. */
    val endpointProvider: DshGatewayEndpointProvider = DshGatewayEndpointProvider { endpointOrNull() }

    /**
     * In-flight observation handed to the runtime owner before every binding apply.
     *
     * It is the real [observeActivity] view: the model channel is asked through the gateway's own
     * authenticated `/healthz`, and `null` means the channel could not be observed. The owner
     * reports that as `inFlightUnobservable` and defers an unforced reload instead of assuming
     * zero. The capability-bridge tool channel and the official account provider are reported as
     * uncovered when this process cannot see them.
     *
     * Wiring: `DshModeAssembly` passes this to `DshRuntimeServiceClient(inFlight = ...)`.
     */
    val inFlightObservation: DshInFlightObservation = DshInFlightObservation {
        observeActivity().observedCalls
    }

    /**
     * Returns the current generation endpoint, starting the gateway on demand. A repeated call
     * while the gateway is running returns the same coordinates (same port, same token) with the
     * freshly observed catalog revision, so a re-attach to a READY host never rotates credentials
     * while a model-list change is still detectable. An empty model catalog is valid: the official
     * DSH frontend remains available before a model is loaded. Returns null only when the catalog
     * cannot be read or the gateway cannot be started.
     */
    private suspend fun endpointOrNull(): DshGatewayEndpoint? {
        server?.takeIf { it.isRunning }?.let { running ->
            val endpoint = running.currentEndpoint() ?: return null
            // Same port/token for one generation: a model-list change is a revision change only.
            val revision = advertisedRevision(observeCatalogChange()) ?: return null
            // A stop may have landed while the catalog was read; never hand out a dead endpoint.
            if (server !== running || !running.isRunning) return null
            return endpoint.copy(catalogRevision = revision)
        }
        return mutex.withLock { startGenerationLocked() }
    }

    /** Starts a fresh gateway generation and publishes its catalog signal. */
    private suspend fun startGenerationLocked(): DshGatewayEndpoint? {
        val models = runCatching { backend.catalog() }
            .onFailure { Log.w(TAG, "DSH gateway catalog read failed: ${it.javaClass.simpleName}") }
            .getOrElse { return null }
        generation += 1
        val created = DshGatewayServer(
            backend = backend,
            runtimeEpoch = generation,
            logger = logger
        )
        val endpoint = runCatching { created.start() }.getOrElse { failure ->
            Log.w(TAG, "DSH gateway start failed: ${failure.javaClass.simpleName}")
            return null
        }
        server = created
        // Published only for a generation that actually listens: a failed start advertises nothing.
        val state = publishCatalogState(models, generation)
        Log.i(
            TAG,
            "DSH gateway READY generation=$generation models=${state.modelCount} " +
                "revision=${state.revision}"
        )
        return endpoint.copy(catalogRevision = state.revision)
    }

    /**
     * Re-reads the live catalog of the running generation and reports the typed change.
     *
     * This is the app-side catalog change signal and it does exactly one thing: it publishes
     * [catalogState]. It never starts, stops, rebinds or reloads the gateway and never rotates the
     * port or bearer token, so a model-list change can never cut an in-flight model call by itself.
     * A failed read keeps the previous signal ([DshCatalogChange.Unreadable]) — a transient catalog
     * failure is neither a model change nor a fake empty catalog.
     */
    internal suspend fun observeCatalogChange(): DshCatalogChange {
        val running = server?.takeIf { it.isRunning } ?: return DshCatalogChange.NoGeneration
        val models = runCatching { backend.catalog() }.getOrElse { failure ->
            Log.w(TAG, "DSH gateway catalog refresh failed: ${failure.javaClass.simpleName}")
            return DshCatalogChange.Unreadable(catalog, failure.javaClass.simpleName)
        }
        return mutex.withLock {
            // A stop or a new generation may have landed while the catalog was being read; the
            // observation then belongs to a generation that no longer serves, so nothing is
            // published for it.
            if (server !== running || !running.isRunning) {
                return@withLock DshCatalogChange.NoGeneration
            }
            val previous = catalog
            val current = publishCatalogState(models, generation)
            if (current.revision == previous.revision) {
                DshCatalogChange.Unchanged(current)
            } else {
                Log.i(
                    TAG,
                    "DSH gateway model catalog changed models=${current.modelCount} " +
                        "revision=${current.revision}"
                )
                DshCatalogChange.Changed(previous, current)
            }
        }
    }

    /** The revision a running generation must advertise for [change]; null when it has none. */
    private fun advertisedRevision(change: DshCatalogChange): String? = when (change) {
        is DshCatalogChange.Changed -> change.current.revision
        is DshCatalogChange.Unchanged -> change.state.revision
        // A failed read keeps the previous signal: the caller never sees a fake empty catalog.
        is DshCatalogChange.Unreadable -> change.retained.revision.takeIf { it.isNotEmpty() }
        DshCatalogChange.NoGeneration -> null
    }

    /** The ONE writer of the app-side catalog signal. */
    private fun publishCatalogState(
        models: List<DshGatewayModel>,
        forGeneration: Long
    ): DshCatalogState {
        val state = DshCatalogState(
            revision = revisionOf(models),
            modelCount = models.size,
            generation = forGeneration
        )
        catalog = state
        return state
    }

    /**
     * Tri-state in-flight observation of the current generation, for the runtime owner's reload
     * policy and for diagnostics.
     *
     * Three states are kept distinct on purpose:
     *  - [DshInFlightEvidence.Observed] with a real count, including a real `0`;
     *  - [DshInFlightEvidence.Unobservable] when the channel exists but cannot be asked;
     *  - channels listed in [DshInFlightSnapshot.uncovered] that this process has no view of.
     *
     * A DSH agent turn that is still streaming must not be cut by a model-list or token change,
     * so the owner defers the reload while observed work exists and also while the model channel
     * cannot be observed at all. The account provider is deliberately reported as uncovered: MCA
     * has no request-level view of the Node host's official-provider turns, and saying so is
     * better than adding a fictional zero.
     */
    suspend fun observeActivity(): DshInFlightSnapshot {
        val evidence = ArrayList<DshInFlightEvidence>(2)
        evidence += observeModelCalls()
        val tool = runCatching { toolEvidence() }
            .onFailure { Log.w(TAG, "DSH tool-execution probe failed (${it.javaClass.simpleName})") }
            .getOrNull()
        if (tool != null) evidence += tool
        return DshInFlightSnapshot(
            evidence = evidence,
            uncovered = buildList {
                if (tool == null) add(DshInFlightChannel.TOOL_EXECUTION)
                add(DshInFlightChannel.ACCOUNT_DIRECT)
            }
        )
    }

    /**
     * Number of model calls this gateway is currently serving, or null when it cannot be observed.
     *
     * The value comes from the gateway's own authenticated `/healthz` surface, i.e. the same
     * registry that admits and cancels calls. Nothing is inferred: a stopped gateway, a bind or
     * read failure all return null.
     */
    suspend fun activeCalls(): Int? =
        (observeModelCalls() as? DshInFlightEvidence.Observed)?.calls

    /** The model channel alone, with the reason kept when it cannot be observed. */
    private suspend fun observeModelCalls(): DshInFlightEvidence {
        val endpoint = server?.takeIf { it.isRunning }?.currentEndpoint()
            ?: return DshInFlightEvidence.Unobservable(
                DshInFlightChannel.MODEL_GATEWAY,
                "no gateway generation is listening"
            )
        val port = runCatching { URI(endpoint.baseUrl).port }.getOrNull()
        if (port == null || port !in 1..65535) {
            return DshInFlightEvidence.Unobservable(
                DshInFlightChannel.MODEL_GATEWAY,
                "the gateway endpoint has no usable port"
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                val connection = URI("http://$LOOPBACK_HOST:$port/healthz").toURL()
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = HEALTH_TIMEOUT_MILLIS
                    connection.readTimeout = HEALTH_TIMEOUT_MILLIS
                    connection.setRequestProperty("Authorization", "Bearer ${endpoint.bearerToken}")
                    if (connection.responseCode != 200) {
                        return@withContext DshInFlightEvidence.Unobservable(
                            DshInFlightChannel.MODEL_GATEWAY,
                            "healthz returned ${connection.responseCode}"
                        )
                    }
                    val line = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.readLine()?.take(MAX_HEALTH_CHARS)
                    } ?: return@withContext DshInFlightEvidence.Unobservable(
                        DshInFlightChannel.MODEL_GATEWAY,
                        "healthz returned no body"
                    )
                    val calls = JSONObject(line).optInt("active_calls", -1)
                    if (calls < 0) {
                        DshInFlightEvidence.Unobservable(
                            DshInFlightChannel.MODEL_GATEWAY,
                            "healthz has no active_calls field"
                        )
                    } else {
                        DshInFlightEvidence.Observed(DshInFlightChannel.MODEL_GATEWAY, calls)
                    }
                } finally {
                    runCatching { connection.disconnect() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Unobservable is a real answer; it must not be turned into "zero in flight".
                Log.w(TAG, "DSH gateway activity probe failed (${failure.javaClass.simpleName})")
                DshInFlightEvidence.Unobservable(
                    DshInFlightChannel.MODEL_GATEWAY,
                    "activity probe failed: " + failure.javaClass.simpleName
                )
            }
        }
    }

    /** Stops the current gateway generation and cancels its per-call work. Idempotent. */
    suspend fun stop() {
        val existing = mutex.withLock {
            val current = server
            server = null
            catalog = DshCatalogState.None
            current
        } ?: return
        runCatching { existing.stop() }
            .onFailure { Log.w(TAG, "DSH gateway stop failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Digests the capability facts that the DSH overlay advertises for one model. It is an
     * app-side change signal; the gateway's own `/v1/models` revision is computed separately by
     * [DshGatewayServer] and is not required to match.
     */
    private fun revisionOf(models: List<DshGatewayModel>): String {
        val payload = models.joinToString("\n") { model ->
            listOf(
                model.id,
                model.displayName,
                model.contextWindow?.toString().orEmpty(),
                model.maxOutputTokens?.toString().orEmpty(),
                model.supportsTools.toString(),
                model.nativeToolCalling.toString(),
                model.toolProtocolLabel.orEmpty()
            ).joinToString("|")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return buildString {
            append("mca-")
            digest.take(8).forEach { byte ->
                append((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
            }
        }
    }

    private companion object {
        const val TAG = "McaDshGateway"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val HEALTH_TIMEOUT_MILLIS = 750
        const val MAX_HEALTH_CHARS = 4_096
    }
}

/**
 * Secret-free diagnostics sink. The gateway only passes lifecycle lines, route names and safe
 * error codes; request bodies, model output and the bearer token never reach this logger.
 */
private object AndroidDshGatewayLogger : DshGatewayLogger {
    override fun log(level: DshGatewayLogLevel, message: String) {
        when (level) {
            DshGatewayLogLevel.DEBUG -> Log.d("McaDshGateway", message)
            DshGatewayLogLevel.WARN -> Log.w("McaDshGateway", message)
            DshGatewayLogLevel.INFO -> Log.i("McaDshGateway", message)
        }
    }
}
