package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One-tap ADB provisioning through root.
 *
 * ## Why this is a second, deliberate command path
 *
 * [DshDeviceCommandPolicy] exists to stop *model-requested* commands from doing damage: it refuses
 * `setprop`, service restarts, raw device writes and anything unknown. Provisioning is the opposite
 * kind of command: a **code-owned constant script**, shown to the user in a confirmation dialog,
 * whose only parameter is this app's own base64 public key. Routing it through the model policy
 * would just mean disabling the policy. It therefore runs through
 * [DshRootChannel.runConstantScript], which is `internal` and only callable from this file; the
 * public [DshRootChannel.exec] keeps gating every user/model command, unchanged.
 *
 * ## Safety rules
 *
 * - The public key is validated against `[A-Za-z0-9+/=]` *before* any script is built. A key with a
 *   newline, quote, `;`, backtick, `$`, backslash or space is refused with `policy_blocked` and no
 *   root command runs.
 * - `adb_keys` is only **appended** to; existing keys of the user's other machines are untouched.
 * - Every step is bounded by the shared command timeout and the shared 256 KB output ceiling.
 * - Failures are typed: a SELinux/LSM refusal is reported as `selinux_denied`, a plain failure as
 *   `exec_failed`, so the UI can tell the user what to do next.
 */
