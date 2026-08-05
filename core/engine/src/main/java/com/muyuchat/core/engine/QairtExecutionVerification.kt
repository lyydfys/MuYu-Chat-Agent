package com.muyuchat.core.engine

import android.app.Application
import android.content.Context
import android.os.Build
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Normal QAIRT inference runs in the generic isolated text worker. The dry-run
 * purpose is an optional diagnostic that can cache exact execution evidence;
 * it is never a prerequisite for a user-facing load or run attempt.
 */
enum class QairtExecutionPurpose {
    NORMAL,
    ISOLATED_DRY_RUN
}

/**
 * The diagnostic activity is declared in this secondary process. Checking the
 * process here ensures only that worker records reusable execution evidence;
 * normal product availability never depends on this evidence.
 */
internal fun isQairtIsolatedDryRunProcess(context: Context): Boolean {
    val expected = "${context.packageName}:qairt_smoke"
    val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        runCatching {
            File("/proc/self/cmdline")
                .readText(Charsets.UTF_8)
                .substringBefore('\u0000')
        }.getOrDefault("")
    }
    return processName == expected
}

/**
 * Persistent evidence that an exact runtime combination completed a real,
 * isolated create/generate/destroy diagnostic. It can skip repeated diagnostics
 * but is never populated from a static device list or used as an admission gate.
 */
class QairtExecutionVerificationStore(
    private val file: File
) {
    @Synchronized
    fun verifiedIdentities(): Set<QairtBundleRuntimeIdentity> =
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val entries = root.optJSONArray("identities") ?: JSONArray()
            buildSet {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    QairtBundleRuntimeIdentity(
                        bundleSha256 = item.optString("bundleSha256"),
                        chipset = item.optString("chipset"),
                        runtimeFingerprint = item.optString("runtimeFingerprint")
                    ).takeIf(QairtBundleRuntimeIdentity::isComplete)?.let(::add)
                }
            }
        }.getOrDefault(emptySet())

    @Synchronized
    fun recordVerified(identity: QairtBundleRuntimeIdentity) {
        require(identity.isComplete) { "QAIRT verification identity is incomplete." }
        val identities = verifiedIdentities() + identity
        val root = JSONObject()
            .put("schema", "mca.qairt.execution-verification.v1")
            .put(
                "identities",
                JSONArray().apply {
                    identities
                        .sortedWith(
                            compareBy<QairtBundleRuntimeIdentity> { it.bundleSha256 }
                                .thenBy { it.chipset }
                                .thenBy { it.runtimeFingerprint }
                        )
                        .forEach { entry ->
                            put(
                                JSONObject()
                                    .put("bundleSha256", entry.bundleSha256)
                                    .put("chipset", entry.chipset)
                                    .put("runtimeFingerprint", entry.runtimeFingerprint)
                            )
                        }
                }
            )
        atomicWrite(root.toString())
    }

    private fun atomicWrite(value: String) {
        val parent = file.parentFile ?: error("QAIRT verification file has no parent directory.")
        check(parent.exists() || parent.mkdirs()) {
            "Unable to create QAIRT verification directory: ${parent.absolutePath}"
        }
        val staged = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            staged.writeText(value, Charsets.UTF_8)
            val published = runCatching {
                Files.move(
                    staged.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.recoverCatching {
                // Some external/adoptable storage filesystems do not expose an
                // atomic rename. Keep a replace-only fallback rather than losing
                // the ability to record a successful smoke.
                Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(published.isSuccess) {
                "Unable to publish QAIRT verification file: ${file.absolutePath}; " +
                    published.exceptionOrNull()?.message.orEmpty()
            }
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    companion object {
        fun forContext(context: Context): QairtExecutionVerificationStore =
            QairtExecutionVerificationStore(
                File(context.filesDir, "qairt_execution_verifications.json")
            )
    }
}

/**
 * The QAIRT runtime ships with the application, while firmware contributes the
 * device-side driver.  Include both the installed app revision and Android
 * build fingerprint so an app/runtime or OTA change invalidates an old smoke.
 */
fun qairtRuntimeIdentityFor(
    context: Context,
    bundleSha256: String?
): QairtBundleRuntimeIdentity? {
    val normalizedSha = bundleSha256.orEmpty().trim().lowercase()
    if (normalizedSha.isBlank()) return null
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull() ?: return null
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    val chipset = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Build.SOC_MANUFACTURER.orEmpty())
            add(Build.SOC_MODEL.orEmpty())
        }
        add(Build.HARDWARE.orEmpty())
        add(Build.BOARD.orEmpty())
    }.filter { it.isNotBlank() }
        .joinToString("/")
        .ifBlank { "unknown-chipset" }
    val runtimeFingerprint = buildString {
        append("geniex-qairt")
        append("|app=").append(context.packageName)
        append('/').append(packageInfo.versionName.orEmpty().ifBlank { "unknown" })
        append('#').append(versionCode)
        append("|build=").append(Build.FINGERPRINT.orEmpty().ifBlank { "unknown" })
        append("|abi=").append(Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" })
        append("|runtime=").append(
            qairtRuntimeBinaryFingerprint(File(context.applicationInfo.nativeLibraryDir))
        )
    }
    return QairtBundleRuntimeIdentity(
        bundleSha256 = normalizedSha,
        chipset = chipset,
        runtimeFingerprint = runtimeFingerprint
    )
}

/**
 * Version names are not sufficient for a locally patched or rebuilt runtime:
 * Android can replace an APK without changing versionCode/versionName. Bind a
 * QAIRT smoke result to the native binaries that actually cross the JNI and
 * QNN boundaries so stale canary evidence is not attributed to changed code.
 */
internal fun qairtRuntimeBinaryFingerprint(nativeLibraryDir: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    QAIRT_RUNTIME_IDENTITY_LIBRARIES.forEach { name ->
        val file = File(nativeLibraryDir, name)
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        if (!file.isFile) {
            digest.update("missing".toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            return@forEach
        }
        digest.update(file.length().toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val QAIRT_RUNTIME_IDENTITY_LIBRARIES = listOf(
    "libnpu_jni.so",
    "libgeniex.so",
    "libgeniex_core.so",
    "libgeniex_plugin_qairt.so",
    "libQnnSystem.so",
    "libQnnHtp.so",
    "libQnnHtpV79Stub.so",
    "libQnnHtpV79Skel.so",
    "libQnnHtpV81Stub.so",
    "libQnnHtpV81Skel.so"
)
