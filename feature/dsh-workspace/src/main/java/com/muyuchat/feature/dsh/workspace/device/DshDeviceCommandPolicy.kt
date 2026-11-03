package com.muyuchat.feature.dsh.workspace.device

/**
 * Stable reason codes of a policy refusal.
 *
 * They are engineering codes (never localised, never containing the submitted command text), so a
 * rejection can be counted, asserted in tests and shown in the console without leaking what the
 * user or the agent typed.
 */
enum class DshDevicePolicyRejection(val code: String) {
    /** Blank command. */
    EMPTY_COMMAND("empty_command"),

    /** Longer than [DshDeviceCommandPolicy.MAX_COMMAND_LENGTH]. */
    COMMAND_TOO_LONG("command_too_long"),

    /** Shell construct that cannot be validated segment-by-segment (substitution, redirection, …). */
    SHELL_COMPOSITION("shell_composition"),

    /** `id;;ls`, a leading `;`, etc. One empty pipeline step. */
    EMPTY_SEGMENT("empty_segment"),

    /** A specifically destructive or privileged command, independent of its arguments. */
    DENIED_COMMAND("denied_command"),

    /** A whitelisted command invoked with arguments outside its allowed shape. */
    DENIED_ARGUMENT("denied_argument"),

    /** Not in the whitelist at all: unknown commands are never forwarded. */
    NOT_WHITELISTED("not_whitelisted")
}

/** How a segment is joined to the previous one; preserved so [DshDeviceCommandPolicy.renderSafe] keeps sh semantics. */
enum class DshDeviceSegmentSeparator(val token: String?) {
    FIRST(null),
    SEQUENCE(";"),
    AND("&&"),
    OR("||"),
    PIPE("|")
}

/** One validated pipeline step: an already tokenised argv plus how it is joined. */
data class DshDeviceCommandSegment(
    val index: Int,
    val separator: DshDeviceSegmentSeparator,
    val argv: List<String>
)

/** Outcome of policy evaluation. There is no "allowed with warnings" state and no override flag. */
sealed interface DshDeviceCommandDecision {

    data class Allowed(val segments: List<DshDeviceCommandSegment>) : DshDeviceCommandDecision

    data class Rejected(
        val rejection: DshDevicePolicyRejection,
        /** Static description of the violated rule; never the submitted command. */
        val rule: String
    ) : DshDeviceCommandDecision
}

/**
 * The one gate every device channel runs before a command reaches a shell.
 *
 * Model (matching the reference project's behaviour rather than a blocklist alone):
 * 1. the command is tokenised into argv segments (`;`, `&&`, `||`, `|` split the pipeline, quotes
 *    group arguments, escapes are resolved);
 * 2. any construct that cannot be validated per-segment is refused outright: `$( )`, backticks,
 *    redirection (`<`, `>`), subshells/grouping (`(`, `)`), braces, `&` background execution and
 *    `$` expansion. Quoted text is data, so `'$HOME'` inside single quotes stays literal;
 * 3. **every** segment is checked separately: one bad segment rejects the whole command;
 * 4. a segment is first matched against the explicit deny list (destructive / privileged commands),
 *    then against the whitelist, which accepts a command **name plus an argument shape** — a name
 *    on its own is never enough, and unknown names are refused instead of forwarded;
 * 5. only the whitelisted segments are re-rendered by [renderSafe] with single-quote escaping, and
 *    that rendering — never the raw user string — is what a channel executes.
 */
object DshDeviceCommandPolicy {

    /** Hard ceiling; a shell command this long is not a diagnostic. */
    const val MAX_COMMAND_LENGTH: Int = 2048

    /** Longest single argument accepted; keeps a path/flag from smuggling a script. */
    private const val MAX_ARGUMENT_LENGTH: Int = 256

    // ---------------------------------------------------------------------------------------
    // Deny list: refused by name, before the whitelist is consulted.
    // ---------------------------------------------------------------------------------------

