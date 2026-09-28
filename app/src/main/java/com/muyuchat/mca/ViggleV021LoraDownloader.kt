package com.muyuchat.mca

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publisher-pinned Viggle v0.2.1 adapter download. The bytes are verified by
 * LocalImageLoraStore before they become selectable by an image request.
 */
internal object ViggleV021LoraDownloader {
    const val DISPLAY_NAME = "Qwen-Image-2.1-viggle-turbo-v0.2.1-6step-lora-r128.safetensors"
    const val EXPECTED_SIZE_BYTES = 679_604_800L
    const val EXPECTED_SHA256 = "bafb91d0047df3f9b8a5a850b0c967f051164314d8aad778dfa34d9c24ec345b"

    private const val REVISION = "bb26a0f38e5fe6c124aaccc9187a87eed5d9ed13"
    private const val DOWNLOAD_URL =
        "https://huggingface.co/Viggle/Qwen-Image-2.1-viggle-turbo/resolve/$REVISION/$DISPLAY_NAME"

    fun download(store: LocalImageLoraStore, onProgress: (Long) -> Unit): LocalImageLoraRecord {
        val connection = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val response = connection.responseCode
            if (response !in 200..299) {
                throw IOException("Viggle 下载请求失败（HTTP $response）")
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > 0L && contentLength != EXPECTED_SIZE_BYTES) {
                throw IOException("Viggle 发布文件大小不符：$contentLength")
            }
            return connection.inputStream.use { input ->
                store.importVerifiedDownload(
                    displayName = DISPLAY_NAME,
                    expectedSizeBytes = EXPECTED_SIZE_BYTES,
                    expectedSha256 = EXPECTED_SHA256,
                    source = input,
                    onProgress = onProgress
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
