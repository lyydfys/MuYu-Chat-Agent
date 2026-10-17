package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.mca.RuntimeIdentityFactory.DIRECTORY_MERKLE_VERSION
import com.muyuchat.mca.RuntimeIdentityFactory.HASH_SCHEMA
import com.muyuchat.mca.RuntimeIdentityFactory.MNN_RUNTIME_CONFIG_FILE_NAME
import com.muyuchat.mca.RuntimeIdentityFactory.SHA256
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale


    internal fun nativeLibraryNames(
        runtime: LocalChatRuntime,
        device: DeviceProfile
    ): List<String> {
        val base = when (runtime) {
            LocalChatRuntime.LLAMA_CPP -> listOf(
                "libmca_native.so",
                // The MCA OpenCL backend is a separately packaged ggml
                // plugin. Include it in the runtime fingerprint so upgrading
                // from a CPU-only APK to a complete APK cannot reuse a
                // profile that was resolved without GPU offload.
                "libggml-opencl-mca.so"
            )
            LocalChatRuntime.MNN_CPU -> listOf(
                "libmca_mnn_native.so", "libMNN.so", "libMNN_Express.so", "libMNN_CL.so",
                "libMNNOpenCV.so", "libMNNAudio.so", "libllm.so"
            )
            LocalChatRuntime.GENIEX_LLAMA_CPP -> listOf(
                "libnpu_jni.so", "libgeniex.so", "libgeniex_core.so", "libgeniex_plugin_llama_cpp.so",
                "libllama.so", "libggml.so", "libggml-base.so", "libggml-cpu.so", "libmtmd.so",
                // GenieX's AAR and MCA's llama stack can coexist. Track both
                // OpenCL artifacts so a backend package change invalidates
                // stale CPU/GPU execution profiles deterministically.
                "libggml-opencl.so", "libggml-opencl-mca.so"
            )
            LocalChatRuntime.GENIEX_QAIRT -> listOf(
                "libnpu_jni.so", "libgeniex.so", "libgeniex_core.so", "libgeniex_plugin_qairt.so",
                "libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so",
                "libQnnHtpV79.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so",
                "libQnnHtpV81.so", "libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so"
            )
            LocalChatRuntime.LITERT_LM -> listOf(
                "liblitertlm_jni.so",
                // LiteRT Qualcomm NPU transport and optional JIT compiler.
                // Missing files remain part of the fingerprint and are not an
                // admission decision; the engine's real load decides support.
                "libLiteRtDispatch_Qualcomm.so",
                "libLiteRtCompilerPlugin_Qualcomm.so",
                "libQnnSystem.so",
                "libQnnHtp.so",
                "libQnnHtpPrepare.so",
                "libQnnIr.so",
                "libQnnSaver.so"
            )
        }
        // A future QAIRT package may use a different HTP generation. Include
        // the generation selected by the device profile without guessing a
        // exact chipset-name table; missing files remain explicit in the digest.
        val generation = Regex("v(\\d+)", RegexOption.IGNORE_CASE)
            .find(device.accelerationProfile.qnnHtpGeneration)
            ?.groupValues
            ?.getOrNull(1)
        val generationNames = generation?.let { version ->
            listOf("libQnnHtpV${version}.so", "libQnnHtpV${version}Stub.so", "libQnnHtpV${version}Skel.so")
        }.orEmpty()
        return (base + generationNames).distinct()
    }

    internal fun directoryMerkle(root: File, session: HashSession, files: List<File>): String {
        val entries = files.map { file ->
            relativePath(root, file) to session.fileSha256(file)
        }
        return session.entriesDigest(DIRECTORY_MERKLE_VERSION, entries)
    }

    internal fun safeFilesUnder(root: File, runtime: LocalChatRuntime): List<File> {
        if (!root.isDirectory) return emptyList()
        val canonicalRoot = canonicalFile(root)
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        return root.walkTopDown()
            .filter(File::isFile)
            .mapNotNull { file ->
                val canonical = runCatching { canonicalFile(file) }.getOrNull() ?: return@mapNotNull null
                if (canonical.path == canonicalRoot.path || canonical.path.startsWith(prefix)) file else null
            }
            .filterNot { file ->
                // Native writes this deterministic compatibility projection
                // after identity creation; it is derived runtime state, not a
                // user model component.
                runtime == LocalChatRuntime.MNN_CPU &&
                    relativePath(root, file).equals(MNN_RUNTIME_CONFIG_FILE_NAME, ignoreCase = true)
            }
            .toList()
    }

    internal fun relativePath(root: File, file: File): String = runCatching {
        // Keep the lexical relative name (including a symlink's name) while
        // HashSession still hashes the canonical target content. This makes a
        // rename or symlink-path change part of the Merkle identity.
        root.absoluteFile.toPath().normalize().relativize(file.absoluteFile.toPath().normalize())
            .toString().replace('\\', '/')
    }.getOrElse { file.name }

    internal fun relativeSidecarPath(file: File): String =
        "sidecar/${file.name}"

    internal fun canonicalFile(file: File): File = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }

    internal fun normalizedSha(value: String?): String? = value
        ?.trim()
        ?.takeIf { SHA256.matches(it) }
        ?.lowercase(Locale.US)

    internal fun String.normalizedOrUnknown(): String = trim().ifBlank { "unknown" }

    internal class HashSession {
        private val fileHashes = LinkedHashMap<String, String>()

        fun fileSha256(file: File): String {
            val canonical = canonicalFile(file)
            val key = canonical.path
            return fileHashes.getOrPut(key) {
                require(canonical.isFile && canonical.canRead()) {
                    "Unable to hash runtime identity component: ${canonical.absolutePath}"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val sizeBefore = canonical.length()
                val modifiedBefore = canonical.lastModified()
                canonical.inputStream().buffered().use { input -> digestStream(digest, input) }
                require(canonical.length() == sizeBefore && canonical.lastModified() == modifiedBefore) {
                    "Runtime identity component changed while hashing: ${canonical.absolutePath}"
                }
                digestHex(digest.digest())
            }
        }

        fun entriesDigest(domain: String, entries: List<Pair<String, String>>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("$HASH_SCHEMA/$domain\n".toByteArray(StandardCharsets.UTF_8))
            entries.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .forEach { (path, sha) ->
                    digest.update(path.replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(sha.lowercase(Locale.US).toByteArray(StandardCharsets.US_ASCII))
                    digest.update(0.toByte())
                }
            return digestHex(digest.digest())
        }

        fun textDigest(domain: String, values: List<String>): String = entriesDigest(
            domain,
            values.mapIndexed { index, value -> index.toString() to value }
        )
    }

    private fun digestStream(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }

    private fun digestHex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        "%02x".format(Locale.US, byte.toInt() and 0xff)
    }

    internal val GENERIC_SIDECAR_NAMES = setOf(
        "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "added_tokens.json",
        "vocab.json", "vocab.txt", "merges.txt", "spiece.model", "sentencepiece.model",
        "config.json", "generation_config.json", "chat_template.jinja", "chat_template.jinja2",
        "template.jinja", "template.jinja2"
    )
    internal val TEMPLATE_CONFIG_NAMES = setOf(
        "config.json", "tokenizer_config.json", "llm_config.json", "generation_config.json",
        "genie_config.json", "metadata.json"
    )
    internal val CONFIG_EXTENSIONS = setOf("json", "yaml", "yml", "toml", "ini", "properties")
