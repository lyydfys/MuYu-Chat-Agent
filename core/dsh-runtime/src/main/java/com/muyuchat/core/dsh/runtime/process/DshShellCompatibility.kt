package com.muyuchat.core.dsh.runtime.process

/**
 * Typed, honest statement about the shell the official DSH tools really get on Android.
 *
 * Why this exists: the official `dsh-terminal-bash` backend and the workspace `bash` tool assume a
 * GNU bash. On this payload `bin/bash` is a `/system/bin/sh` wrapper (the writable-home script is
 * not even exec()able at targetSdk 36), and Android's `sh` is `mksh`/`toybox`: not GNU bash. A
 * terminal that starts is therefore *not* evidence that bash exists, and MCA must not upgrade any
 * probe result into a "bash works" claim.
 *
 * The facts here are pure data derived from the same [DshCommandClosure] the executor uses, so they
 * are unit testable without a device. [DshGnuBashFeature] carries a side-effect-free `-c` probe and
 * the output GNU bash would print, so a device test can prove the missing feature by execution
 * instead of by assertion. Those probes are NOT run here: reporting a feature list is not
 * claiming the feature was exercised.
 */
enum class DshShellInterpreter {
    /** A real GNU bash (not the case on this payload). */
    GNU_BASH,

    /** Android `mksh`/`toybox` reached directly (`/system/bin/sh`). */
    ANDROID_SH,

    /** The packaged `libdshcmd.so` dispatcher invoked through the payload `bash` symlink. */
    DISPATCHER_SHIM,

    /**
     * An executable was resolved for the `bash` role, but its GNU identity was not proven by a
     * real `--version` run. Treated as "not GNU bash" until a device probe says otherwise.
     */
    OTHER_EXECUTABLE,

    /** Nothing can be started for the shell role. */
    NONE
}

/**
 * One GNU-bash capability that Android `sh` does not provide.
 *
 * [probeArgs] is an exact argv fragment for `<interpreter> <args...>`; [expectedOutput] is what a
 * real GNU bash prints. A device probe passes only when the observed stdout equals it, so "no
 * output" or a syntax error is never mistaken for support.
 */
enum class DshGnuBashFeature(
    val probeArgs: List<String>,
    val expectedOutput: String,
    val detail: String
) {
    ARRAYS(
        listOf("-c", "a=(1 2); printf '%s' \"\${a[1]}\""),
        "2",
        "indexed arrays (`a=(1 2)`, `\${a[1]}`) are a bash/ksh93 extension; mksh has none"
    ),
    BRACE_EXPANSION(
        listOf("-c", "printf '%s' {a,b}"),
        "ab",
        "brace expansion (`{a,b}`) is not performed by mksh"
    ),
    PROCESS_SUBSTITUTION(
        listOf("-c", "cat <(printf dsh)"),
        "dsh",
        "process substitution (`<(...)`, `>(...)`) is a bash/ksh93 feature"
    ),
    NAMEREF(
        listOf("-c", "f(){ local -n r=\$1; printf '%s' \"\$r\"; }; x=dsh; f x"),
        "dsh",
        "`local -n` namerefs are bash 4.3+/ksh93 only"
    ),
    SHOPT(
        listOf("-c", "shopt -s extglob; printf dsh"),
        "dsh",
        "the `shopt` builtin and `extglob`/`globstar` options are bash only"
    ),
    BASH_VERSION(
        listOf("-c", "printf '%s' \"\${BASH_VERSION:+set}\""),
        "set",
        "`BASH_VERSION`/`BASH_SOURCE` are unset outside bash, so `set` is printed only by GNU bash"
    )
}

