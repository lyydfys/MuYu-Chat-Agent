package com.muyuchat.mca

import java.security.MessageDigest
import java.util.Locale

// R1/S3: 从 MainViewModel 抽出的 Local API 幂等键哈希（纯函数，JVM 可直测）。
internal fun localApiIdempotencyKeyHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
