package com.muyuchat.mca.agent.toolpack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException

/**
 * Real JavaScript engine for executable tool packages (Rhino, pure JVM).
 *
 * ## Boundary
 *
 * Each execution gets a fresh Rhino [Context] with:
 * - a deny-all [ClassShutter], so `java.*`, `javax.*`, `android.*` and every other Java class is
 *   invisible to scripts;
 * - the standard Rhino host globals (`Packages`, `java`, `javax`, `org`, `com`, `edu`, `net`,
 *   `JavaAdapter`, `getClass`, `loadClass`, `defineClass`, `load`, `readFile`, `readUrl`, `spawn`,
 *   `sync`, `quit`, `print`) deleted before the package script runs;
 * - a bounded `print`/`console` sink that never writes to host stdout;
 * - `host.call(method, argsJson[, stableOrdinal])` — the only capability seam — which delegates to
 *   the existing [ToolPackageHostCaller] (the host bridge); the script never receives a Java object
 *   or executor.
 *
 * ## Interruption
 *
 * Rhino runs interpreted with an instruction observer. The observer aborts on cooperative
 * cancellation, the wall-clock deadline or a thread interrupt. The engine itself additionally
 * enforces the same deadline and output budget, so it is usable without the runtime wrapper.
 *
 * **This is not an `isolatedProcess`/UID/FD sandbox.** A hostile script that catches the abort
 * error inside an unbounded loop, allocates unbounded memory or attacks the JVM itself is not
 * contained by this class; that remains N10's device-level isolation work (NOT_RUN). The host-side
 * guarantees here are: no Java class access, no implicit host functions, whitelisted host calls,
 * bounded arguments/results, wall-clock and instruction interruption.
 *
 * ## Script contract
 *
 * A tool entry evaluates in a fresh module scope. It may either define `function main(input)` or
 * publish `module.exports` / `exports.main` (CommonJS). `input` is the parsed argument object;
 * `main` returns a JSON-serialisable object serialised with Rhino's `JSON.stringify`.
 *
 * CommonJS is intentionally minimal: `require` only accepts relative paths inside the package's
 * validated script map (`./` or `../`), resolves `.js` optionally, caches modules, rejects cycles
 * and path escapes, and never exposes `node_modules`, built-ins, absolute paths or host Java. The
 * package `main` registration script is still not executed.
 */