class DshAdbProvisioner internal constructor(
    private val root: DshRootChannel,
    private val identity: () -> AdbIdentity,
    private val transport: () -> AdbTransport,
    private val portReady: suspend () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    /** Production wiring: the shared root channel plus the app-private key file. */
    constructor(root: DshRootChannel, identityFile: File) : this(
        root = root,
        identity = { AdbIdentity.loadOrCreate(identityFile) },
        transport = { SocketAdbTransport(identityFile) },
        portReady = { dshWaitForAdbPort() }
    )

    /**
     * Runs the five steps. Never throws: every failure becomes a step result plus the first failure.
     *
     * Steps still run in order and stop at the first failure; the returned list always starts with the
     * steps that actually ran, so the console can show exactly where provisioning stopped.
     */
    suspend fun provision(): DshAdbProvisioningOutcome = withContext(io) {
        val steps = mutableListOf<DshAdbProvisioningStepResult>()

        // Validate the key before building any script: a bad key never reaches a shell.
        val keyBase64 = runCatching { identity().publicKeyBase64() }.getOrNull()
        if (keyBase64 == null || !dshAdbKeyBase64Valid(keyBase64)) {
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.WRITE_ADB_KEYS,
                succeeded = false,
                detail = "公钥不是合法 base64，已拒绝拼入脚本",
                failure = DshDeviceCommandFailure.POLICY_BLOCKED
            )
            return@withContext DshAdbProvisioningOutcome(
                steps = steps,
                connected = false,
                failure = DshDeviceCommandFailure.POLICY_BLOCKED
            )
        }

        // 1. Root must be proven before anything is written.
        val probe = root.probe()
        if (probe.status != DshDeviceChannelStatus.READY) {
            val failure = if (probe.status == DshDeviceChannelStatus.NEEDS_GRANT) {
                DshDeviceCommandFailure.NOT_GRANTED
            } else {
                DshDeviceCommandFailure.EXEC_FAILED
            }
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.ROOT_PROBE,
                succeeded = false,
                detail = "Root 不可用（${probe.status.name.lowercase()}）",
                failure = failure
            )
            return@withContext DshAdbProvisioningOutcome(steps, connected = false, failure = failure)
        }
        steps += DshAdbProvisioningStepResult(
            step = DshAdbProvisioningStep.ROOT_PROBE,
            succeeded = true,
            detail = "su 可用，uid=0 已确认"
        )

        // 2. Read the existing authorisation list (a missing file is an empty list).
        val readResult = root.runConstantScript(DSH_ADB_READ_KEYS_SCRIPT)
        val readFailure = dshAdbStepFailure(readResult)
        if (readFailure != null) {
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.READ_ADB_KEYS,
                succeeded = false,
                detail = dshAdbStepDetail(readFailure),
                failure = readFailure
            )
            return@withContext DshAdbProvisioningOutcome(steps, connected = false, failure = readFailure)
        }
        val alreadyRegistered = keyPresentInAdbKeys(readResult.stdout, keyBase64)
        steps += DshAdbProvisioningStepResult(
            step = DshAdbProvisioningStep.READ_ADB_KEYS,
            succeeded = true,
            detail = if (alreadyRegistered) "已登记本应用公钥" else "尚未登记（文件为空或缺少本应用公钥）"
        )

        // 3. Append the key only when it is not there yet; never rewrite the file.
        if (alreadyRegistered) {
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.WRITE_ADB_KEYS,
                succeeded = true,
                detail = "公钥已存在，未重复写入，其它公钥保持不变"
            )
        } else {
            val appendScript = dshAdbAppendKeyScript(keyBase64)
            val writeResult = appendScript?.let { root.runConstantScript(it) }
                ?: DshDeviceCommandResult.failure(
                    DshDeviceCommandFailure.POLICY_BLOCKED,
                    message = "public key refused"
                )
            val writeFailure = dshAdbStepFailure(writeResult)
            if (writeFailure != null) {
                steps += DshAdbProvisioningStepResult(
                    step = DshAdbProvisioningStep.WRITE_ADB_KEYS,
                    succeeded = false,
                    detail = dshAdbStepDetail(writeFailure),
                    failure = writeFailure
                )
                return@withContext DshAdbProvisioningOutcome(
                    steps,
                    connected = false,
                    failure = writeFailure
                )
            }
            // Confirm by re-reading instead of trusting the write's exit code.
            val confirmResult = root.runConstantScript(DSH_ADB_READ_KEYS_SCRIPT)
            val confirmed = dshAdbStepFailure(confirmResult) == null &&
                keyPresentInAdbKeys(confirmResult.stdout, keyBase64)
            if (!confirmed) {
                steps += DshAdbProvisioningStepResult(
                    step = DshAdbProvisioningStep.WRITE_ADB_KEYS,
                    succeeded = false,
                    detail = "写入后未在 adb_keys 中找到公钥",
                    failure = DshDeviceCommandFailure.EXEC_FAILED
                )
                return@withContext DshAdbProvisioningOutcome(
                    steps,
                    connected = false,
                    failure = DshDeviceCommandFailure.EXEC_FAILED
                )
            }
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.WRITE_ADB_KEYS,
                succeeded = true,
                detail = "已追加到 adb_keys（未改动其它公钥）"
            )
        }

        // 4. Enable wireless adb on the loopback port and restart adbd.
        val enableResult = root.runConstantScript(DSH_ADB_ENABLE_TCP_SCRIPT)
        val enableFailure = dshAdbStepFailure(enableResult)
        if (enableFailure != null) {
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.ENABLE_TCP_ADB,
                succeeded = false,
                detail = dshAdbStepDetail(enableFailure),
                failure = enableFailure
            )
            return@withContext DshAdbProvisioningOutcome(
                steps,
                connected = false,
                failure = enableFailure
            )
        }
        steps += DshAdbProvisioningStepResult(
            step = DshAdbProvisioningStep.ENABLE_TCP_ADB,
            succeeded = true,
            detail = "service.adb.tcp.port=5555，adbd 已重启"
        )

        // 5. Wait for the port, connect with our own key and prove the shell identity.
        if (!portReady()) {
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.VERIFY_CONNECTION,
                succeeded = false,
                detail = "adb 端口未在 ${DSH_ADB_PORT_WAIT_TIMEOUT_MS}ms 内就绪",
                failure = DshDeviceCommandFailure.TIMEOUT
            )
            return@withContext DshAdbProvisioningOutcome(
                steps,
                connected = false,
                failure = DshDeviceCommandFailure.TIMEOUT
            )
        }
        val active = transport()
        try {
            if (!active.connect(dshAdbLoopbackEndpoint())) {
                val failure = active.lastFailure() ?: DshDeviceCommandFailure.HANDSHAKE_FAILED
                steps += DshAdbProvisioningStepResult(
                    step = DshAdbProvisioningStep.VERIFY_CONNECTION,
                    succeeded = false,
                    detail = dshAdbStepDetail(failure),
                    failure = failure
                )
                return@withContext DshAdbProvisioningOutcome(
                    steps,
                    connected = false,
                    failure = failure
                )
            }
            // Constant `id`: the verification probes the shell identity, it is not a user command.
            val idResult = active.shell(DSH_ADB_IDENTITY_PROBE)
            val idFailure = idResult.failure
            val shellUser = idFailure == null && DSH_ADB_SHELL_UID.containsMatchIn(idResult.stdout)
            if (!shellUser) {
                val failure = idFailure ?: DshDeviceCommandFailure.EXEC_FAILED
                steps += DshAdbProvisioningStepResult(
                    step = DshAdbProvisioningStep.VERIFY_CONNECTION,
                    succeeded = false,
                    detail = "已连通但身份不是 shell（uid=2000）",
                    failure = failure
                )
                return@withContext DshAdbProvisioningOutcome(
                    steps,
                    connected = false,
                    failure = failure
                )
            }
            steps += DshAdbProvisioningStepResult(
                step = DshAdbProvisioningStep.VERIFY_CONNECTION,
                succeeded = true,
                detail = "回连成功，uid=2000 校验通过"
            )
            return@withContext DshAdbProvisioningOutcome(
                steps = steps,
                connected = true,
                failure = null
            )
        } finally {
            runCatching { active.close() }
        }
    }
}

