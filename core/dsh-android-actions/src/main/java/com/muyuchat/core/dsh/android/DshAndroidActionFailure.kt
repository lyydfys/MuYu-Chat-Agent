package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState

/**
 * Typed failure families of the App-local Android action routes.
 *
 * The official tool packages only read an `ok` flag and an `error` string, so the wire contract
 * stays that shape: the stable machine code is the `error` prefix (`PERMISSION_DENIED: ...`)
 * and never a new JSON field, because every one of these outputs is declared
 * `additionalProperties: false` and an extra field makes DSH reject the tool result as illegal.
 *
 * [contractCode] maps the wire family onto the shared capability failure vocabulary so the same
 * failure can be recorded by the platform ledger without a second taxonomy.
 */
enum class DshAndroidActionErrorCode(
    val wireName: String,
    val contractCode: DshCapabilityErrorCode
) {
    /** The request shape, method or a parameter is invalid. */
    INVALID_REQUEST("INVALID_REQUEST", DshCapabilityErrorCode.INVALID_REQUEST),

    /** The route exists but not for this HTTP verb (`/clipboard` and `/setting` are POST-only). */
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", DshCapabilityErrorCode.INVALID_REQUEST),

    /** A query parameter that the route does not read was supplied. */
    UNEXPECTED_QUERY("UNEXPECTED_QUERY", DshCapabilityErrorCode.INVALID_REQUEST),

    /** A required runtime permission is not granted. */
    PERMISSION_DENIED("PERMISSION_DENIED", DshCapabilityErrorCode.PERMISSION_DENIED),

    /** The permission is granted but the platform service is switched off. */
    NOT_ENABLED("NOT_ENABLED", DshCapabilityErrorCode.NOT_ENABLED),

    /** No usable channel/implementation exists (no root, no Shizuku, unsupported build). */
    CAPABILITY_UNAVAILABLE("CAPABILITY_UNAVAILABLE", DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE),

    /** The user explicitly denied the consent prompt. Nothing was executed. */
    CONSENT_DENIED("CONSENT_DENIED", DshCapabilityErrorCode.PERMISSION_DENIED),

    /** The consent prompt elapsed without an answer. Nothing was executed. */
    CONSENT_TIMEOUT("CONSENT_TIMEOUT", DshCapabilityErrorCode.TIMEOUT),

    /** The bounded execution deadline elapsed; the process was terminated. */
    TIMEOUT("TIMEOUT", DshCapabilityErrorCode.TIMEOUT),

    /** The caller cancelled; the process was terminated. */
    CANCELLED("CANCELLED", DshCapabilityErrorCode.USER_CANCELLED),

    /** The payload exceeds a route limit. */
    TOO_LARGE("TOO_LARGE", DshCapabilityErrorCode.TOO_LARGE),

    /** Another privileged command still owns the single execution slot. */
    BUSY("BUSY", DshCapabilityErrorCode.RATE_LIMITED),

    /** A setting key or package has no value/data. */
    NOT_FOUND("NOT_FOUND", DshCapabilityErrorCode.INVALID_REQUEST),

    /** A defect in the platform adapter. Never used for an expected negative outcome. */
    INTERNAL("INTERNAL", DshCapabilityErrorCode.INTERNAL);

    companion object {
        /** Maps a capability state onto the wire failure used when an action is refused. */
        fun refusalFor(state: DshCapabilityState): DshAndroidActionErrorCode = when (state) {
            DshCapabilityState.GRANTED -> CAPABILITY_UNAVAILABLE
            DshCapabilityState.DENIED, DshCapabilityState.NOT_DECLARED -> PERMISSION_DENIED
            DshCapabilityState.NOT_ENABLED -> NOT_ENABLED
            DshCapabilityState.UNSUPPORTED -> CAPABILITY_UNAVAILABLE
        }
    }
}

/**
 * One prompt-free, secret-free route failure.
 *
 * [message] is a single line with no control characters, at most [MAX_MESSAGE_CHARS] characters,
 * and never contains a token, a credential or a full command line. The factory sanitizes instead
 * of trusting callers, so an adapter exception text cannot break the wire format or leak a
 * multi-line log dump into the model's tool output.
 */
