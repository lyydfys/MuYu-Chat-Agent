package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.tools.AgentToolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-execution budgets. Every field may be **lowered** by the caller but never raised above the
 * frozen package budget, so a composition mistake cannot silently weaken the isolation contract.
 */
data class ToolPackageRuntimeLimits(
    val maxToolTimeoutMillis: Long = ToolPackageLimits.MAX_TOOL_TIMEOUT_MILLIS,
    val maxToolArgumentsBytes: Int = ToolPackageLimits.MAX_TOOL_ARGUMENTS_BYTES,
    val maxOutputBytes: Int = ToolPackageLimits.MAX_OUTPUT_BYTES,
    val maxHostCalls: Int = ToolPackageLimits.MAX_HOST_CALLS,
    val maxHostArgumentsBytes: Int = ToolPackageLimits.MAX_HOST_ARGUMENT_BYTES,
    val maxHostResultBytes: Int = ToolPackageLimits.MAX_HOST_RESULT_BYTES,
    val maxHostTotalResultBytes: Int = ToolPackageLimits.MAX_HOST_TOTAL_RESULT_BYTES
) {
    init {
        require(maxToolTimeoutMillis in 1L..ToolPackageLimits.MAX_TOOL_TIMEOUT_MILLIS) {
            "runtime tool timeout is outside the frozen package budget"
        }
        require(maxToolArgumentsBytes in 1..ToolPackageLimits.MAX_TOOL_ARGUMENTS_BYTES) {
            "runtime tool argument budget is outside the frozen package budget"
        }
        require(maxOutputBytes in 1..ToolPackageLimits.MAX_OUTPUT_BYTES) {
            "runtime output budget is outside the frozen package budget"
        }
        require(maxHostCalls in 1..ToolPackageLimits.MAX_HOST_CALLS) {
            "runtime host call budget is outside the frozen package budget"
        }
        require(maxHostArgumentsBytes in 1..ToolPackageLimits.MAX_HOST_ARGUMENT_BYTES) {
            "runtime host argument budget is outside the frozen package budget"
        }
        require(maxHostResultBytes in 1..ToolPackageLimits.MAX_HOST_RESULT_BYTES) {
            "runtime host result budget is outside the frozen package budget"
        }
        require(maxHostTotalResultBytes in 1..ToolPackageLimits.MAX_HOST_TOTAL_RESULT_BYTES) {
            "runtime host total result budget is outside the frozen package budget"
        }
        require(maxHostResultBytes <= maxHostTotalResultBytes) {
            "runtime host total budget must cover one host result"
        }
    }
}

/**
 * One engine invocation. The engine gets package bytes, arguments, a cancellation signal, the
 * exact wall-clock/output budgets and the narrow host bridge.
 */
data class ToolPackageEngineRequest(
    val bundle: ExecutableToolPackage,
    val tool: ExecutableToolPackageTool,
    val argumentsJson: String,
    val control: ToolPackageExecutionControl,
    val host: ToolPackageHostCaller,
    val timeoutMillis: Long,
    val outputLimitBytes: Int
)

/**
 * Host-side script engine abstraction (N10).
 *
 * [RhinoToolPackageScriptEngine] is the real JVM implementation; a device `isolatedProcess`
 * adapter is still a future seam and is NOT_RUN. The engine receives no `Context`, `File`, `URL`,
 * raw Java object or reflection handle — only the read-only [ToolPackageEngineRequest] and the host
 * bridge. It must poll [ToolPackageExecutionControl.ensureActive] at interpreter interrupt points so
 * a cancel stop
 * is attributable to the execution instead of pretending the coroutine timeout stopped JavaScript.
 */
interface ToolPackageScriptEngine {
    val engineId: String

    suspend fun execute(request: ToolPackageEngineRequest): ToolPackageEngineResult
}

sealed interface ToolPackageEngineResult {
    data class Returned(val resultJson: String) : ToolPackageEngineResult

    data class Failed(val safeMessage: String) : ToolPackageEngineResult
}

/**
 * Cooperative execution control shared by the owner and the engine.
 *
 * [ensureActive] is the engine's interrupt fence: it throws [ToolPackageCancelledException] before
 * the next unit of work. It is intentionally not a coroutine `CancellationException`, so an engine
 * abort is reported as CANCELLED while real owner/scope cancellation still unwinds structurally.
 */
class ToolPackageExecutionControl internal constructor() {
    private val cancelled = AtomicBoolean(false)