/** The five provisioning steps, in execution order. */
enum class DshAdbProvisioningStep(val code: String, val label: String) {
    ROOT_PROBE("root_probe", "探测 Root"),
    READ_ADB_KEYS("read_adb_keys", "读取 adb_keys"),
    WRITE_ADB_KEYS("write_adb_keys", "登记本应用公钥"),
    ENABLE_TCP_ADB("enable_tcp_adb", "开启 adb tcp 5555"),
    VERIFY_CONNECTION("verify_connection", "回连校验")
}

/** One step result: independently reportable, never containing command output or secrets. */
data class DshAdbProvisioningStepResult(
    val step: DshAdbProvisioningStep,
    val succeeded: Boolean,
    val detail: String,
    val failure: DshDeviceCommandFailure? = null
)

/** Result of one provisioning run. */
data class DshAdbProvisioningOutcome(
    val steps: List<DshAdbProvisioningStepResult>,
    val connected: Boolean,
    val failure: DshDeviceCommandFailure?
)

// -------------------------------------------------------------------------------------------------
// Fixed scripts and constants: the only text this feature ever sends to `su -c`.
// -------------------------------------------------------------------------------------------------

/** Loopback endpoint the provisioning enables and verifies. */
const val DSH_ADB_LOOPBACK_HOST: String = "127.0.0.1"

/** Port written to `service.adb.tcp.port`. */
const val DSH_ADB_TCP_PORT: Int = 5555

/** Authorisation list adbd reads at startup. */
const val DSH_ADB_KEYS_PATH: String = "/data/misc/adb/adb_keys"

/** How long step 5 waits for adbd to listen again after the restart. */
const val DSH_ADB_PORT_WAIT_TIMEOUT_MS: Long = 5_000L

/** Maximum accepted base64 length; a 2048-bit adb key is ~696 chars. */
internal const val DSH_ADB_KEY_MAX_BASE64_LENGTH: Int = 1024

/** Reads the current authorisation list; a missing file produces no output (treated as empty). */
internal val DSH_ADB_READ_KEYS_SCRIPT: String =
    "if [ -f $DSH_ADB_KEYS_PATH ]; then cat $DSH_ADB_KEYS_PATH; fi"

/**
 * Enables wireless adb and restarts adbd.
 *
 * The exit status is the last `[ ... ] && [ ... ] && [ ... ]` test, so a failed `setprop` (for example
 * when SELinux denies the property write) makes the whole step fail instead of being masked by a
 * successful `start adbd`. The echoed `prop=/stop=/start=` line is diagnostic only.
 */
internal val DSH_ADB_ENABLE_TCP_SCRIPT: String =
    "setprop service.adb.tcp.port $DSH_ADB_TCP_PORT; P=\$?; stop adbd; S=\$?; start adbd; A=\$?; " +
        "echo \"prop=\$P stop=\$S start=\$A\"; " +
        "[ \"\$P\" -eq 0 ] && [ \"\$S\" -eq 0 ] && [ \"\$A\" -eq 0 ]"

/** Constant identity probe used by step 5 (not a user command). */
internal const val DSH_ADB_IDENTITY_PROBE: String = "id"

private const val DSH_ADB_PORT_PROBE_TIMEOUT_MS: Int = 250

private const val DSH_ADB_PORT_POLL_INTERVAL_MS: Long = 250L

/** adb shell runs as the `shell` user (uid 2000). */
private val DSH_ADB_SHELL_UID = Regex("\\buid=2000\\b")

/** Only base64 characters may appear in the key; anything else could escape the quoted script. */
private val DSH_ADB_KEY_BASE64_PATTERN = Regex("^[A-Za-z0-9+/=]{1,$DSH_ADB_KEY_MAX_BASE64_LENGTH}$")

/** SELinux/LSM refusal wording, used to separate "blocked by policy" from "the command failed". */
private val DSH_ADB_SELINUX_PATTERN =
    Regex("(?i)(avc:\\s*denied|selinux|operation not permitted|permission denied)")

