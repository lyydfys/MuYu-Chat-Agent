package com.muyuchat.mca

import java.net.IDN
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal val urlRegex = Regex("""https?://[^\s，。！？、）)\]}>"'`]+""", RegexOption.IGNORE_CASE)
internal class BlockedWebSearchUrlException(message: String) : IllegalArgumentException(message)

internal fun validateReadableWebSearchUrl(url: String) {
    blockedReadableWebSearchUrlReason(url)?.let { reason ->
        throw BlockedWebSearchUrlException(reason)
    }
}

internal fun blockedReadableWebSearchUrlReason(url: String): String? {
    val httpUrl = url.toHttpUrlOrNull()
        ?: return "MCA 已阻止联网检索读取无效网页地址。"
    if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
        return "MCA 只允许联网检索读取 http/https 网页。"
    }
    val host = httpUrl.host.trim().trimEnd('.')
    if (host.isBlank()) {
        return "MCA 已阻止联网检索读取空主机地址。"
    }
    val asciiHost = runCatching { IDN.toASCII(host).lowercase(Locale.ROOT) }
        .getOrDefault(host.lowercase(Locale.ROOT))
    if (asciiHost == "localhost" || asciiHost.endsWith(".localhost") || asciiHost.endsWith(".local")) {
        return "MCA 已阻止联网检索读取本机或局域网主机。"
    }
    val addresses = runCatching { InetAddress.getAllByName(asciiHost).toList() }
        .getOrElse { error ->
            if (error is UnknownHostException) {
                return null
            }
            return "MCA 无法确认该地址是否安全，已阻止读取。"
        }
    val blocked = addresses.firstOrNull { it.isRestrictedWebSearchAddress() }
    return if (blocked != null) {
        "MCA 已阻止联网检索读取本机、内网、链路本地或保留地址：${blocked.hostAddress}"
    } else {
        null
    }
}

internal fun InetAddress.isRestrictedWebSearchAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return true
    }
    val bytes = address
    if (bytes.size == 4) {
        return bytes.isRestrictedIpv4Address(0)
    }
    if (bytes.size == 16) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        val mappedIpv4 = bytes
            .takeIf { candidate -> (0 until 10).all { candidate[it].toInt() == 0 } }
            ?.takeIf { candidate -> (candidate[10].toInt() and 0xff) == 0xff && (candidate[11].toInt() and 0xff) == 0xff }
        if (mappedIpv4 != null) {
            return mappedIpv4.isRestrictedIpv4Address(12)
        }
        return first == 0 ||
            first == 0xfc ||
            first == 0xfd ||
            (first == 0xfe && (second and 0xc0) == 0x80) ||
            (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8)
    }
    return false
}

internal fun ByteArray.isRestrictedIpv4Address(offset: Int): Boolean {
    val first = this[offset].toInt() and 0xff
    val second = this[offset + 1].toInt() and 0xff
    val third = this[offset + 2].toInt() and 0xff
    return (first == 0) ||
        (first == 10) ||
        (first == 100 && second in 64..127) ||
        (first == 127) ||
        (first == 169 && second == 254) ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 0) ||
        (first == 192 && second == 168) ||
        (first == 198 && second in 18..19) ||
        (first == 198 && second == 51 && third == 100) ||
        (first == 203 && second == 0 && third == 113) ||
        (first >= 224)
}

internal fun String.extractHttpUrls(): List<String> =
    urlRegex.findAll(this)
        .map { it.value.trimEnd('.', ',', ';', ':') }
        .distinct()
        .toList()
internal fun String.normalizedUrlKey(): String =
    trim().trimEnd('/').substringBefore("#")
internal fun jinaReaderUrlFor(url: String): String =
    "https://r.jina.ai/http://$url"