    fun isCancelled(): Boolean = cancelled.get()

    fun ensureActive() {
        if (cancelled.get()) throw ToolPackageCancelledException()
    }

    internal fun cancel() {
        cancelled.set(true)
    }
}

/** Typed outcome of one isolated execution. A denial is distinct from a failure. */
sealed interface ToolPackageExecutionResult {
    data class Succeeded(val resultJson: String) : ToolPackageExecutionResult

    data class Failed(
        val error: ToolPackageError,
        val possibleSideEffects: Boolean = false
    ) : ToolPackageExecutionResult

    data class Denied(
        val error: ToolPackageError,
        val possibleSideEffects: Boolean = false
    ) : ToolPackageExecutionResult

    data object Cancelled : ToolPackageExecutionResult
}

/**
 * One execution request: frozen run identity, the tool to run and the arguments it already accepted.
 *
 * [approval] is consulted once per nested WRITE host call with the exact call binding. It defaults to
 * the fail-closed deny-all port, so a script-level run permission never turns into a nested write.
 */
data class ToolPackageExecutionRequest(
    val scope: ToolPackageExecutionScope,
    val tool: ExecutableToolPackageTool,
    val argumentsJson: String,
    val approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL
)

/**
 * Owns the engine and the host handler; one [open] call creates one execution instance.
 *
 * The runtime does **not** own MCA sources, model leases or a user directory. It only couples an
 * engine and a host handler with bounded budgets, so a future isolated-process adapter can replace
 * the engine without changing package validation or the executor projection.
 */
class ToolPackageScriptRuntime(
    private val engine: ToolPackageScriptEngine,
    private val hostHandler: ToolPackageHostOperationHandler,
    private val limits: ToolPackageRuntimeLimits = ToolPackageRuntimeLimits()
) {
    fun open(bundle: ExecutableToolPackage): ToolPackageScriptInstance =
        ToolPackageScriptInstance(
            engine = engine,
            bundle = bundle,
            hostHandler = hostHandler,
            limits = limits,
            instanceId = "tpi-" + UUID.randomUUID().toString()
        )
}

/**
 * One execution instance. At most one execution may run at a time; a concurrent start is rejected
 * with [ToolPackageErrorCode.INSTANCE_BUSY] instead of sharing interpreter state. The busy flag is
 * released only when the execution body actually exits, so a cancelled-but-still-unwinding run does
 * not allow a second run to overlap it.
 */