/** The loopback endpoint used by the verification step. */
fun dshAdbLoopbackEndpoint(): DshAdbEndpoint =
    DshAdbEndpoint(DSH_ADB_LOOPBACK_HOST, DSH_ADB_TCP_PORT)

/**
 * True only for a single-line standard base64 key.
 *
 * The allowed set is exactly `[A-Za-z0-9+/=]`. Newlines, quotes, `;`, backticks, `$`, backslash,
 * spaces and anything longer than [DSH_ADB_KEY_MAX_BASE64_LENGTH] are refused — such a key could
 * otherwise terminate the single-quoted shell argument and run arbitrary text.
 */
fun dshAdbKeyBase64Valid(key: String): Boolean =
    key.length <= DSH_ADB_KEY_MAX_BASE64_LENGTH && DSH_ADB_KEY_BASE64_PATTERN.matches(key)

/**
 * The append script, or `null` when [keyBase64] is not a safe base64 key (the caller must then fail
 * with `policy_blocked` instead of building a script).
 */
internal fun dshAdbAppendKeyScript(keyBase64: String): String? {
    if (!dshAdbKeyBase64Valid(keyBase64)) return null
    return "K=$DSH_ADB_KEYS_PATH; [ -f \"\$K\" ] || { : > \"\$K\"; chmod 640 \"\$K\"; }; " +
        "printf '%s\\n' '$keyBase64' >> \"\$K\""
}

/** True when [keyBase64] already appears as a line of the authorisation list. */
internal fun keyPresentInAdbKeys(keysText: String, keyBase64: String): Boolean =
    keysText.lineSequence().any { it.trim() == keyBase64 }

/** SELinux/LSM denial detection for a fixed script's stderr. */
fun dshAdbSelinuxDenied(stderr: String): Boolean = DSH_ADB_SELINUX_PATTERN.containsMatchIn(stderr)

/**
 * Maps a fixed-script result to a typed failure: `reason` first, then SELinux wording, then a plain
 * command failure. A successful exit (0) maps to `null`.
 */
internal fun dshAdbStepFailure(result: DshDeviceCommandResult): DshDeviceCommandFailure? = when {
    result.reason != null -> result.reason
    result.exitCode == 0 -> null
    dshAdbSelinuxDenied(result.stderr) -> DshDeviceCommandFailure.SELINUX_DENIED
    else -> DshDeviceCommandFailure.EXEC_FAILED
}

/** Short, user-facing hint for a failed step; never contains command text or output. */
internal fun dshAdbStepDetail(failure: DshDeviceCommandFailure): String = when (failure) {
    DshDeviceCommandFailure.SELINUX_DENIED ->
        "SELinux 拒绝了该操作；需要在 Magisk/内核侧放行（例如允许 setprop service.adb.tcp.port）"

    DshDeviceCommandFailure.NOT_GRANTED -> "Root 未授权；先在 Magisk/KernelSU 中授予 MCA Root"
    DshDeviceCommandFailure.TIMEOUT -> "命令超时；adbd 可能未响应，请稍后重试"
    DshDeviceCommandFailure.POLICY_BLOCKED -> "命令被本地策略拒绝"
    DshDeviceCommandFailure.OUTPUT_LIMIT -> "输出超过上限，已截断"
    DshDeviceCommandFailure.PAIRING_REQUIRED -> "设备未信任本应用公钥"
    DshDeviceCommandFailure.CONNECT_FAILED -> "无法连接 adb 端口"
    DshDeviceCommandFailure.HANDSHAKE_FAILED -> "ADB 握手失败"
    DshDeviceCommandFailure.PROTOCOL_ERROR -> "ADB 协议错误"
    DshDeviceCommandFailure.NOT_INTEGRATED -> "ADB 通道未接入"
    DshDeviceCommandFailure.EXEC_FAILED -> "命令执行失败"
}

/**
 * Polls the loopback adb port until it accepts a TCP connection.
 *
 * Runs on the caller's IO dispatcher; `delay` keeps it cancellable and there is no busy loop.
 */
internal suspend fun dshWaitForAdbPort(
    host: String = DSH_ADB_LOOPBACK_HOST,
    port: Int = DSH_ADB_TCP_PORT,
    timeoutMs: Long = DSH_ADB_PORT_WAIT_TIMEOUT_MS
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val reachable = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), DSH_ADB_PORT_PROBE_TIMEOUT_MS)
            }
        }.isSuccess
        if (reachable) return true
        delay(DSH_ADB_PORT_POLL_INTERVAL_MS)
    }
    return false
}
