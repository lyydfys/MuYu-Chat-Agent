package com.muyuchat.mca

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal data class LocalImageInputDispatch(
    val options: LocalImageGenerationOptions,
    val directory: File
)

/** Resolves UI/API references in the main process and guarantees Binder only sees local files. */
internal class LocalImageInputDispatcher(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, DISPATCH_DIRECTORY)

    fun prepare(
        requestId: String,
        draft: LocalImageInputDraft,
        baseOptions: LocalImageGenerationOptions
    ): LocalImageInputDispatch {
        require(requestId.isNotBlank()) { "Image input dispatch requestId must not be blank." }
        draft.validate()
        cleanupStaleDirectories(root)
        val directory = requestDirectory(root, requestId).apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create the image input dispatch directory." }
        }
        return try {
            val options = baseOptions.copy(
                taskMode = draft.taskMode,
                inputImage = draft.inputImageReference?.let { reference ->
                    stageReference(directory, "input", reference)
                },
                maskImage = draft.maskImageReference?.let { reference ->
                    stageReference(directory, "mask", reference)
                },
                controlImage = draft.controlImageReference?.let { reference ->
                    stageReference(directory, "control", reference)
                },
                strength = draft.strength,
                controlStrength = draft.controlStrength
            ).also { it.validateProductInputContract() }
            LocalImageInputDispatch(options = options, directory = directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun stageReference(directory: File, role: String, reference: String): LocalImagePreparedInput {
        val resolved = openReference(reference)
        resolved.stream.use { input ->
            return writeValidatedInput(
                directory = directory,
                role = role,
                input = input,
                declaredMimeType = resolved.mimeType
            )
        }
    }

    private fun openReference(reference: String): ResolvedInputStream {
        val value = reference.trim()
        require(value.isNotEmpty()) { "Image input reference must not be blank." }
        DATA_IMAGE_PATTERN.matchEntire(value)?.let { match ->
            val mimeType = match.groupValues[1].lowercase()
            val encoded = match.groupValues[2]
            val estimatedBytes = encoded.length.toLong() * 3L / 4L
            require(estimatedBytes <= LocalImagePreparedInput.MAX_INPUT_BYTES + 3L) {
                "Image data URL exceeds the ${LocalImagePreparedInput.MAX_INPUT_BYTES}-byte limit."
            }
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                .getOrElse { error("Image data URL contains invalid base64 data.") }
            return ResolvedInputStream(ByteArrayInputStream(bytes), mimeType)
        }

        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "content" -> {
                val stream = appContext.contentResolver.openInputStream(uri)
                    ?: error("The selected image could not be opened.")
                ResolvedInputStream(stream, appContext.contentResolver.getType(uri))
            }
            "file" -> {
                val file = uri.path?.let(::File) ?: error("Invalid file image reference.")
                require(file.isFile && file.canRead()) { "The referenced image file is not readable." }
                ResolvedInputStream(FileInputStream(file), null)
            }
            null, "" -> {
                val file = File(value)
                require(file.isAbsolute) { "Image file references must use an absolute path." }
                require(file.isFile && file.canRead()) { "The referenced image file is not readable." }
                ResolvedInputStream(FileInputStream(file), null)
            }
            else -> error(
                "Unsupported image input reference scheme: ${uri.scheme}. " +
                    "Use data:image, content:, file:, or an absolute readable path."
            )
        }
    }

    private data class ResolvedInputStream(
        val stream: InputStream,
        val mimeType: String?
    )

    companion object {
        internal const val DISPATCH_DIRECTORY = "local_image_dispatch_inputs"
        private val DATA_IMAGE_PATTERN = Regex(
            pattern = "^data:(image/[A-Za-z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)$",
            option = RegexOption.IGNORE_CASE
        )
    }
}

internal data class LocalImageWorkerInputs(
    val options: LocalImageGenerationOptions,
    val directory: File
)

