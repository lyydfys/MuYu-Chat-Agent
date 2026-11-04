package com.muyuchat.feature.dsh.workspace.ui

/**
 * Pure helpers behind the experimental wireless-pairing form.
 *
 * Kept out of the view model so the validation and the failure text can be unit-tested on a plain JVM
 * (no `androidx.lifecycle`, no Compose). The view model only calls these functions.
 */

/** adb pairing codes are exactly six digits. */
const val PAIRING_CODE_LENGTH: Int = 6

/**
 * Validates the pairing form before any I/O.
 *
 * Returns a user-facing message, or null when the input is usable. [hostPortValid] is the caller's
 * `dshAdbEndpointFrom(raw) != null` result, so this file stays free of the parser (and of AndroidX) and
 * can be unit-tested on a plain JVM. The message never repeats the pairing code.
 */
fun dshAdbPairingInputError(hostPortValid: Boolean, code: String): String? {
    if (!hostPortValid) {
        return "配对端点格式应为 host:port，例如 10.0.0.2:43521"
    }
    if (code.length != PAIRING_CODE_LENGTH || !code.all { it in '0'..'9' }) {
        return "配对码是设备上显示的 6 位数字"
    }
    return null
}

/**
 * Builds the failure line: stable reason code plus the unverified-assumption names.
 *
 * Only the reason code and the fixed assumption labels appear here — never the pairing code, device
 * text or key material.
 */
fun dshAdbPairingFailureLine(reasonCode: String, unverifiedAssumptions: List<String>): String {
    val labels = unverifiedAssumptions.joinToString(" / ") { name ->
        when (name) {
            "tls_exporter_label" -> "exporter label"
            "aes_gcm_derivation" -> "GCM 派生"
            "peer_info_layout" -> "PeerInfo 布局"
            "packet_type_values" -> "packet type"
            else -> name
        }
    }
    return if (labels.isEmpty()) {
        "配对失败：$reasonCode"
    } else {
        "配对失败：$reasonCode · 未验证假设：$labels"
    }
}

/** Keeps the code field to six ASCII digits (non-digits dropped, over-long input truncated). */
fun dshAdbPairingCodeDigits(raw: String): String =
    raw.filter { it in '0'..'9' }.take(PAIRING_CODE_LENGTH)