data class DshAndroidActionFailure(
    val code: DshAndroidActionErrorCode,
    val message: String
) {
    init {
        require(message.isNotBlank() && message.length <= MAX_MESSAGE_CHARS) {
            "android action failure message must be 1..$MAX_MESSAGE_CHARS characters"
        }
        require(message.none(::isForbiddenFailureCharacter)) {
            "android action failure message must be a prompt-free single line"
        }
    }

    /** `PERMISSION_DENIED: 未授予通知权限` — the only machine code the official tools can see. */
    val wireMessage: String
        get() = code.wireName + ": " + message

    /**
     * The same failure in the shared capability vocabulary. Used when the platform ledger records
     * the outcome; the wire body never carries it as a separate field.
     */
    val contractCode: DshCapabilityErrorCode
        get() = code.contractCode

    override fun toString(): String = "DshAndroidActionFailure(${code.wireName})"

    companion object {
        const val MAX_MESSAGE_CHARS = 240

        /**
         * Builds a failure from untrusted adapter text: collapses every run of whitespace and
         * control characters into one space, trims, caps at [MAX_MESSAGE_CHARS] without splitting
         * a surrogate pair, and substitutes a stable default when nothing usable remains.
         */
        fun of(code: DshAndroidActionErrorCode, rawMessage: String?): DshAndroidActionFailure {
            val sanitized = sanitize(rawMessage)
            val message = if (sanitized.isEmpty()) defaultMessage(code) else sanitized
            return DshAndroidActionFailure(code, message)
        }

        private fun sanitize(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            val builder = StringBuilder(minOf(raw.length, MAX_MESSAGE_CHARS + 1))
            var pendingSpace = false
            for (character in raw) {
                if (isForbiddenFailureCharacter(character)) {
                    pendingSpace = builder.isNotEmpty()
                    continue
                }
                if (pendingSpace) {
                    builder.append(' ')
                    pendingSpace = false
                }
                builder.append(character)
                if (builder.length > MAX_MESSAGE_CHARS) break
            }
            var text = builder.toString().trim()
            if (text.length > MAX_MESSAGE_CHARS) {
                text = text.take(MAX_MESSAGE_CHARS)
                if (text.last().isHighSurrogate()) text = text.dropLast(1)
            }
            return text
        }

        private fun defaultMessage(code: DshAndroidActionErrorCode): String = when (code) {
            DshAndroidActionErrorCode.INVALID_REQUEST -> "请求格式不正确"
            DshAndroidActionErrorCode.METHOD_NOT_ALLOWED -> "该端点不接受此 HTTP 方法"
            DshAndroidActionErrorCode.UNEXPECTED_QUERY -> "该端点不读取查询参数"
            DshAndroidActionErrorCode.PERMISSION_DENIED -> "缺少所需的系统权限"
            DshAndroidActionErrorCode.NOT_ENABLED -> "平台服务未启用"
            DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE -> "该能力当前不可用"
            DshAndroidActionErrorCode.CONSENT_DENIED -> "用户拒绝了本次操作"
            DshAndroidActionErrorCode.CONSENT_TIMEOUT -> "等待用户确认超时"
            DshAndroidActionErrorCode.TIMEOUT -> "执行超时"
            DshAndroidActionErrorCode.CANCELLED -> "操作已取消"
            DshAndroidActionErrorCode.TOO_LARGE -> "内容超过上限"
            DshAndroidActionErrorCode.BUSY -> "已有特权命令在执行"
            DshAndroidActionErrorCode.NOT_FOUND -> "目标不存在"
            DshAndroidActionErrorCode.INTERNAL -> "平台适配器内部错误"
        }
    }
}

/**
 * True for characters that must never appear in a failure message or a status detail: ASCII
 * control characters, DEL and the C1 range. Newlines are collapsed by the sanitizer, not kept.
 */
internal fun isForbiddenFailureCharacter(character: Char): Boolean =
    character.code <= 0x1F || character.code in 0x7F..0x9F