class RhinoToolPackageScriptEngine(
    private val instructionObserverThreshold: Int = DEFAULT_INSTRUCTION_OBSERVER_THRESHOLD
) : ToolPackageScriptEngine {

    init {
        require(instructionObserverThreshold in 1..MAX_INSTRUCTION_OBSERVER_THRESHOLD) {
            "instruction observer threshold is outside the bounded range"
        }
    }

    override val engineId: String = ENGINE_ID

    override suspend fun execute(request: ToolPackageEngineRequest): ToolPackageEngineResult =
        runInterruptible(Dispatchers.IO) { evaluate(request) }

    private fun evaluate(request: ToolPackageEngineRequest): ToolPackageEngineResult {
        val deadlineNanos = System.nanoTime() + request.timeoutMillis * NANOS_PER_MILLI
        val factory = SandboxContextFactory(
            control = request.control,
            deadlineNanos = deadlineNanos,
            threshold = instructionObserverThreshold
        )
        val cx = factory.enterContext()
        try {
            // ES6 enables native Promise so a package can return a thenable from main. The engine
            // drains microtasks with a bounded loop; it never starts a host event loop.
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects(null, false)
            hardenScope(cx, scope, request, deadlineNanos)
            val loader = CommonJsModuleLoader(
                cx = cx,
                global = scope,
                request = request,
                deadlineNanos = deadlineNanos
            )
            val entry = loader.loadEntry(request.tool.entry)
            request.control.ensureActive()
            val main = resolveMain(entry)
            if (main !is Function) {
                return ToolPackageEngineResult.Failed("The script does not define a main function.")
            }
            val input = parseArguments(cx, scope, request.argumentsJson)
            val result = awaitPromiseIfNeeded(
                cx = cx,
                scope = scope,
                value = main.call(cx, scope, scope, arrayOf<Any?>(input)) ?: Undefined.instance,
                request = request,
                deadlineNanos = deadlineNanos
            )
            request.control.ensureActive()
            if (System.nanoTime() >= deadlineNanos) {
                throw timeoutFailure()
            }
            val json = NativeJSON.stringify(cx, scope, result, null, null) as? String
                ?: return ToolPackageEngineResult.Failed("The script did not return a JSON result.")
            if (json.toByteArray(Charsets.UTF_8).size > request.outputLimitBytes) {
                throw outputLimitFailure()
            }
            return ToolPackageEngineResult.Returned(json)
        } catch (failure: Throwable) {
            throwOrMap(failure, request, deadlineNanos)
        } finally {
            Context.exit()
        }
    }

    /**
     * Installs the only host surface and removes every standard global that could reach the JVM or
     * the filesystem. Removal happens before any package source is evaluated.
     */
    private fun hardenScope(
        cx: Context,
        scope: ScriptableObject,
        request: ToolPackageEngineRequest,
        deadlineNanos: Long
    ) {
        for (name in REMOVED_GLOBALS) {
            ScriptableObject.deleteProperty(scope, name)
        }
        val output = BoundedScriptOutput(request.outputLimitBytes)
        val print = PrintFunction(output)
        ScriptableObject.putProperty(scope, "print", print)
        val console = cx.newObject(scope)
        ScriptableObject.putProperty(console, "log", print)
        ScriptableObject.putProperty(console, "info", print)
        ScriptableObject.putProperty(console, "warn", print)
        ScriptableObject.putProperty(console, "error", print)
        ScriptableObject.putProperty(scope, "console", console)
        val host = cx.newObject(scope)
        ScriptableObject.putProperty(host, "call", HostCallFunction(request, deadlineNanos))
        ScriptableObject.putProperty(scope, "host", host)
    }

    private fun parseArguments(cx: Context, scope: Scriptable, argumentsJson: String): Any? = try {
        // Rhino 1.7.15's NativeJSON.parse dereferences the reviver unconditionally, so a null
        // reviver NPEs; an identity reviver preserves the parsed value exactly.
        NativeJSON.parse(cx, scope, argumentsJson, IdentityReviver)
    } catch (_: Exception) {
        throw ToolPackageException(
            ToolPackageError(
                ToolPackageErrorCode.EXECUTION_ARGUMENTS_INVALID,
                "The script arguments are not valid JSON."
            )
        )
    }

    /**
     * Maps a Rhino failure to a typed result. Original messages/stacks never leave this method:
     * script source, host paths and Java internals must not reach the model or logs through errors.
     */
    private fun throwOrMap(
        failure: Throwable,
        request: ToolPackageEngineRequest,
        deadlineNanos: Long
    ): Nothing {
        findTypedFailure(failure)?.let { throw it }
        if (request.control.isCancelled()) {
            throw ToolPackageCancelledException()
        }
        if (System.nanoTime() >= deadlineNanos) {
            throw timeoutFailure()
        }
        if (findAbort(failure) != null || Thread.currentThread().isInterrupted) {
            throw ToolPackageCancelledException()
        }
        throw ToolPackageException(
            ToolPackageError(
                ToolPackageErrorCode.ENGINE_FAILED,
                if (failure is StackOverflowError) {
                    "The script exhausted the interpreter stack."
                } else {
                    "The script failed without a safe detail."
                }
            )
        )
    }

    private fun findTypedFailure(failure: Throwable): Throwable? {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            when (current) {
                is ToolPackageException -> return current
                is ToolPackageCancelledException -> return current
                is WrappedException -> current = current.wrappedException
                null -> return null
                else -> current = current.cause
            }
        }
        return null
    }

    private fun findAbort(failure: Throwable): RhinoAbort? {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            if (current is RhinoAbort) return current
            current = when (current) {
                is WrappedException -> current.wrappedException
                null -> return null
                else -> current.cause
            }
        }
        return null
    }

    private fun timeoutFailure(): ToolPackageException = ToolPackageException(
        ToolPackageError(ToolPackageErrorCode.EXECUTION_TIMEOUT, "The script exceeded its execution timeout.")
    )

    private fun outputLimitFailure(): ToolPackageException = ToolPackageException(
        ToolPackageError(ToolPackageErrorCode.EXECUTION_OUTPUT_LIMIT, "The script exceeded its output budget.")
    )

    /** Bounded sink for `print`/`console`; output is counted, not retained or written to stdout. */
    private class BoundedScriptOutput(private val maxBytes: Int) {
        private var bytes: Long = 0L

        @Synchronized
        fun append(text: String) {
            val addition = text.toByteArray(Charsets.UTF_8).size.toLong() + 1L
            if (bytes + addition > maxBytes) {
                throw ToolPackageException(
                    ToolPackageError(
                        ToolPackageErrorCode.EXECUTION_OUTPUT_LIMIT,
                        "The script exceeded its output budget."
                    )
                )
            }
            bytes += addition
        }
    }

    private class PrintFunction(private val output: BoundedScriptOutput) : BaseFunction() {
        override fun call(
            cx: Context?,
            scope: Scriptable?,
            thisObj: Scriptable?,
            args: Array<out Any?>?
        ): Any? {
            val text = args?.joinToString(separator = " ") { Context.toString(it) }.orEmpty()
            output.append(text)
            return Undefined.instance
        }
    }

    /**
     * `host.call(method, argsJson[, stableOrdinal])` — the only capability available to a script.
     *
     * The Rhino interpreter runs on a dedicated blocking thread, so the suspend host bridge is
     * entered with [runBlocking] bound by the remaining execution time. The script only ever sees
     * the returned JSON text; it never receives a bridge, executor, context or Java object.
     *
     * The optional third argument is the guest's stable correlation ordinal. It is not a privilege:
     * the bridge uses it to make one logical call execute at most once. A non-integer value is
     * forwarded as an invalid ordinal and refused by the bridge.
     */
    private class HostCallFunction(
        private val request: ToolPackageEngineRequest,
        private val deadlineNanos: Long
    ) : BaseFunction() {
        override fun call(
            cx: Context?,
            scope: Scriptable?,
            thisObj: Scriptable?,
            args: Array<out Any?>?
        ): Any? {
            request.control.ensureActive()
            val remaining = (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI
            if (remaining <= 0L) {
                throw ToolPackageException(
                    ToolPackageError(
                        ToolPackageErrorCode.EXECUTION_TIMEOUT,
                        "The script exceeded its execution timeout."
                    )
                )
            }
            val method = args?.getOrNull(0)?.let { Context.toString(it) }.orEmpty()
            val arguments = args?.getOrNull(1)?.let { Context.toString(it) } ?: "{}"
            val ordinal = stableOrdinal(args?.getOrNull(2))
            return try {
                runBlocking {
                    withTimeout(remaining) { request.host.call(method, arguments, ordinal) }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw ToolPackageException(
                    ToolPackageError(
                        ToolPackageErrorCode.EXECUTION_TIMEOUT,
                        "The script exceeded its execution timeout."
                    )
                )
            } catch (cancelled: CancellationException) {
                throw ToolPackageCancelledException()
            }
        }

        private fun stableOrdinal(raw: Any?): Int {
            if (raw == null || raw === Undefined.instance) return ToolPackageHostCaller.AUTO_ORDINAL
            val number = raw as? Number ?: return INVALID_ORDINAL
            val value = number.toDouble()
            if (!value.isFinite() || value != Math.floor(value)) return INVALID_ORDINAL
            return if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) INVALID_ORDINAL else value.toInt()
        }
    }

    private class SandboxContextFactory(
        private val control: ToolPackageExecutionControl,
        private val deadlineNanos: Long,
        private val threshold: Int
    ) : ContextFactory() {

        override fun makeContext(): Context {
            val cx = SandboxContext(this, control, deadlineNanos)
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.setMaximumInterpreterStackDepth(MAX_INTERPRETER_STACK_DEPTH)
            cx.setInstructionObserverThreshold(threshold)
            cx.setClassShutter(DenyAllClassShutter)
            return cx
        }
    }

    private class SandboxContext(
        factory: ContextFactory,
        private val control: ToolPackageExecutionControl,
        private val deadlineNanos: Long
    ) : Context(factory) {

        override fun observeInstructionCount(instructionCount: Int) {
            if (control.isCancelled()) throw RhinoAbort
            if (System.nanoTime() >= deadlineNanos) throw RhinoAbort
            if (Thread.currentThread().isInterrupted) {
                Thread.interrupted()
                throw RhinoAbort
            }
        }
    }

    private object DenyAllClassShutter : ClassShutter {
        override fun visibleToScripts(className: String?): Boolean = false
    }

    /** Identity JSON reviver: Rhino's parse requires a non-null reviver even when none is wanted. */
    private object IdentityReviver : org.mozilla.javascript.Callable {
        override fun call(
            cx: Context?,
            scope: Scriptable?,
            thisObj: Scriptable?,
            args: Array<out Any?>?
        ): Any? = args?.getOrNull(1)
    }

    /** Stackless abort marker thrown from the instruction observer. */
    private object RhinoAbort : Error() {
        private fun readResolve(): Any = RhinoAbort
    }

    /** Result of one CommonJS module evaluation: its exports and its own module scope. */
    private class LoadedModule(
        val exports: Any,
        val moduleScope: ScriptableObject
    )

    /**
     * Minimal CommonJS loader confined to the package's own script map.
     *
     * Every module evaluates in a child scope of the hardened global, so the host surface and the
     * removed Java globals still apply. Only relative `.js` paths inside the package are accepted;
     * cycles, missing modules, escapes and oversized dependency graphs fail closed.
     */
    private inner class CommonJsModuleLoader(
        private val cx: Context,
        private val global: ScriptableObject,
        private val request: ToolPackageEngineRequest,
        private val deadlineNanos: Long
    ) {
        private val cache = LinkedHashMap<String, LoadedModule>()
        private val loading = LinkedHashSet<String>()

        fun loadEntry(entry: String): LoadedModule = load(normalizeEntry(entry), from = null, depth = 0)

        private fun load(path: String, from: String?, depth: Int): LoadedModule {
            cache[path]?.let { return it }
            if (depth > MAX_MODULE_DEPTH) {
                throw requireFailure("The package module graph is too deep.")
            }
            if (path in loading) {
                throw requireFailure("Cyclic require is not supported.")
            }
            if (cache.size >= MAX_MODULES) {
                throw requireFailure("The package has too many modules.")
            }
            val source = request.bundle.script(path)
                ?: throw requireFailure("The required module is not present in the package.")
            loading += path
            try {
                val moduleScope = cx.newObject(global) as ScriptableObject
                val exportsObject = cx.newObject(global)
                val moduleObject = cx.newObject(global)
                ScriptableObject.putProperty(moduleObject, "exports", exportsObject)
                ScriptableObject.putProperty(moduleObject, "id", path)
                ScriptableObject.putProperty(moduleObject, "filename", path)
                ScriptableObject.putProperty(moduleObject, "loaded", false)
                ScriptableObject.putProperty(moduleScope, "module", moduleObject)
                ScriptableObject.putProperty(moduleScope, "exports", exportsObject)
                ScriptableObject.putProperty(
                    moduleScope,
                    "require",
                    RequireFunction(path, this, depth)
                )
                cx.evaluateString(moduleScope, source, path, 1, null)
                request.control.ensureActive()
                if (System.nanoTime() >= deadlineNanos) throw timeoutFailure()
                ScriptableObject.putProperty(moduleObject, "loaded", true)
                val finalExports = ScriptableObject.getProperty(moduleObject, "exports")
                    ?: Undefined.instance
                val loaded = LoadedModule(finalExports, moduleScope)
                cache[path] = loaded
                return loaded
            } finally {
                loading -= path
            }
        }

        fun require(fromPath: String, specifier: String): Any {
            val target = resolve(fromPath, specifier)
            return load(target, from = fromPath, depth = cache.size + 1).exports
        }

        private fun resolve(fromPath: String, specifier: String): String {
            val raw = specifier.trim()
            if (raw.isEmpty() || raw.startsWith("/") || raw.contains('\\') || raw.contains(':')) {
                throw requireFailure("Only relative package modules can be required.")
            }
            if (!raw.startsWith("./") && !raw.startsWith("../")) {
                throw requireFailure("Only relative package modules can be required.")
            }
            val baseDirectory = fromPath.substringBeforeLast('/', missingDelimiterValue = "")
            val segments = ArrayDeque<String>()
            baseDirectory.split('/').filter { it.isNotEmpty() }.forEach { segments.addLast(it) }
            raw.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        if (segments.isEmpty()) throw requireFailure("The required path escapes the package.")
                        segments.removeLast()
                    }
                    else -> segments.addLast(segment)
                }
            }
            if (segments.isEmpty()) throw requireFailure("The required path escapes the package.")
            val normalized = segments.joinToString("/")
            val candidate = if (normalized.endsWith(".js")) normalized else "$normalized.js"
            if (request.bundle.script(candidate) == null) {
                if (request.bundle.script(normalized) != null) return normalized
                throw requireFailure("The required module is not present in the package.")
            }
            return candidate
        }

        private fun normalizeEntry(entry: String): String {
            val normalized = entry.trim().trimStart('/')
            if (normalized.isEmpty() || normalized.contains("..") || normalized.startsWith(".")) {
                throw requireFailure("The tool entry path is invalid.")
            }
            return normalized
        }
    }

    /** `require(specifier)` bound to the module that owns it. */
    private class RequireFunction(
        private val fromPath: String,
        private val loader: CommonJsModuleLoader,
        private val depth: Int
    ) : BaseFunction() {
        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any?>
        ): Any? {
            val specifier = Context.toString(args.getOrNull(0))
            return loader.require(fromPath, specifier)
        }
    }

    /**
     * Awaits a returned native Promise with a bounded microtask drain.
     *
     * Rhino resolves Promise jobs on the Context's microtask queue; the engine pumps it while the
     * wall-clock deadline holds. Rejections become a typed failure without the script's message.
     */
    private fun awaitPromiseIfNeeded(
        cx: Context,
        scope: Scriptable,
        value: Any,
        request: ToolPackageEngineRequest,
        deadlineNanos: Long
    ): Any {
        if (value !is org.mozilla.javascript.NativePromise) return value
        var settled = false
        var resolved: Any = Undefined.instance
        var rejected = false
        val onFulfilled = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                settled = true
                resolved = args.getOrNull(0) ?: Undefined.instance
                return Undefined.instance
            }
        }
        val onRejected = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? {
                settled = true
                rejected = true
                return Undefined.instance
            }
        }
        ScriptableObject.callMethod(
            cx,
            value,
            "then",
            arrayOf<Any?>(onFulfilled, onRejected)
        )
        while (!settled) {
            request.control.ensureActive()
            if (System.nanoTime() >= deadlineNanos) throw timeoutFailure()
            cx.processMicrotasks()
            if (!settled) {
                try {
                    Thread.sleep(PROMISE_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ToolPackageCancelledException()
                }
            }
        }
        if (rejected) {
            throw ToolPackageException(
                ToolPackageError(
                    ToolPackageErrorCode.ENGINE_FAILED,
                    "The script promise was rejected."
                )
            )
        }
        return resolved
    }

    private fun resolveMain(entry: LoadedModule): Any {
        val exported = entry.exports
        if (exported is Function) return exported
        if (exported is Scriptable) {
            val main = ScriptableObject.getProperty(exported, "main")
            if (main is Function) return main
        }
        return ScriptableObject.getProperty(entry.moduleScope, "main") ?: Undefined.instance
    }

    private fun requireFailure(message: String): ToolPackageException = ToolPackageException(
        ToolPackageError(ToolPackageErrorCode.ENGINE_FAILED, message)
    )

    private companion object {
        const val ENGINE_ID = "rhino-1.7.15"
        const val NANOS_PER_MILLI = 1_000_000L
        const val INVALID_ORDINAL = -1
        const val DEFAULT_INSTRUCTION_OBSERVER_THRESHOLD = 1_000
        const val MAX_MODULES = 64
        const val MAX_MODULE_DEPTH = 32
        const val PROMISE_POLL_MILLIS = 2L
        const val MAX_INSTRUCTION_OBSERVER_THRESHOLD = 100_000
        const val MAX_INTERPRETER_STACK_DEPTH = 512
        const val MAX_CAUSE_DEPTH = 16

        /**
         * Standard Rhino globals that provide host access, file/network loading or process control.
         * `Packages`/`java`/... are removed even though [DenyAllClassShutter] already blocks every
         * class: defense in depth, and it turns `typeof java` into `undefined` instead of a
         * class-access error that a script could probe.
         */
        val REMOVED_GLOBALS = listOf(
            "Packages", "java", "javax", "org", "com", "edu", "net",
            "JavaAdapter", "getClass", "loadClass", "defineClass",
            "load", "readFile", "readUrl", "spawn", "sync", "quit", "print"
        )
    }
}