    private val DENIED_COMMANDS: Set<String> = setOf(
        // Raw block devices and partitioning.
        "dd", "blkdiscard", "fdisk", "sfdisk", "gdisk", "sgdisk", "parted", "partx", "wipe", "format",
        // Filesystem mutation / deletion (this round is read-only diagnostics).
        "rm", "rmdir", "mv", "cp", "ln", "install", "tee", "truncate", "shred",
        // Boot, power and bootloader.
        "reboot", "shutdown", "poweroff", "halt", "recovery", "fastboot", "flash", "erase", "oem",
        // Privilege escalation, namespaces and kernel modules.
        "su", "sudo", "nsenter", "unshare", "chroot", "runcon", "insmod", "rmmod", "modprobe",
        // Security policy and mounts.
        "setenforce", "setprop", "resetprop", "mount", "umount", "chmod", "chown", "chcon",
        "restorecon", "chattr", "lsattr",
        // Firewalls, services, app installation and input injection.
        "iptables", "ip6tables", "nft", "svc", "am", "cmd", "content", "input", "monkey",
        "screencap", "screenrecord", "kill", "killall", "pkill",
        // Shell wrappers that would let an untokenised string run behind the policy's back.
        "sh", "bash", "toybox", "busybox", "env", "xargs", "eval", "exec", "source",
        "timeout", "nohup", "setsid", "nice", "watch", "strace"
    )

    /** Whole families refused by prefix (`mkfs.ext4`, `mkfs.f2fs`, …). */
    private val DENIED_PREFIXES: List<String> = listOf("mkfs")

    // ---------------------------------------------------------------------------------------
    // Whitelist: command name + argument shape.
    // ---------------------------------------------------------------------------------------

