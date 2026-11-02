package com.muyuchat.core.dsh.browser

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The 13 wire operations behind the 10 official `browser_*` tools.
 *
 * `tabs` is one tool with four server operations (`tabs.list`, `tabs.new`, `tabs.switch`,
 * `tabs.close`); keeping them as distinct ops means no handler ever has to guess which tab
 * lifecycle step it is running.
 */
enum class DshBrowserOp(val wire: String, val defaultTimeoutMillis: Long) {
    CAPS("caps", 8_000L),
    OPEN("open", 40_000L),
    TABS_LIST("tabs.list", 15_000L),
    TABS_NEW("tabs.new", 20_000L),
    TABS_SWITCH("tabs.switch", 20_000L),
    TABS_CLOSE("tabs.close", 20_000L),
    SNAPSHOT("snapshot", 20_000L),
    FIND("find", 20_000L),
    CLICK("click", 20_000L),
    TYPE("type", 20_000L),
    READ("read", 20_000L),
    NAV("nav", 25_000L),
    SCROLL("scroll", 10_000L);

    companion object {
        fun fromWire(value: String?): DshBrowserOp? = entries.firstOrNull { it.wire == value }
    }
}

/** `nav` sub-operation. */
enum class DshBrowserNavOp(val wire: String) {
    BACK("back"),
    FORWARD("forward"),
    RELOAD("reload"),
    CLOSE("close");

    companion object {
        fun fromWire(value: String?): DshBrowserNavOp? = entries.firstOrNull { it.wire == value }
    }
}

/** `scroll` direction. */
enum class DshBrowserScrollDirection(val wire: String) {
    DOWN("down"),
    UP("up"),
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromWire(value: String?): DshBrowserScrollDirection? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Strictly typed arguments of one operation. */
sealed interface DshBrowserArgs {
    data object Caps : DshBrowserArgs

    data class Open(val url: String, val newTab: Boolean) : DshBrowserArgs

    data object TabsList : DshBrowserArgs

    /**
     * [tabId] is tolerated because the official package forwards a `tabId` parameter to every
     * `tabs` sub-op; it carries no meaning for `new` and the session never applies it.
     */
    data class TabsNew(val url: String?, val front: Boolean, val tabId: String?) : DshBrowserArgs

    data class TabsSwitch(val tabId: String) : DshBrowserArgs

    data class TabsClose(val tabId: String?) : DshBrowserArgs

    data class Snapshot(val since: Long?, val includeText: Boolean) : DshBrowserArgs

    data class Find(val query: String, val role: String?, val limit: Int) : DshBrowserArgs

    data class Click(val ref: String, val tabId: String?) : DshBrowserArgs

    data class TypeAction(
        val ref: String,
        val text: String,
        val replace: Boolean,
        val tabId: String?
    ) : DshBrowserArgs

    data class Read(val ref: String?, val tabId: String?) : DshBrowserArgs

    data class Nav(val op: DshBrowserNavOp) : DshBrowserArgs

    data class Scroll(val direction: DshBrowserScrollDirection, val amount: Int) : DshBrowserArgs
}

/** One validated call: operation, typed args and the effective per-call timeout. */
data class DshBrowserCall(
    val op: DshBrowserOp,
    val args: DshBrowserArgs,
    val timeoutMillis: Long
)

/** Parse result: never an exception, always a typed answer. */
sealed interface DshBrowserCallResult {
    data class Parsed(val call: DshBrowserCall) : DshBrowserCallResult

    data class Invalid(val failure: DshBrowserFailure) : DshBrowserCallResult
}

/**
 * Strict parser of the `/browser` POST envelope `{op, args, timeout_ms}`.
 *
 * Strictness is deliberate: an undeclared `args` key or a wrong JSON type is rejected instead of
 * silently ignored, because a silently dropped `tabId`/`ref` is exactly how an AI ends up acting on
 * the wrong element. The only tolerated redundancy is `tabs.new.tabId` (the official package
 * forwards it uniformly, documented on [DshBrowserArgs.TabsNew]).
 *
 * No value is ever echoed into a failure message: page- or caller-controlled text must not be able
 * to place itself in the model-visible response.
 */
object DshBrowserCallParser {

    private val TOP_LEVEL_KEYS = setOf("op", "args", "timeout_ms", "request_id")

    private val ARG_KEYS: Map<DshBrowserOp, Set<String>> = mapOf(
        DshBrowserOp.CAPS to emptySet(),
        DshBrowserOp.OPEN to setOf("url", "newTab"),
        DshBrowserOp.TABS_LIST to emptySet(),
        DshBrowserOp.TABS_NEW to setOf("url", "front", "tabId"),
        DshBrowserOp.TABS_SWITCH to setOf("tabId"),
        DshBrowserOp.TABS_CLOSE to setOf("tabId"),
        DshBrowserOp.SNAPSHOT to setOf("since", "includeText"),
        DshBrowserOp.FIND to setOf("query", "role", "limit"),
        DshBrowserOp.CLICK to setOf("ref", "tabId"),
        DshBrowserOp.TYPE to setOf("ref", "text", "replace", "tabId"),
        DshBrowserOp.READ to setOf("ref", "tabId"),
        DshBrowserOp.NAV to setOf("op"),
        DshBrowserOp.SCROLL to setOf("direction", "amount")
    )

