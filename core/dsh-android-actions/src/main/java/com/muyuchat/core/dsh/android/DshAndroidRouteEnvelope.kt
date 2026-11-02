package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A request that failed typed validation. It is not an adapter defect: the route converts it into
 * a deterministic `{ "ok": false, "error": "CODE: message" }` body. Anything else thrown out of a
 * handler is a real defect and must reach the platform server as an error, never be swallowed.
 */
internal class DshAndroidBadRequest(val failure: DshAndroidActionFailure) :
    Exception(failure.wireMessage)

/**
 * Strict JSON access shared by the six App-local Android routes.
 *
 * The routes are called by the official tool packages, which always send typed JSON bodies, so a
 * value of the wrong JSON type is a caller defect we reject instead of coercing. `optString`-style
 * coercion is exactly what let the reference App read a `write` request as a silent `read`.
 *
 * Every response is produced here as one compact JSON object with only the keys the pinned tool
 * output schema allows (`additionalProperties: false`), so no helper may add a field by itself.
 */
internal object DshAndroidWire {
    const val STATUS_OK = 200

    fun response(body: JSONObject): DshCapabilityRouteResponse =
        DshCapabilityRouteResponse.Json(STATUS_OK, body.toString())

    /** Parses a body that must be a JSON object; anything else is an invalid request. */
    fun parseObject(raw: String?): JSONObject {
        if (raw == null || raw.isBlank()) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "请求体不能为空")
        }
        val parsed = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "请求体必须是合法 JSON 对象")
        }
        return parsed
    }

    /** A required JSON string with an exact type check and a hard length cap. */
    fun requireString(
        body: JSONObject,
        name: String,
        maxChars: Int,
        allowBlank: Boolean = false
    ): String {
        val value = optionalString(body, name, maxChars)
            ?: throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "缺少字符串参数 $name")
        if (!allowBlank && value.isBlank()) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "参数 $name 不能为空")
        }
        return value
    }

    fun optionalString(body: JSONObject, name: String, maxChars: Int): String? {
        if (!body.has(name) || body.isNull(name)) return null
        val value = body.opt(name)
        if (value !is String) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "参数 $name 必须是字符串")
        }
        if (value.length > maxChars) {
            throw badRequest(
                DshAndroidActionErrorCode.TOO_LARGE,
                "参数 $name 超过 $maxChars 字符上限"
            )
        }
        return value
    }

    fun optionalInt(body: JSONObject, name: String): Int? {
        if (!body.has(name) || body.isNull(name)) return null
        return asInt(body.opt(name))
            ?: throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "参数 $name 必须是整数")
    }

    fun optionalBoolean(body: JSONObject, name: String): Boolean? {
        if (!body.has(name) || body.isNull(name)) return null
        val value = body.opt(name)
        if (value !is Boolean) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "参数 $name 必须是布尔值")
        }
        return value
    }

    fun array(items: List<Any?>): JSONArray {
        val array = JSONArray()
        items.forEach { array.put(it) }
        return array
    }

    private fun asInt(raw: Any?): Int? = when (raw) {
        is Int -> raw
        is Long -> if (raw >= Int.MIN_VALUE.toLong() && raw <= Int.MAX_VALUE.toLong()) raw.toInt() else null
        is Short -> raw.toInt()
        is Byte -> raw.toInt()
        is Double -> if (raw.isFinite() && raw % 1.0 == 0.0 &&
            raw >= Int.MIN_VALUE.toDouble() && raw <= Int.MAX_VALUE.toDouble()
        ) {
            raw.toInt()
        } else {
            null
        }
        is Float -> if (raw.isFinite() && raw % 1.0f == 0.0f &&
            raw >= Int.MIN_VALUE.toFloat() && raw <= Int.MAX_VALUE.toFloat()
        ) {
            raw.toInt()
        } else {
            null
        }
        is Number -> asInt(raw.toDouble())
        else -> null
    }
}

