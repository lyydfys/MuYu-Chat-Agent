package com.muyuchat.mca

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

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
            ).withCanonicalUltraFixControls().also { it.validateProductInputContract() }
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

internal class LocalImageWorkerUpscaleInputs(
    val input: LocalImagePreparedInput,
    val upscaler: LocalImagePreparedUpscaler,
    val directory: File,
    private val upscalerLease: LocalImageUpscalerLease
) : AutoCloseable {
    override fun close() = upscalerLease.close()
}

internal class LocalImageUpscalerLease(
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {
    @Synchronized
    override fun close() {
        runCatching { if (lock.isValid) lock.release() }
        runCatching { channel.close() }
    }
}

/** Copies dispatch inputs into the worker-owned request directory and revalidates every byte. */
internal class LocalImageWorkerInputStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, WORKER_INPUT_DIRECTORY)
    private val dispatchRoot = File(appContext.cacheDir, LocalImageInputDispatcher.DISPATCH_DIRECTORY)
    private val loraRoot = File(appContext.filesDir, "image_loras")
    private val upscalerRoot = File(appContext.filesDir, LocalImageUpscalerStore.DIRECTORY)

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
                controlImage = options.controlImage?.let { copyPrepared(directory, "control", it) },
                loras = options.loras.map(::validateLora)
            ).also { it.validateProductInputContract() }
            LocalImageWorkerInputs(materialized, directory)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun materializeUpscale(
        requestId: String,
        input: LocalImagePreparedInput,
        upscaler: LocalImagePreparedUpscaler,
        isCancelled: () -> Boolean = { false }
    ): LocalImageWorkerUpscaleInputs {
        throwIfUpscaleCancelled(isCancelled)
        val materialized = materialize(
            requestId = requestId,
            options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImage = input,
                strength = 1.0
            )
        )
        var lockedUpscaler: LockedUpscaler? = null
        return try {
            throwIfUpscaleCancelled(isCancelled)
            lockedUpscaler = validateUpscaler(upscaler, isCancelled)
            LocalImageWorkerUpscaleInputs(
                input = requireNotNull(materialized.options.inputImage),
                upscaler = requireNotNull(lockedUpscaler).prepared,
                directory = materialized.directory,
                upscalerLease = requireNotNull(lockedUpscaler).lease
            )
        } catch (error: Throwable) {
            lockedUpscaler?.lease?.close()
            cleanup(materialized.directory)
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

    private fun validateLora(expected: LocalImagePreparedLora): LocalImagePreparedLora {
        val source = File(expected.path).canonicalFile
        val canonicalRoot = loraRoot.canonicalFile
        require(source.parentFile == canonicalRoot) {
            "LoRA adapter must be a direct child of the app-owned LoRA directory."
        }
        require(source.isFile && source.canRead() && source.length() == expected.sizeBytes) {
            "LoRA adapter is missing or changed before worker execution."
        }
        val actualSha256 = source.sha256()
        require(actualSha256 == expected.sha256) {
            "LoRA adapter content hash changed before worker execution."
        }
        return expected.copy(path = source.path)
    }

    private fun validateUpscaler(
        expected: LocalImagePreparedUpscaler,
        isCancelled: () -> Boolean
    ): LockedUpscaler {
        val source = File(expected.path).canonicalFile
        val canonicalRoot = upscalerRoot.canonicalFile
        require(source.parentFile == canonicalRoot) {
            "Upscaler model must be a direct child of the app-owned upscaler directory."
        }
        require(source.name == "upscaler-${expected.id}.${source.extension.lowercase()}") {
            "Upscaler model id does not match its app-owned file identity."
        }
        val lockFile = File(
            canonicalRoot,
            LocalImageUpscalerStore.lockFileName(source.name)
        ).canonicalFile
        require(lockFile.parentFile == canonicalRoot) { "Upscaler model lock path is invalid." }
        val channel = RandomAccessFile(lockFile, "rw").channel
        val lease = try {
            acquireUpscalerLease(channel, isCancelled)
        } catch (error: Throwable) {
            runCatching { channel.close() }
            throw error
        }
        return try {
            require(source.isFile && source.canRead() && source.length() == expected.sizeBytes) {
                "Upscaler model is missing or changed before worker execution."
            }
            require(source.extension.lowercase() in setOf("pth", "safetensors", "ckpt", "bin")) {
                "Upscaler model extension is unsupported."
            }
            require(source.sha256(isCancelled) == expected.sha256) {
                "Upscaler model content hash changed before worker execution."
            }
            throwIfUpscaleCancelled(isCancelled)
            LockedUpscaler(
                prepared = expected.copy(path = source.path),
                lease = lease
            )
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    private fun acquireUpscalerLease(
        channel: FileChannel,
        isCancelled: () -> Boolean
    ): LocalImageUpscalerLease {
        while (true) {
            throwIfUpscaleCancelled(isCancelled)
            val lock = try {
                channel.tryLock(0L, Long.MAX_VALUE, true)
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return LocalImageUpscalerLease(channel, lock)
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Local image upscale was cancelled while waiting for the model lease.")
            }
        }
    }

    private fun File.sha256(isCancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                throwIfUpscaleCancelled(isCancelled)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private data class LockedUpscaler(
        val prepared: LocalImagePreparedUpscaler,
        val lease: LocalImageUpscalerLease
    )

    private fun throwIfUpscaleCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            throw CancellationException("Local image upscale was cancelled during input verification.")
        }
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
        val exifOrientation = readPreparedInputExifOrientation(canonical)
        val orientedDimensions = localImageOrientedDimensions(
            bounds.outWidth,
            bounds.outHeight,
            exifOrientation
        )
        return LocalImagePreparedInput(
            path = canonical.path,
            mimeType = actualMime,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
            sizeBytes = size,
            width = bounds.outWidth,
            height = bounds.outHeight,
            orientedWidth = orientedDimensions.first,
            orientedHeight = orientedDimensions.second,
            exifOrientation = exifOrientation
        )
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}

private fun readPreparedInputExifOrientation(file: File): Int = runCatching {
    ExifInterface(file.path).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    .takeIf { it in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270 }
    ?: ExifInterface.ORIENTATION_NORMAL

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
