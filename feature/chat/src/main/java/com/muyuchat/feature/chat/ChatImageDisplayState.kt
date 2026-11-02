package com.muyuchat.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * R06: identity of one image surface, and the only thing that decides whether a decoded result may
 * be projected onto it.
 *
 * The decoded-image cache is keyed by source, source revision and target size. That is enough to
 * reuse bytes, but it is not enough to decide whether a *surface* may show them: two generation
 * tasks can legitimately reuse the same preview URI and the same preview revision, and a cancelled
 * producer can still complete after its owner is gone. Every projection therefore carries this
 * request identity, and only a result with an equal identity may reach the screen.
 *
 * [taskIdentity] is the owner identity, never a start time, a preview step or the URI itself:
 * `ImageGenerationUiJob.id` for a live generation preview, the asset id for a library asset, and a
 * local stable placeholder while no job exists yet.
 */
internal class ImageDisplayRequest(
    val uriString: String,
    val maxDimensionPx: Int? = null,
    val revisionKey: Any? = null,
    val taskIdentity: Any? = null
) {
    /** Cache-level revision token, shared with [ChatImageDecodeKey] so both agree on "same revision". */
    val revisionToken: String = chatImageRevisionToken(revisionKey)

    val normalizedMaxDimensionPx: Int? = maxDimensionPx?.coerceAtLeast(1)

    /** False means "no source configured yet", e.g. a generation step that has produced no frame. */
    val isPresent: Boolean = uriString.isNotBlank()

    fun matches(other: ImageDisplayRequest?): Boolean =
        other != null &&
            uriString == other.uriString &&
            normalizedMaxDimensionPx == other.normalizedMaxDimensionPx &&
            revisionToken == other.revisionToken &&
            taskIdentity == other.taskIdentity

    override fun equals(other: Any?): Boolean = other is ImageDisplayRequest && matches(other)

    override fun hashCode(): Int {
        var result = uriString.hashCode()
        result = 31 * result + (normalizedMaxDimensionPx ?: 0)
        result = 31 * result + revisionToken.hashCode()
        result = 31 * result + (taskIdentity?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ImageDisplayRequest(uri=$uriString, size=$normalizedMaxDimensionPx, " +
            "revision=$revisionToken, task=$taskIdentity)"
}

internal enum class ImageDisplayPhase { ABSENT, LOADING, READY, FAILED }

/**
 * The projected state of one image surface. [ABSENT] is not [LOADING]: "no frame exists yet" must
 * never render a spinner or an error, and [FAILED] is a retryable read failure, not a task failure.
 */
internal data class ImageDisplayProjection<T : Any>(
    val phase: ImageDisplayPhase,
    val request: ImageDisplayRequest?,
    val value: T? = null
) {
    init {
        require((phase == ImageDisplayPhase.READY) == (value != null)) {
            "READY is the only phase that carries a decoded value"
        }
        require((phase == ImageDisplayPhase.ABSENT) == (request == null)) {
            "ABSENT has no request identity and every other phase keeps its own"
        }
    }

    /** The decoded value only when this projection is READY; the init invariant forbids READY+null. */
    val readyValue: T?
        get() = if (phase == ImageDisplayPhase.READY) value else null

    companion object {
        fun <T : Any> absent(): ImageDisplayProjection<T> =
            ImageDisplayProjection(ImageDisplayPhase.ABSENT, request = null, value = null)

        fun <T : Any> loading(request: ImageDisplayRequest): ImageDisplayProjection<T> =
            ImageDisplayProjection(ImageDisplayPhase.LOADING, request, value = null)

        fun <T : Any> ready(request: ImageDisplayRequest, value: T): ImageDisplayProjection<T> =
            ImageDisplayProjection(ImageDisplayPhase.READY, request, value)

        fun <T : Any> failed(request: ImageDisplayRequest): ImageDisplayProjection<T> =
            ImageDisplayProjection(ImageDisplayPhase.FAILED, request, value = null)
    }
}

/**
 * Single-slot owner of the last committed projection for one surface.
 *
 * [project] is synchronous: a request whose identity does not match the committed result projects
 * Loading on the very first frame, so a previous image (another URI, another revision, another
 * generation task) can never be shown while the new decode runs. [commit] refuses anything whose
 * owner is gone or whose request is no longer current, so an abandoned or superseded decode cannot
 * write an old bitmap back after the surface left or switched.
 *
 * The backing store is injected so the same state machine runs in Compose ([read]/[write] over a
 * `MutableState`) and in unit tests (over a plain field), with no Android or Compose dependency.
 */
internal class ImageDisplaySlot<T : Any>(
    private val read: () -> ImageDisplayProjection<T>?,
    private val write: (ImageDisplayProjection<T>) -> Unit
) {
    fun project(request: ImageDisplayRequest): ImageDisplayProjection<T> {
        if (!request.isPresent) return ImageDisplayProjection.absent()
        val committed = read()
        return if (committed != null && committed.request?.matches(request) == true) {
            committed
        } else {
            ImageDisplayProjection.loading(request)
        }
    }

    fun commit(
        next: ImageDisplayProjection<T>,
        currentRequest: ImageDisplayRequest,
        ownerActive: Boolean
    ): ImageDisplayProjection<T> {
        val accepted = ownerActive && next.request?.matches(currentRequest) == true
        if (!accepted) return project(currentRequest)
        write(next)
        return next
    }
}

/**
 * Runs one request-identified decode and commits its outcome only while the owner still exists and
 * the request is still the current one.
 *
 * Cancellation is rethrown before any terminal state is built: it is not a failure, it must not
 * start a fallback decode (see [loadChatImageBitmap]'s cache/fallback split) and it must not be
 * retried here. An ordinary decode failure is committed as [ImageDisplayPhase.FAILED] so the surface
 * can offer a retry, but only for the request that is still on screen.
 */
internal suspend fun <T : Any> driveImageDisplay(
    slot: ImageDisplaySlot<T>,
    request: ImageDisplayRequest,
    currentRequest: () -> ImageDisplayRequest,
    decode: suspend (ImageDisplayRequest) -> T?
): ImageDisplayProjection<T> {
    if (!request.isPresent) return slot.project(request)
    val value = try {
        decode(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
    val next: ImageDisplayProjection<T> = if (value != null) {
        ImageDisplayProjection.ready(request, value)
    } else {
        ImageDisplayProjection.failed(request)
    }
    return slot.commit(
        next = next,
        currentRequest = currentRequest(),
        ownerActive = currentCoroutineContext().isActive
    )
}