/**
 * HTTP surface rules shared by the six routes, derived from what the pinned packages really send.
 *
 * `dsh-tool-android` calls `/usage` and `/overlay` with `GET` + query string, and `dsh-tool-shizuku`
 * calls `/notify`, `/setting`, `/clipboard` and `/shell` with `POST` + JSON body. Its own comment
 * records why the POST routes must not read the query: the reference App truncates the request
 * line at `?`, so `POST /clipboard?action=write` used to be executed as a `read`. MCA rejects a
 * query on a POST route (UNEXPECTED_QUERY) instead of silently doing something else.
 */
internal object DshAndroidEnvelope {
    fun requirePost(request: DshCapabilityRouteRequest, path: String) {
        if (request.method != DshCapabilityHttpMethod.POST) {
            throw badRequest(
                DshAndroidActionErrorCode.METHOD_NOT_ALLOWED,
                "$path 只接受 POST"
            )
        }
        if (request.query.isNotEmpty()) {
            throw badRequest(
                DshAndroidActionErrorCode.UNEXPECTED_QUERY,
                "$path 只读取 JSON 请求体，不接受查询参数"
            )
        }
    }

    fun requireGet(
        request: DshCapabilityRouteRequest,
        path: String,
        allowedQueryKeys: Set<String>
    ) {
        if (request.method != DshCapabilityHttpMethod.GET) {
            throw badRequest(
                DshAndroidActionErrorCode.METHOD_NOT_ALLOWED,
                "$path 只接受 GET"
            )
        }
        val unexpected = request.query.keys - allowedQueryKeys
        if (unexpected.isNotEmpty()) {
            throw badRequest(
                DshAndroidActionErrorCode.UNEXPECTED_QUERY,
                "$path 不支持查询参数 " + unexpected.sorted().joinToString(",")
            )
        }
    }

    fun body(request: DshCapabilityRouteRequest): JSONObject =
        DshAndroidWire.parseObject(request.bodyJson)

    fun queryString(
        request: DshCapabilityRouteRequest,
        name: String,
        maxChars: Int
    ): String? {
        val raw = request.query[name] ?: return null
        if (raw.length > maxChars) {
            throw badRequest(
                DshAndroidActionErrorCode.TOO_LARGE,
                "查询参数 $name 超过 $maxChars 字符上限"
            )
        }
        return raw
    }

    fun queryInt(
        request: DshCapabilityRouteRequest,
        name: String,
        minimum: Int,
        maximum: Int,
        default: Int
    ): Int {
        val raw = queryString(request, name, 16) ?: return default
        val value = raw.trim().toIntOrNull()
            ?: throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "查询参数 $name 必须是整数")
        if (value !in minimum..maximum) {
            throw badRequest(
                DshAndroidActionErrorCode.INVALID_REQUEST,
                "查询参数 $name 必须在 $minimum..$maximum 之间"
            )
        }
        return value
    }
}

internal fun badRequest(
    code: DshAndroidActionErrorCode,
    message: String
): DshAndroidBadRequest = DshAndroidBadRequest(DshAndroidActionFailure.of(code, message))

/**
 * One App-local Android route.
 *
 * Subclasses implement [executeTyped] and never worry about HTTP envelope details: method, query
 * and body-shape violations become the route's own typed failure body, so every negative branch
 * keeps the exact key set the pinned tool output schema declares.
 */
abstract class DshAndroidRoute : com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler {

    abstract val path: String

    final override suspend fun handle(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse = try {
        executeTyped(request)
    } catch (bad: DshAndroidBadRequest) {
        DshAndroidWire.response(failureBody(bad.failure))
    }

    protected abstract suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse

    /** Default negative body: `{"ok":false,"error":"CODE: message"}`. */
    protected open fun failureBody(failure: DshAndroidActionFailure): JSONObject =
        JSONObject().put("ok", false).put("error", failure.wireMessage)

    protected fun okBody(): JSONObject = JSONObject().put("ok", true)

    /** Negative response in the route's own failure shape. */
    protected fun refused(failure: DshAndroidActionFailure): DshCapabilityRouteResponse =
        DshAndroidWire.response(failureBody(failure))

    protected fun refused(
        body: JSONObject,
        failure: DshAndroidActionFailure
    ): DshCapabilityRouteResponse = DshAndroidWire.response(
        body.put("ok", false).put("error", failure.wireMessage)
    )
}