/** Copies dispatch inputs into the worker-owned request directory and revalidates every byte. */
internal class LocalImageWorkerInputStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, WORKER_INPUT_DIRECTORY)
    private val dispatchRoot = File(appContext.cacheDir, LocalImageInputDispatcher.DISPATCH_DIRECTORY)

    fun materialize(requestId: String, options: LocalImageGenerationOptions): LocalImageWorkerInputs {
        options.validateProductInputContract()
        cleanupStaleDirectories(root)
        val directory = requestDirectory(root, requestId).apply {
            deleteRecursively()
            check(mkdirs()) { "Unable to create the worker image input directory." }
        }
        return try {
            val materialized = options.copy(
                inputImage = options.inputImage?.let { copyPrepared(directory, "input", it) },
                maskImage = options.maskImage?.let { copyPrepared(directory, "mask", it) },
                controlImage = options.controlImage?.let { copyPrepared(directory, "control", it) }
            ).also { it.validateProductInputContract() }
            LocalImageWorkerInputs(materialized, directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun cleanup(directory: File?) {
        if (directory == null) return
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (canonical.parentFile == canonicalRoot) canonical.deleteRecursively()
    }

    /** A newly created worker process cannot own any directory left by its predecessor. */
    fun clearOrphanedWorkerInputs() {
        root.listFiles()?.forEach { directory ->
            if (directory.isDirectory) directory.deleteRecursively()
        }
    }

    private fun copyPrepared(
        directory: File,
        role: String,
        expected: LocalImagePreparedInput
    ): LocalImagePreparedInput {
        val source = File(expected.path).canonicalFile
        val canonicalDispatchRoot = dispatchRoot.canonicalFile
        require(source.path.startsWith(canonicalDispatchRoot.path + File.separator)) {
            "Prepared $role image must come from the app-owned dispatch directory."
        }
        require(source.isFile && source.canRead()) { "Prepared $role image input is not readable." }
        val actual = FileInputStream(source).use { input ->
            writeValidatedInput(
                directory = directory,
                role = role,
                input = input,
                declaredMimeType = expected.mimeType
            )
        }
        require(actual.sha256 == expected.sha256 && actual.sizeBytes == expected.sizeBytes) {
            "Prepared $role image changed before the worker copied it."
        }
        require(actual.width == expected.width && actual.height == expected.height) {
            "Prepared $role image dimensions changed before the worker copied it."
        }
        return actual
    }

    companion object {
        internal const val WORKER_INPUT_DIRECTORY = "local_image_worker_inputs"
    }
}

private fun writeValidatedInput(
    directory: File,
    role: String,
    input: InputStream,
    declaredMimeType: String?
): LocalImagePreparedInput {
    if (declaredMimeType != null) {
        require(declaredMimeType.lowercase().startsWith("image/")) {
            "$role input MIME type must use image/*."
        }
    }
    val target = File(directory, "$role-${UUID.randomUUID()}.img")
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    try {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                size += read.toLong()
                require(size <= LocalImagePreparedInput.MAX_INPUT_BYTES) {
                    "$role image exceeds the ${LocalImagePreparedInput.MAX_INPUT_BYTES}-byte limit."
                }
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        require(size > 0L) { "$role image is empty." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "$role image format is invalid or unsupported."
        }
        val actualMime = bounds.outMimeType
            ?.lowercase()
            ?.takeIf { it.startsWith("image/") }
            ?: declaredMimeType?.lowercase()?.takeIf { it.startsWith("image/") }
            ?: error("$role image MIME type could not be determined.")
        val canonicalRoot = directory.canonicalFile
        val canonical = target.canonicalFile
        require(canonical.parentFile == canonicalRoot) { "$role image escaped its request directory." }
        return LocalImagePreparedInput(
            path = canonical.path,
            mimeType = actualMime,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
            sizeBytes = size,
            width = bounds.outWidth,
            height = bounds.outHeight
        )
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}

private fun requestDirectory(root: File, requestId: String): File {
    root.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(requestId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return File(root, digest)
}

private fun cleanupStaleDirectories(root: File) {
    val staleBefore = System.currentTimeMillis() - INPUT_MAX_AGE_MS
    root.listFiles()?.forEach { directory ->
        if (directory.isDirectory && directory.lastModified() < staleBefore) {
            directory.deleteRecursively()
        }
    }
}

private const val INPUT_MAX_AGE_MS = 24L * 60L * 60L * 1000L