/** One resolved shell fact plus the GNU features it provably lacks. */
data class DshShellCompatibility(
    val interpreter: DshShellInterpreter,
    /** True only for a proven GNU bash. Always false for the current payload. */
    val gnuBash: Boolean,
    /** The closure's launch report for the `bash` role (never a path from user input). */
    val launch: String,
    /** Bounded, prompt-free explanation of the substitution. */
    val detail: String,
    /**
     * GNU features the interpreter does not provide. A device probe may still prove one of them
     * present on a future payload; until then a tool that depends on them must expect failure.
     */
    val missingGnuFeatures: List<DshGnuBashFeature>
) {
    /** True when *a* shell can be started. Never means "bash semantics are available". */
    val executable: Boolean get() = interpreter != DshShellInterpreter.NONE

    /** Bounded one-line fact for logs and the console; no token, no path. */
    fun summary(): String = buildString {
        append("bash=")
        append(
            when (interpreter) {
                DshShellInterpreter.GNU_BASH -> "gnu"
                DshShellInterpreter.ANDROID_SH -> "android-sh"
                DshShellInterpreter.DISPATCHER_SHIM -> "dispatcher-shim"
                DshShellInterpreter.OTHER_EXECUTABLE -> "unverified"
                DshShellInterpreter.NONE -> "none"
            }
        )
        append(" gnuBash=").append(gnuBash)
        if (missingGnuFeatures.isNotEmpty()) {
            append(" missing=")
            append(missingGnuFeatures.joinToString(",") { it.name.lowercase().replace('_', '-') })
        }
    }

    /** Route/console payload facts. Serializable by [com.muyuchat.core.dsh.runtime.payload.DshJson]. */
    fun facts(): Map<String, Any?> = mapOf(
        "interpreter" to interpreter.name,
        "gnuBash" to gnuBash,
        "executable" to executable,
        "launch" to launch,
        "detail" to detail,
        "missingGnuFeatures" to missingGnuFeatures.map { feature ->
            mapOf(
                "feature" to feature.name,
                "probeArgs" to feature.probeArgs,
                "expectedOutput" to feature.expectedOutput,
                "detail" to feature.detail
            )
        }
    )

    companion object {

        /** Derives the note from the closure model exactly as the executor resolves it. */
        fun ofModel(model: DshCommandClosureModel): DshShellCompatibility =
            of(DshCommandClosure.resolve(model)[DshCommandRole.BASH])

        /** Derives the note from one already-resolved `bash` role. */
        fun of(availability: DshCommandAvailability?): DshShellCompatibility {
            if (availability == null) {
                return DshShellCompatibility(
                    interpreter = DshShellInterpreter.NONE,
                    gnuBash = false,
                    launch = "UNRESOLVED",
                    detail = "the closure did not resolve the bash role",
                    missingGnuFeatures = DshGnuBashFeature.entries
                )
            }
            val launch = availability.launch
            if (launch is DshCommandLaunch.Blocked) {
                return DshShellCompatibility(
                    interpreter = DshShellInterpreter.NONE,
                    gnuBash = false,
                    launch = availability.report(),
                    detail = "${launch.reason}: ${launch.detail}",
                    missingGnuFeatures = DshGnuBashFeature.entries
                )
            }
            if (launch is DshCommandLaunch.DispatcherLink) {
                return DshShellCompatibility(
                    interpreter = DshShellInterpreter.DISPATCHER_SHIM,
                    gnuBash = false,
                    launch = availability.report(),
                    detail = "the payload bash name is a libdshcmd.so shim that forwards to /system/bin/sh; " +
                        "gnuBash=false and GNU-only syntax fails",
                    missingGnuFeatures = DshGnuBashFeature.entries
                )
            }
            if (launch is DshCommandLaunch.PlatformShell) {
                return DshShellCompatibility(
                    interpreter = DshShellInterpreter.ANDROID_SH,
                    gnuBash = false,
                    launch = availability.report(),
                    detail = "the bash role resolves to the Android platform shell (" +
                        "${launch.shell}) with bash-only leading options stripped; gnuBash=false",
                    missingGnuFeatures = DshGnuBashFeature.entries
                )
            }
            // A real executable was resolved, but nothing in this build proves it is GNU bash.
            // Only a device `bash --version`/feature probe may promote this interpreter.
            return DshShellCompatibility(
                interpreter = DshShellInterpreter.OTHER_EXECUTABLE,
                gnuBash = false,
                launch = availability.report(),
                detail = "an executable was resolved for bash but its GNU identity is unproven; " +
                    "GNU features stay unverified until a device probe passes",
                missingGnuFeatures = DshGnuBashFeature.entries
            )
        }
    }
}
