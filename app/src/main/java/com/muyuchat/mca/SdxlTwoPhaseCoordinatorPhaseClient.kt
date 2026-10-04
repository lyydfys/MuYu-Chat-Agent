package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class SdxlPhaseClient(
    private val context: Context,
    private val phase: SdxlImagePhase,
    private val onProgress: (SdxlImagePhaseProgress) -> Unit
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val serviceReady = CompletableDeferred<ISdxlImagePhaseWorker>()
    private val result = CompletableDeferred<SdxlImagePhaseResult>()
    private sealed class TerminalOutcome {
        data class Success(val result: SdxlImagePhaseResult) : TerminalOutcome()
        data class Failure(val error: Throwable) : TerminalOutcome()
        object TimedOut : TerminalOutcome()
    }

    // Binder callbacks race the coroutine timeout on different threads. The first accepted
    // outcome is authoritative even when the coroutine has not resumed from result.await() yet.
    private val terminalOutcome = AtomicReference<TerminalOutcome?>(null)
    // Only the linked Binder death recipient may satisfy this proof. Android
    // connection callbacks can report a disconnected/dead/null binding without
    // proving that the exact disposable worker process instance has exited.
    private val binderDeath = CompletableDeferred<Unit>()
    private var service: ISdxlImagePhaseWorker? = null
    private var binder: IBinder? = null

    @Volatile
    private var workerPid: Int = -1
    private var bound = false
    private var bindingRequested = false
    private var closed = false

    @Volatile
    private var dispatchAttempted = false

    @Volatile
    private var requestId: String = ""

    private val deathRecipient = IBinder.DeathRecipient {
        binderDeath.complete(Unit)
        acceptFailure(
            LocalImageWorkerDisconnectedException(
                "SDXL ${phase.wireName} phase process died before result publication."
            )
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, connectedBinder: IBinder) {
            val connectedService = ISdxlImagePhaseWorker.Stub.asInterface(connectedBinder)
            val linkError = runCatching { connectedBinder.linkToDeath(deathRecipient, 0) }
                .exceptionOrNull()
            val accepted = synchronized(lifecycleLock) {
                if (closed || (!bound && !bindingRequested) || linkError != null) {
                    false
                } else {
                    binder = connectedBinder
                    service = connectedService
                    true
                }
            }
            if (!accepted) {
                runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                if (linkError != null) {
                    serviceReady.completeExceptionally(linkError)
                    acceptFailure(linkError)
                }
                runCatching { context.unbindService(this) }
                return
            }
            if (!serviceReady.complete(connectedService)) {
                runCatching { connectedBinder.unlinkToDeath(deathRecipient, 0) }
                releaseBindingForExit()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase service disconnected.")
        }

        override fun onBindingDied(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase binding died.")
        }

        override fun onNullBinding(name: ComponentName) {
            failConnection("SDXL ${phase.wireName} phase returned a null binding.")
        }
    }

    private val callback = object : ISdxlImagePhaseWorkerCallback.Stub() {
        override fun onProgress(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseProgress(payloadJson) }
                .getOrElse {
                    acceptFailure(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                acceptFailure(IllegalStateException("SDXL phase progress identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            onProgress(envelope)
        }

        override fun onComplete(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseResult(payloadJson) }
                .getOrElse {
                    acceptFailure(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                acceptFailure(IllegalStateException("SDXL phase result identity mismatch."))
                return
            }
            if (envelope.workerPid <= 0) {
                acceptFailure(IllegalStateException("SDXL phase did not report a worker PID."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            acceptSuccess(envelope)
        }

        override fun onError(payloadJson: String) {
            if (isClosed()) return
            val envelope = runCatching { SdxlImagePhaseProtocol.parseError(payloadJson) }
                .getOrElse {
                    acceptFailure(it)
                    return
                }
            if (envelope.requestId != requestId || envelope.phase != phase) {
                acceptFailure(IllegalStateException("SDXL phase error identity mismatch."))
                return
            }
            if (envelope.workerPid > 0) workerPid = envelope.workerPid
            acceptFailure(LocalImageWorkerRemoteException(envelope.code, envelope.message))
        }
    }

    suspend fun execute(request: SdxlImagePhaseRequest, timeoutMs: Long): SdxlPhaseCompletion {
        requestId = request.requestId
        val serviceClass = when (phase) {
            SdxlImagePhase.ENCODER -> SdxlEncoderWorkerService::class.java
            SdxlImagePhase.UNET -> SdxlUnetWorkerService::class.java
            SdxlImagePhase.VAE -> SdxlVaeWorkerService::class.java
        }
        synchronized(lifecycleLock) {
            check(!closed) { "SDXL ${phase.wireName} phase client is closed." }
            check(!bindingRequested && !bound) { "SDXL ${phase.wireName} phase bind was already requested." }
            bindingRequested = true
        }
        val didBind = runCatching {
            context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            synchronized(lifecycleLock) { bindingRequested = false }
            throw error
        }
        val closedDuringBind = synchronized(lifecycleLock) {
            bindingRequested = false
            if (!closed) bound = didBind
            closed
        }
        if (closedDuringBind && didBind) {
            runCatching { context.unbindService(connection) }
        }
        check(!closedDuringBind && didBind) { "Unable to bind SDXL ${phase.wireName} phase worker." }
        val terminal = try {
            withTimeout(timeoutMs) {
                val remote = serviceReady.await()
                // A Binder exception or false return can occur after the remote process has begun
                // handling the transaction, so the exact-process exit barrier starts before IPC.
                dispatchAttempted = true
                check(remote.execute(SdxlImagePhaseProtocol.request(request), callback)) {
                    "SDXL ${phase.wireName} phase rejected the request."
                }
                result.await()
            }
            acceptedTerminal()
        } catch (timeout: TimeoutCancellationException) {
            acceptTimeoutOrObserveWinner()
        } catch (cancelled: CancellationException) {
            // Parent cancellation still propagates, but must not turn an already accepted Binder
            // success into a worker cancellation while this client establishes its exit proof.
            (terminalOutcome.get() as? TerminalOutcome.Success)?.let { success ->
                completeSuccessfulResult(success.result)
                throw cancelled
            }
            acceptFailureOrObserveWinner(cancelled)
        } catch (error: Throwable) {
            acceptFailureOrObserveWinner(error)
        }
        return when (terminal) {
            is TerminalOutcome.Success -> completeSuccessfulResult(terminal.result)
            is TerminalOutcome.Failure -> throwFailureAfterExit(terminal.error)
            TerminalOutcome.TimedOut -> throwTimeoutAfterExit(timeoutMs)
        }
    }

    private fun acceptSuccess(envelope: SdxlImagePhaseResult): Boolean =
        acceptTerminal(TerminalOutcome.Success(envelope))

    private fun acceptFailure(error: Throwable): Boolean =
        acceptTerminal(TerminalOutcome.Failure(error))

    private fun acceptTerminal(outcome: TerminalOutcome): Boolean {
        if (!terminalOutcome.compareAndSet(null, outcome)) return false
        when (outcome) {
            is TerminalOutcome.Success -> result.complete(outcome.result)
            is TerminalOutcome.Failure -> result.completeExceptionally(outcome.error)
            TerminalOutcome.TimedOut -> Unit
        }
        return true
    }

    private fun acceptedTerminal(): TerminalOutcome =
        checkNotNull(terminalOutcome.get()) { "SDXL ${phase.wireName} phase completed without a terminal outcome." }

    private fun acceptTimeoutOrObserveWinner(): TerminalOutcome {
        terminalOutcome.compareAndSet(null, TerminalOutcome.TimedOut)
        return acceptedTerminal()
    }

    private fun acceptFailureOrObserveWinner(error: Throwable): TerminalOutcome {
        terminalOutcome.compareAndSet(null, TerminalOutcome.Failure(error))
        return acceptedTerminal()
    }

    private suspend fun completeSuccessfulResult(
        completed: SdxlImagePhaseResult
    ): SdxlPhaseCompletion {
        // The child deliberately exits instead of unloading QNN. Release the binding first so
        // Android does not restart the disposable service. Binder death identifies the exact
        // process instance even if Android immediately reuses its numeric PID.
        releaseBindingForExit()
        if (!awaitExactBinderDeath()) {
            cancelAndTerminate()
            throw workerExitUnconfirmed(
                IllegalStateException("SDXL ${phase.wireName} phase completed without exact Binder death proof.")
            )
        }
        return SdxlPhaseCompletion(completed, true)
    }

    private suspend fun throwFailureAfterExit(error: Throwable): Nothing {
        if (!cancelAndAwaitExit()) throw workerExitUnconfirmed(error)
        throw error
    }

    private suspend fun throwTimeoutAfterExit(timeoutMs: Long): Nothing {
        val timeout = LocalImageWorkerRemoteException(
            code = "qnn_sdxl_${phase.wireName}_worker_timeout",
            message = "SDXL ${phase.wireName} phase exceeded ${timeoutMs / 1000L}s and its isolated worker exit was confirmed."
        )
        if (!cancelAndAwaitExit()) throw workerExitUnconfirmed(timeout)
        throw timeout
    }

    fun cancelAndTerminate() {
        val currentService = synchronized(lifecycleLock) { service }
        runCatching { currentService?.cancel(requestId) }
    }

    private suspend fun cancelAndAwaitExit(): Boolean =
        withContext(NonCancellable) {
            cancelAndTerminate()
            releaseBindingForExit()
            if (!requiresExactExitProof()) true else awaitExactBinderDeath()
        }

    private suspend fun awaitExactBinderDeath(): Boolean =
        withContext(NonCancellable) {
            runCatching {
                withTimeout(SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS) {
                    binderDeath.await()
                }
                true
            }.getOrDefault(false)
        }

    private fun requiresExactExitProof(): Boolean =
        dispatchAttempted || synchronized(lifecycleLock) { binder != null }

    private fun workerExitUnconfirmed(cause: Throwable): LocalImageWorkerRemoteException =
        LocalImageWorkerRemoteException(
            code = "qnn_sdxl_${phase.wireName}_worker_exit_unconfirmed",
            message = cause.message.orEmpty().ifBlank {
                "SDXL ${phase.wireName} phase did not confirm isolated worker exit."
            } + " The exact Binder death was not confirmed, so runtime fallback is unsafe."
        )

    private fun releaseBindingForExit() {
        val shouldUnbind = synchronized(lifecycleLock) {
            if (!bound) false else {
                bound = false
                true
            }
        }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }

    private fun failConnection(message: String) {
        val failure = LocalImageWorkerDisconnectedException(message)
        if (isClosed()) return
        serviceReady.completeExceptionally(failure)
        acceptFailure(failure)
    }

    private fun isClosed(): Boolean = synchronized(lifecycleLock) { closed }

    private fun closeLifecycle(): Pair<IBinder?, Boolean> = synchronized(lifecycleLock) {
        if (closed) return@synchronized null to false
        closed = true
        val currentBinder = binder
        binder = null
        service = null
        val shouldUnbind = bound
        bound = false
        currentBinder to shouldUnbind
    }

    override fun close() {
        val (currentBinder, shouldUnbind) = closeLifecycle()
        currentBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }
}

private const val SDXL_PHASE_EXIT_CONFIRM_TIMEOUT_MS = 5L * 1_000L
