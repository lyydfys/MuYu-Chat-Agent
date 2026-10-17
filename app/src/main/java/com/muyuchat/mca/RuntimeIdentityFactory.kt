package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import java.io.File

/**
 * Builds the identity used by the runtime/profile coordinator.
 *
 * This intentionally lives in the app layer.  The core identity type is a
 * value object, while the app is the only layer that knows where the installed
 * APK libraries and user model files live.  No transient measurement (free
 * memory, temperature, battery, storage pressure, or foreground state) is
 * allowed to enter this factory's identity material.
 */
internal object RuntimeIdentityFactory {
    internal const val HASH_SCHEMA = "runtime-identity-hash-v2"
    internal const val ENGINE_CONTRACT_VERSION = "mca-local-chat-engine-v1"
    internal const val IDENTITY_SCHEMA_VERSION = "runtime-parameters-schema-v1"
    internal const val RULE_SET_VERSION = "model-family-rules-v3-target-model-adaptive-tuning"
    internal const val EVALUATOR_VERSION =
        "bootstrap-load-v1:1|minimum-text-v1:2|canary:text-v1|safety:safety-v1"
    internal const val EMBEDDED_TOKENIZER_VERSION = "embedded-tokenizer-v1"
    internal const val EMBEDDED_TEMPLATE_VERSION = "embedded-template-v1"
    internal const val EMBEDDED_CONFIG_VERSION = "embedded-config-v1"
    internal const val DIRECTORY_MERKLE_VERSION = "directory-merkle-v1"
    internal const val NATIVE_SET_VERSION = "native-library-set-v2-litert-qualcomm"
    internal const val MAX_TEMPLATE_CONFIG_BYTES = 4L * 1024L * 1024L
    internal const val MNN_RUNTIME_CONFIG_FILE_NAME = "mca_runtime_config.json"
    internal val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    /** Android values needed by the factory; kept separate so JVM tests do not
     * need a running Activity, PackageManager, or native library loader. */
    internal data class PlatformSnapshot(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val nativeLibraryDir: File?
    )

    /**
     * Material is useful to tests and diagnostics.  [identity] remains the
     * canonical persisted value; config has no dedicated field in the current
     * core schema, so its domain-separated digest is included in
     * [ModelRuntimeIdentity.backendFingerprint].
     */
    internal data class BuildResult(
        val identity: ModelRuntimeIdentity,
        val configFingerprint: String,
        val nativeLibraries: List<String>
    )

    /** Hash current file components so a same-size replacement changes the load identity. */
    fun create(
        context: Context,
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        qairtAdmission: QairtExecutionAdmission? = null
    ): ModelRuntimeIdentity = build(
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        platform = platformSnapshot(context),
        qairtAdmission = qairtAdmission,
        qairtAdmissionPassed = false
    ).identity

    /** Compatibility overload for callers that only persist the admission
     * result and do not retain the full advisory object. */
    fun create(
        context: Context,
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        qairtAdmissionPassed: Boolean
    ): ModelRuntimeIdentity = build(
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        platform = platformSnapshot(context),
        qairtAdmission = null,
        qairtAdmissionPassed = qairtAdmissionPassed
    ).identity

    /** Pure/JVM-friendly entry point used by unit tests and migration tools. */
    internal fun buildForTesting(
        model: ModelManifest,
        runtime: LocalChatRuntime,
        device: DeviceProfile,
        installationScopeId: String,
        platform: PlatformSnapshot = PlatformSnapshot(
            packageName = "com.muyuchat.mca",
            versionName = "test",
            versionCode = 0L,
            nativeLibraryDir = null
        ),
        qairtAdmission: QairtExecutionAdmission? = null,
        qairtAdmissionPassed: Boolean = false
    ): BuildResult = build(
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        platform = platform,
        qairtAdmission = qairtAdmission,
        qairtAdmissionPassed = qairtAdmissionPassed
    )

    /** Testable native fingerprint helper; it hashes each selected .so once. */
    internal fun nativeFingerprint(
        nativeLibraryDir: File?,
        runtime: LocalChatRuntime,
        device: DeviceProfile
    ): Pair<String, List<String>> {
        return nativeFingerprint(nativeLibraryDir, runtime, device, HashSession())
    }
}