    fun parse(bodyJson: String?): DshBrowserCallResult {
        if (bodyJson == null || bodyJson.isBlank()) return invalid("the request body is empty")
        if (bodyJson.length > DshBrowserLimits.MAX_REQUEST_BYTES) {
            return DshBrowserCallResult.Invalid(
                browserFailure(
                    DshBrowserFailureReason.TOO_LARGE,
                    "The browser request body exceeds the contract limit"
                )
            )
        }
        val root = try {
            JSONObject(bodyJson)
        } catch (error: JSONException) {
            return invalid("the request body is not a JSON object")
        }
        for (key in root.keys()) {
            if (key !in TOP_LEVEL_KEYS) return invalid("the request envelope carries an undeclared field")
        }
        val opText = stringOrNull(root, "op")
            ?: return invalid("the envelope needs a string 'op'")
        val op = DshBrowserOp.fromWire(opText)
            ?: return DshBrowserCallResult.Invalid(
                browserFailure(
                    DshBrowserFailureReason.BAD_OP,
                    "The browser operation is not one of the 10 official tools",
                    "use caps/open/tabs/snapshot/find/click/type/read/nav/scroll"
                )
            )
        val requestId = rawOrNull(root, "request_id")
        if (requestId != null) {
            if (requestId !is String || requestId.isBlank() || requestId.length > 64) {
                return invalid("'request_id' must be an opaque 1..64 character string")
            }
        }
        val timeout = parseTimeout(root, op) ?: return invalid("'timeout_ms' must be an integer in the accepted range")

        val argsObject = when (val rawArgs = rawOrNull(root, "args")) {
            null -> JSONObject()
            is JSONObject -> rawArgs
            else -> return invalid("'args' must be a JSON object")
        }
        val allowed = ARG_KEYS.getValue(op)
        for (key in argsObject.keys()) {
            if (key !in allowed) {
                return invalid("args.$key is not declared for operation '${op.wire}'")
            }
        }
        val args = try {
            parseArgs(op, argsObject)
        } catch (error: BadArgument) {
            return invalid(error.detail)
        }
        return DshBrowserCallResult.Parsed(DshBrowserCall(op, args, timeout))
    }

    private fun parseArgs(op: DshBrowserOp, args: JSONObject): DshBrowserArgs = when (op) {
        DshBrowserOp.CAPS -> DshBrowserArgs.Caps
        DshBrowserOp.OPEN -> DshBrowserArgs.Open(
            url = requiredString(args, "url", DshBrowserLimits.MAX_URL_CHARS),
            newTab = booleanOr(args, "newTab", false)
        )
        DshBrowserOp.TABS_LIST -> DshBrowserArgs.TabsList
        DshBrowserOp.TABS_NEW -> DshBrowserArgs.TabsNew(
            url = optionalString(args, "url", DshBrowserLimits.MAX_URL_CHARS),
            front = booleanOr(args, "front", true),
            tabId = optionalString(args, "tabId", DshBrowserLimits.MAX_TAB_ID_CHARS)
        )
        DshBrowserOp.TABS_SWITCH -> DshBrowserArgs.TabsSwitch(
            tabId = requiredString(args, "tabId", DshBrowserLimits.MAX_TAB_ID_CHARS)
        )
        DshBrowserOp.TABS_CLOSE -> DshBrowserArgs.TabsClose(
            tabId = optionalString(args, "tabId", DshBrowserLimits.MAX_TAB_ID_CHARS)
        )
        DshBrowserOp.SNAPSHOT -> DshBrowserArgs.Snapshot(
            since = optionalNonNegativeLong(args, "since"),
            includeText = booleanOr(args, "includeText", false)
        )
        DshBrowserOp.FIND -> DshBrowserArgs.Find(
            query = requiredString(args, "query", DshBrowserLimits.MAX_QUERY_CHARS),
            role = optionalString(args, "role", DshBrowserLimits.MAX_ROLE_CHARS),
            limit = optionalInt(args, "limit", DshBrowserLimits.DEFAULT_FIND_LIMIT)
                .coerceIn(1, DshBrowserLimits.MAX_FIND_LIMIT)
        )
        DshBrowserOp.CLICK -> DshBrowserArgs.Click(
            ref = requiredString(args, "ref", DshBrowserLimits.MAX_REF_CHARS),
            tabId = optionalString(args, "tabId", DshBrowserLimits.MAX_TAB_ID_CHARS)
        )
        DshBrowserOp.TYPE -> DshBrowserArgs.TypeAction(
            ref = requiredString(args, "ref", DshBrowserLimits.MAX_REF_CHARS),
            text = requiredText(args, "text", DshBrowserLimits.MAX_TYPE_CHARS),
            replace = booleanOr(args, "replace", true),
            tabId = optionalString(args, "tabId", DshBrowserLimits.MAX_TAB_ID_CHARS)
        )
        DshBrowserOp.READ -> DshBrowserArgs.Read(
            ref = optionalString(args, "ref", DshBrowserLimits.MAX_REF_CHARS),
            tabId = optionalString(args, "tabId", DshBrowserLimits.MAX_TAB_ID_CHARS)
        )
        DshBrowserOp.NAV -> DshBrowserArgs.Nav(
            op = DshBrowserNavOp.fromWire(requiredString(args, "op", 16))
                ?: throw BadArgument("args.op must be back / forward / reload / close")
        )
        DshBrowserOp.SCROLL -> DshBrowserArgs.Scroll(
            direction = DshBrowserScrollDirection.fromWire(requiredString(args, "direction", 16))
                ?: throw BadArgument("args.direction must be down / up / top / bottom"),
            amount = optionalInt(args, "amount", DshBrowserLimits.DEFAULT_SCROLL_AMOUNT)
                .coerceIn(1, DshBrowserLimits.MAX_SCROLL_AMOUNT)
        )
    }

