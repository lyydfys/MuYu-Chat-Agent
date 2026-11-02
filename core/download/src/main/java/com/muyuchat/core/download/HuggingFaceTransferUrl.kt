package com.muyuchat.core.download

/**
 * Transfer-URL policy for a canonical Hugging Face `resolve` URL.
 *
 * Provenance and every pin (repo, immutable revision, size, SHA-256) stay on
 * `huggingface.co`; only the HTTP front door is redirected to the first non-`huggingface.co`
 * endpoint in [DEFAULT_HUGGING_FACE_ENDPOINTS] (currently `https://hf-mirror.com`). This is the
 * same policy the ModelScope/Hugging Face catalog downloads already use, so a model or bundle
 * acquired by one path is acquired by the same host as the others.
 *
 * Bytes are still verified against the unchanged size and SHA-256 pins after transfer, so a
 * stale or foreign mirror can only make the download fail — never install different weights.
 * The mirror answers with a redirect to the Hugging Face Xet CDN, which OkHttp follows while
 * keeping `Range`/`If-Range`, so resume semantics do not change.
 */
fun huggingFacePreferredTransferUrl(url: String): String =
    preferredHuggingFaceMirrorUrl(url, DEFAULT_HUGGING_FACE_ENDPOINTS)