class ToolPackageScriptInstance internal constructor(
    private val engine: ToolPackageScriptEngine,
    private val bundle: ExecutableToolPackage,
    private val hostHandler: ToolPackageHostOperationHandler,
    private val limits: ToolPackageRuntimeLimits,
    val instanceId: String
) {
    private val busy = AtomicBoolean(false)

    fun start(scope: CoroutineScope, request: ToolPackageExecutionRequest): ToolPackageExecution {
        if (!busy.compareAndSet(false, true)) {
            return ToolPackageExecution.completed(
                ToolPackageExecutionResult.Failed(
                    ToolPackageError(
                        ToolPackageErrorCode.INSTANCE_BUSY,
                        "This script instance is already executing another call."
                    )
                )
            )
        }
        val control = ToolPackageExecutionControl()
        val deferred = try {
            scope.async {
                try {
                    executeInternal(request, control)
                } finally {
                    busy.set(false)
                }
            }
        } catch (failure: Throwable) {
            busy.set(false)
            throw failure
        }
        return ToolPackageExecution(deferred, control)
    }

    private suspend fun executeInternal(
        request: ToolPackageExecutionRequest,
        control: ToolPackageExecutionControl
    ): ToolPackageExecutionResult {
        control.ensureActive()
        if (bundle.manifest.tool(request.tool.name) != request.tool || bundle.script(request.tool.entry) == null) {
            return failed(
                ToolPackageErrorCode.EXECUTION_TOOL_UNKNOWN,
                "The requested tool is not part of this package snapshot."
            )
        }
        if (request.argumentsJson.toByteArray(Charsets.UTF_8).size > limits.maxToolArgumentsBytes) {
            return failed(
                ToolPackageErrorCode.EXECUTION_ARGUMENTS_INVALID,
                "The script arguments exceed the execution argument budget."
            )
        }
        val argumentsJson = canonicalObject(request.argumentsJson)
            ?: return failed(
                ToolPackageErrorCode.EXECUTION_ARGUMENTS_INVALID,
                "The script arguments must be a JSON object."
            )

        val bridge = ToolPackageHostBridge(
            bundle = bundle,
            scope = request.scope,
            instanceId = instanceId,
            handler = hostHandler,
            approval = request.approval,
            control = control,
            limits = limits
        )
        val timeoutMillis = minOf(request.tool.timeoutMillis, limits.maxToolTimeoutMillis)
        val engineRequest = ToolPackageEngineRequest(
            bundle = bundle,
            tool = request.tool,
            argumentsJson = argumentsJson,
            control = control,
            host = bridge,
            timeoutMillis = timeoutMillis,
            outputLimitBytes = limits.maxOutputBytes
        )
        val outcome = try {
            withTimeout(timeoutMillis) { engine.execute(engineRequest) }
        } catch (_: ToolPackageCancelledException) {
            return ToolPackageExecutionResult.Cancelled
        } catch (_: TimeoutCancellationException) {
            return failed(
                ToolPackageErrorCode.EXECUTION_TIMEOUT,
                "The script exceeded its execution timeout.",
                bridge.possibleSideEffects
            )
        } catch (failure: ToolPackageException) {
            return failure.toExecutionResult(bridge.possibleSideEffects)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                ToolPackageErrorCode.ENGINE_FAILED,
                "The script engine failed without a safe detail.",
                bridge.possibleSideEffects
            )
        }

        return when (outcome) {
            is ToolPackageEngineResult.Returned -> {
                if (outcome.resultJson.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) {
                    failed(
                        ToolPackageErrorCode.EXECUTION_OUTPUT_LIMIT,
                        "The script result exceeds the output budget.",
                        bridge.possibleSideEffects
                    )
                } else {
                    val result = canonicalObject(outcome.resultJson)
                    if (result == null) {
                        failed(
                            ToolPackageErrorCode.EXECUTION_RESULT_INVALID,
                            "The script result must be a JSON object.",
                            bridge.possibleSideEffects
                        )
                    } else {
                        ToolPackageExecutionResult.Succeeded(result)
                    }
                }
            }
            is ToolPackageEngineResult.Failed -> failed(
                ToolPackageErrorCode.ENGINE_FAILED,
                safeEngineMessage(outcome.safeMessage),
                bridge.possibleSideEffects
            )
        }
    }

    private fun failed(
        code: ToolPackageErrorCode,
        message: String,
        possibleSideEffects: Boolean = false
    ): ToolPackageExecutionResult.Failed =
        ToolPackageExecutionResult.Failed(ToolPackageError(code, message), possibleSideEffects)

    private fun ToolPackageException.toExecutionResult(possibleSideEffects: Boolean): ToolPackageExecutionResult =
        if (error.code.isDenial) {
            ToolPackageExecutionResult.Denied(error, possibleSideEffects)
        } else {
            ToolPackageExecutionResult.Failed(error, possibleSideEffects)
        }

    private fun canonicalObject(raw: String): String? {
        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        return AgentToolJson.canonical(parsed)
    }

    /** Engine diagnostics are bounded and stripped of controls; anything unusual is dropped. */
    private fun safeEngineMessage(raw: String): String {
        val candidate = raw.filter { !it.isISOControl() }.trim().take(MAX_ENGINE_MESSAGE_CHARS)
        return if (candidate.isEmpty()) "The script reported a failure." else candidate
    }

    private companion object {
        const val MAX_ENGINE_MESSAGE_CHARS = 256
    }
}

/**
 * Handle of one started execution.
 *
 * [cancel] sets the cooperative fence and cancels the awaiting coroutine; [await] reports CANCELLED
 * when this handle requested the stop, while an owner/scope cancellation keeps propagating.
 */
class ToolPackageExecution internal constructor(
    private val deferred: Deferred<ToolPackageExecutionResult>,
    private val control: ToolPackageExecutionControl
) {
    fun cancel() {
        control.cancel()
        deferred.cancel()
    }

    suspend fun await(): ToolPackageExecutionResult = try {
        deferred.await()
    } catch (cancelled: CancellationException) {
        if (control.isCancelled()) {
            ToolPackageExecutionResult.Cancelled
        } else {
            throw cancelled
        }
    }

    internal companion object {
        fun completed(result: ToolPackageExecutionResult): ToolPackageExecution =
            ToolPackageExecution(CompletableDeferred(result), ToolPackageExecutionControl())
    }
}