    private fun parseTimeout(root: JSONObject, op: DshBrowserOp): Long? {
        val raw = rawOrNull(root, "timeout_ms") ?: return op.defaultTimeoutMillis
        if (raw !is Number) return null
        val asDouble = raw.toDouble()
        if (!asDouble.isFinite() || asDouble % 1.0 != 0.0) return null
        val value = asDouble.toLong()
        if (value < DshBrowserLimits.MIN_TIMEOUT_MILLIS || value > DshBrowserLimits.MAX_TIMEOUT_MILLIS) {
            return null
        }
        return value
    }

    private class BadArgument(val detail: String) : Exception(detail)

    private fun invalid(detail: String): DshBrowserCallResult.Invalid = DshBrowserCallResult.Invalid(
        browserFailure(DshBrowserFailureReason.BAD_ARGS, "The browser request is malformed", detail)
    )

    /** JSON `null` and an absent key are both "not supplied". */
    private fun rawOrNull(source: JSONObject, key: String): Any? =
        if (!source.has(key) || source.isNull(key)) null else source.get(key)

    private fun stringOrNull(source: JSONObject, key: String): String? =
        rawOrNull(source, key) as? String

    private fun requiredString(source: JSONObject, key: String, maxChars: Int): String {
        val value = stringOrNull(source, key)
            ?: throw BadArgument("args.$key must be a string")
        if (value.isBlank()) throw BadArgument("args.$key must not be blank")
        if (value.length > maxChars) throw BadArgument("args.$key exceeds $maxChars characters")
        return value
    }

    private fun optionalString(source: JSONObject, key: String, maxChars: Int): String? {
        if (rawOrNull(source, key) == null) return null
        val value = stringOrNull(source, key) ?: throw BadArgument("args.$key must be a string")
        if (value.isBlank()) throw BadArgument("args.$key must not be blank")
        if (value.length > maxChars) throw BadArgument("args.$key exceeds $maxChars characters")
        return value
    }

    /** `type` accepts the empty string (it means "clear the field"). */
    private fun requiredText(source: JSONObject, key: String, maxChars: Int): String {
        val raw = rawOrNull(source, key) ?: throw BadArgument("args.$key must be a string")
        val value = raw as? String ?: throw BadArgument("args.$key must be a string")
        if (value.length > maxChars) throw BadArgument("args.$key exceeds $maxChars characters")
        return value
    }

    private fun booleanOr(source: JSONObject, key: String, default: Boolean): Boolean {
        val raw = rawOrNull(source, key) ?: return default
        return raw as? Boolean ?: throw BadArgument("args.$key must be a boolean")
    }

    private fun optionalInt(source: JSONObject, key: String, default: Int): Int {
        val raw = rawOrNull(source, key) ?: return default
        if (raw !is Number) throw BadArgument("args.$key must be a number")
        val value = raw.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0) throw BadArgument("args.$key must be an integer")
        if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) {
            throw BadArgument("args.$key is out of range")
        }
        return value.toInt()
    }

    private fun optionalNonNegativeLong(source: JSONObject, key: String): Long? {
        val raw = rawOrNull(source, key) ?: return null
        if (raw !is Number) throw BadArgument("args.$key must be a number")
        val value = raw.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0) throw BadArgument("args.$key must be an integer")
        if (value < 0.0) throw BadArgument("args.$key must be >= 0")
        return value.toLong()
    }

    /** Kept for the encoder/tests: a JSON array of the envelope's declared top-level keys. */
    fun declaredTopLevelKeys(): JSONArray = JSONArray(TOP_LEVEL_KEYS.sorted())
}