    private val PROP_NAME = Regex("^[A-Za-z0-9_.\\-]{1,92}$")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){0,10}$")
    private val SETTING_KEY = Regex("^[A-Za-z0-9_.:\\-]{1,128}$")
    private val SAFE_ARGUMENT = Regex("^[A-Za-z0-9_@%+=:,./\\-]+$")

    private val ID_ARGUMENTS: Set<List<String>> = setOf(
        emptyList(),
        listOf("-u"),
        listOf("-g"),
        listOf("-G"),
        listOf("-un"),
        listOf("-Gn")
    )

    private val UNAME_ARGUMENTS: Set<List<String>> = setOf(
        emptyList(),
        listOf("-a"),
        listOf("-r"),
        listOf("-m"),
        listOf("-s")
    )

    private val DATE_ARGUMENTS: Set<List<String>> = setOf(
        emptyList(),
        listOf("+%s")
    )

    private val PS_ARGUMENTS: Set<List<String>> = setOf(
        emptyList(),
        listOf("-A"),
        listOf("-e"),
        listOf("-ef")
    )

    private val DF_FLAGS: Set<String> = setOf("-h", "-k", "-a")
    private val FREE_FLAGS: Set<String> = setOf("-h", "-m", "-k")
    private val LS_FLAGS: Set<String> = setOf(
        "-l", "-a", "-1", "-d", "-h", "-lh", "-hl", "-al", "-la", "-ld", "-lah", "-alh", "-ahl"
    )

    private val PM_LIST_TARGETS: Set<String> =
        setOf("packages", "permission-groups", "features", "libraries", "users")
    private val PM_LIST_FLAGS: Set<String> = setOf("-3", "-s", "-d", "-e", "-f", "-i", "-u")
    private val SETTINGS_NAMESPACES: Set<String> = setOf("system", "secure", "global")

    /** Only kernel/pseudo filesystems are readable; app data and raw devices are not. */
    private val DIAGNOSTIC_PATH_PREFIXES: List<String> = listOf("/proc/", "/sys/")

    private val DENIED_DIAGNOSTIC_PATHS: List<String> = listOf(
        "/proc/kcore",
        "/proc/kmsg",
        "/proc/sysrq-trigger",
        "/proc/self/mem",
        "/proc/1/mem",
        "/dev/"
    )

    /**
     * Decide whether [command] may run. Pure: no IO, no clock, no state.
     */
    fun evaluate(command: String): DshDeviceCommandDecision {
        val raw = command.trim()
        if (raw.isEmpty()) {
            return reject(DshDevicePolicyRejection.EMPTY_COMMAND, "empty command")
        }
        if (raw.length > MAX_COMMAND_LENGTH) {
            return reject(DshDevicePolicyRejection.COMMAND_TOO_LONG, "command length ceiling")
        }
        val scanner = DshDeviceCommandScanner(raw)
        val segments = scanner.scan()
        if (segments == null) {
            return scanner.failure ?: reject(
                DshDevicePolicyRejection.SHELL_COMPOSITION,
                "unparseable command"
            )
        }
        if (segments.isEmpty()) {
            return reject(DshDevicePolicyRejection.EMPTY_COMMAND, "empty command")
        }
        segments.forEach { segment ->
            val rejection = validateSegment(segment)
            if (rejection != null) return rejection
        }
        return DshDeviceCommandDecision.Allowed(segments)
    }

    /**
     * Render validated segments back into one shell string with every argument quoted.
     *
     * This is the only string a channel is allowed to execute: quoting removes any chance that a
     * token which passed validation is re-interpreted as shell syntax later.
     */
    fun renderSafe(segments: List<DshDeviceCommandSegment>): String {
        require(segments.isNotEmpty()) { "at least one segment is required" }
        val out = StringBuilder()
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                out.append(' ')
                out.append(segment.separator.token ?: DshDeviceSegmentSeparator.SEQUENCE.token)
                out.append(' ')
            }
            segment.argv.forEachIndexed { argumentIndex, argument ->
                if (argumentIndex > 0) out.append(' ')
                out.append(shellQuote(argument))
            }
        }
        return out.toString()
    }

    /** POSIX single-quote escaping: safe for any content, including newlines and quotes. */
    fun shellQuote(argument: String): String =
        if (SAFE_ARGUMENT.matches(argument)) {
            argument
        } else {
            "'" + argument.replace("'", "'\\''") + "'"
        }

    // ---------------------------------------------------------------------------------------
    // Segment validation.
    // ---------------------------------------------------------------------------------------

    private fun validateSegment(segment: DshDeviceCommandSegment): DshDeviceCommandDecision.Rejected? {
        val argv = segment.argv
        if (argv.isEmpty()) {
            return reject(DshDevicePolicyRejection.EMPTY_SEGMENT, "empty pipeline segment")
        }
        if (argv.size > 8) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "too many arguments")
        }
        if (argv.any { it.length > MAX_ARGUMENT_LENGTH }) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "argument length ceiling")
        }
        val name = argv.first()
        if (name.isEmpty()) {
            return reject(DshDevicePolicyRejection.NOT_WHITELISTED, "empty command name")
        }
        if (name in DENIED_COMMANDS || DENIED_PREFIXES.any { name.startsWith(it) }) {
            return reject(DshDevicePolicyRejection.DENIED_COMMAND, "destructive or privileged command")
        }
        if (name.contains('/')) {
            return reject(DshDevicePolicyRejection.NOT_WHITELISTED, "command must be a bare whitelist name")
        }
        return when (name) {
            "id" -> validateId(argv)
            "getprop" -> validateGetprop(argv)
            "getenforce" -> requireNoArguments(argv, "getenforce")
            "uname" -> validateFixedArguments(argv, UNAME_ARGUMENTS, "uname")
            "date" -> validateFixedArguments(argv, DATE_ARGUMENTS, "date")
            "ps" -> validateFixedArguments(argv, PS_ARGUMENTS, "ps")
            "uptime" -> requireNoArguments(argv, "uptime")
            "pwd" -> requireNoArguments(argv, "pwd")
            "whoami" -> requireNoArguments(argv, "whoami")
            "df" -> validateFlagsOnly(argv, DF_FLAGS, "df")
            "free" -> validateFlagsOnly(argv, FREE_FLAGS, "free")
            "ls" -> validateLs(argv)
            "cat" -> validateCat(argv)
            "pm" -> validatePm(argv)
            "wm" -> validateWm(argv)
            "settings" -> validateSettings(argv)
            else -> reject(DshDevicePolicyRejection.NOT_WHITELISTED, "command is not whitelisted")
        }
    }

    private fun validateId(argv: List<String>): DshDeviceCommandDecision.Rejected? =
        validateFixedArguments(argv, ID_ARGUMENTS, "id")

    private fun validateGetprop(argv: List<String>): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        if (args.size > 1) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "getprop takes at most one name")
        }
        if (args.isEmpty()) return null
        return if (PROP_NAME.matches(args.first())) {
            null
        } else {
            reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "property name shape")
        }
    }

    private fun validateFixedArguments(
        argv: List<String>,
        allowed: Set<List<String>>,
        name: String
    ): DshDeviceCommandDecision.Rejected? =
        if (argv.drop(1) in allowed) {
            null
        } else {
            reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "$name argument shape")
        }

    private fun requireNoArguments(
        argv: List<String>,
        name: String
    ): DshDeviceCommandDecision.Rejected? =
        if (argv.size == 1) {
            null
        } else {
            reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "$name takes no arguments")
        }

    private fun validateFlagsOnly(
        argv: List<String>,
        allowedFlags: Set<String>,
        name: String
    ): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        if (args.size > 2 || args.any { it !in allowedFlags }) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "$name flag shape")
        }
        return null
    }

    private fun validateLs(argv: List<String>): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        if (args.size > 4) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "ls argument count")
        }
        args.forEach { argument ->
            if (argument.startsWith("-")) {
                if (argument !in LS_FLAGS) {
                    return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "ls flag shape")
                }
            } else if (!isSafePathArgument(argument)) {
                return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "ls path shape")
            }
        }
        return null
    }

    private fun validateCat(argv: List<String>): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        if (args.isEmpty()) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "cat requires an explicit path")
        }
        if (args.size > 3) {
            return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "cat argument count")
        }
        args.forEach { argument ->
            if (!isDiagnosticReadPath(argument)) {
                return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "cat path is not a diagnostics path")
            }
        }
        return null
    }

    private fun validatePm(argv: List<String>): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        if (args.size >= 2 && args[0] == "list" && args[1] in PM_LIST_TARGETS) {
            val rest = args.drop(2)
            if (rest.size > 3 || rest.any { it !in PM_LIST_FLAGS }) {
                return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "pm list flag shape")
            }
            return null
        }
        if (args.size == 2 && args[0] == "path" && PACKAGE_NAME.matches(args[1])) {
            return null
        }
        return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "pm subcommand is not read-only whitelisted")
    }

    private fun validateWm(argv: List<String>): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        return if (args == listOf("size") || args == listOf("density")) {
            null
        } else {
            reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "wm write or unknown subcommand")
        }
    }

    private fun validateSettings(argv: List<String>): DshDeviceCommandDecision.Rejected? {
        val args = argv.drop(1)
        if (args.size == 3 && args[0] == "get" &&
            args[1] in SETTINGS_NAMESPACES && SETTING_KEY.matches(args[2])
        ) {
            return null
        }
        if (args.size == 2 && args[0] == "list" && args[1] in SETTINGS_NAMESPACES) {
            return null
        }
        return reject(DshDevicePolicyRejection.DENIED_ARGUMENT, "settings write or unknown subcommand")
    }

    private fun isSafePathArgument(argument: String): Boolean =
        argument.isNotEmpty() &&
            argument.length <= MAX_ARGUMENT_LENGTH &&
            SAFE_ARGUMENT.matches(argument) &&
            !argument.contains("..")

    private fun isDiagnosticReadPath(argument: String): Boolean {
        if (!isSafePathArgument(argument)) return false
        if (DENIED_DIAGNOSTIC_PATHS.any { argument == it || argument.startsWith(it) }) return false
        if (argument.endsWith("/mem")) return false
        return DIAGNOSTIC_PATH_PREFIXES.any { argument.startsWith(it) && argument.length > it.length }
    }

    private fun reject(
        rejection: DshDevicePolicyRejection,
        rule: String
    ): DshDeviceCommandDecision.Rejected = DshDeviceCommandDecision.Rejected(rejection, rule)
}
