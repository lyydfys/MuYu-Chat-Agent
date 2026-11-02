package com.muyuchat.core.dsh.vscreen

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import kotlinx.coroutines.CancellationException

/**
 * The eight vscreen routes of the official `dsh-tool-vscreen` package.
 *
 * The package sends every action as a GET with a flat query string and reads a JSON document with
 * an `ok` flag. The envelope below is therefore frozen:
 *
 * - success: `{"ok":true, ...action fields...}`
 * - failure: `{"ok":false,"error":"<single-line message>","errorCode":"<stable code>", ...diagnostics}`
 *
 * Diagnostics (`displayId`, `hasFrame`, `waitedMs`, `attempts`, `hint`) are additive and match the
 * fields the package itself produces for `see`, so a future package version can use them without a
 * second error channel. The handler never reads [DshCapabilityRouteRequest.localToken], and every
 * value written into the body comes from a typed result — never from the raw query.
 */
class DshVscreenRoutes(private val control: DshVscreenControl) {

    fun register(builder: DshCapabilityRouteTable.Builder): DshCapabilityRouteTable.Builder {
        builder.route(DshVscreenAction.CREATE.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.CREATE) })
        builder.route(DshVscreenAction.STATUS.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.STATUS) })
        builder.route(DshVscreenAction.LAUNCH.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.LAUNCH) })
        builder.route(DshVscreenAction.SEE.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.SEE) })
        builder.route(DshVscreenAction.TAP.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.TAP) })
        builder.route(DshVscreenAction.SWIPE.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.SWIPE) })
        builder.route(DshVscreenAction.KEY.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.KEY) })
        builder.route(DshVscreenAction.CLOSE.route, DshCapabilityRouteHandler { dispatch(it, DshVscreenAction.CLOSE) })
        return builder
    }

    private suspend fun dispatch(
        request: DshCapabilityRouteRequest,
        action: DshVscreenAction
    ): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.GET) {
            return json(405, failureBody(DshVscreenErrorCode.INVALID_REQUEST, "virtual-screen routes accept GET only"))
        }
        return try {
            when (action) {
                DshVscreenAction.CREATE -> create(request.query)
                DshVscreenAction.STATUS -> status(request.query)
                DshVscreenAction.LAUNCH -> launch(request.query)
                DshVscreenAction.SEE -> see(request.query)
                DshVscreenAction.TAP -> tap(request.query)
                DshVscreenAction.SWIPE -> swipe(request.query)
                DshVscreenAction.KEY -> key(request.query)
                DshVscreenAction.CLOSE -> close(request.query)
            }
        } catch (validation: DshVscreenValidationException) {
            json(200, failureBody(validation.code, validation.message ?: "invalid virtual-screen request"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            json(200, failureBody(DshVscreenErrorCode.INTERNAL, "the virtual-screen handler failed"))
        }
    }

    private suspend fun create(query: Map<String, String>): DshCapabilityRouteResponse {
        val request = DshVscreenCreateRequest.parse(query)
        return when (val result = control.create(request)) {
            is DshVscreenResult.Success -> json(200, DshVscreenJsonObject().apply {
                boolean("ok", true)
                number("displayId", result.value.displayId)
                number("width", result.value.geometry.width)
                number("height", result.value.geometry.height)
                number("dpi", result.value.geometry.dpi)
                string("orientation", result.value.geometry.orientation.wire)
                boolean("reused", result.value.reused)
                result.value.hint?.let { string("hint", it) }
            }.build())

            is DshVscreenResult.Failure -> json(200, failureBody(result))
        }
    }

    private suspend fun status(query: Map<String, String>): DshCapabilityRouteResponse {
        rejectUnknownKeys(query, emptySet())
        return when (val result = control.status()) {
            is DshVscreenResult.Success -> json(200, DshVscreenJsonObject().apply {
                boolean("ok", true)
                number("displayId", result.value.displayId)
                number("width", result.value.geometry?.width ?: 0)
                number("height", result.value.geometry?.height ?: 0)
                boolean("running", result.value.running)
                boolean("hasFrame", result.value.hasFrame)
            }.build())

            is DshVscreenResult.Failure -> json(200, failureBody(result))
        }
    }

    private suspend fun launch(query: Map<String, String>): DshCapabilityRouteResponse {
        val request = DshVscreenLaunchRequest.parse(query)
        return when (val result = control.launch(request)) {
            is DshVscreenResult.Success -> json(200, DshVscreenJsonObject().apply {
                boolean("ok", true)
                string("package", result.value.packageName)
                string("component", result.value.component ?: "")
                number("displayId", result.value.displayId)
                result.value.hint?.let { string("hint", it) }
            }.build())

            is DshVscreenResult.Failure -> json(200, failureBody(result))
        }
    }

    private suspend fun see(query: Map<String, String>): DshCapabilityRouteResponse {
        val request = DshVscreenSeeRequest.parse(query)
        return when (val result = control.see(request)) {
            is DshVscreenResult.Success -> json(200, DshVscreenJsonObject().apply {
                boolean("ok", true)
                string("path", result.value.path)
                number("displayId", result.value.displayId)
                number("screenW", result.value.screenWidth)
                number("screenH", result.value.screenHeight)
                number("imageW", result.value.imageWidth)
                number("imageH", result.value.imageHeight)
                decimal("scaleX", result.value.scaleX)
                decimal("scaleY", result.value.scaleY)
            }.build())

            is DshVscreenResult.Failure -> json(200, failureBody(result))
        }
    }

    private suspend fun tap(query: Map<String, String>): DshCapabilityRouteResponse {
        val request = DshVscreenTapRequest.parse(query)
        return actionResult(control.tap(request))
    }

    private suspend fun swipe(query: Map<String, String>): DshCapabilityRouteResponse {
        val request = DshVscreenSwipeRequest.parse(query)
        return actionResult(control.swipe(request))
    }

    private suspend fun key(query: Map<String, String>): DshCapabilityRouteResponse {
        val request = DshVscreenKeyRequest.parse(query)
        return actionResult(control.key(request))
    }

    private suspend fun close(query: Map<String, String>): DshCapabilityRouteResponse {
        rejectUnknownKeys(query, emptySet())
        return actionResult(control.close())
    }

    private fun actionResult(result: DshVscreenResult<Unit>): DshCapabilityRouteResponse = when (result) {
        is DshVscreenResult.Success -> json(200, DshVscreenJsonObject().apply { boolean("ok", true) }.build())
        is DshVscreenResult.Failure -> json(200, failureBody(result))
    }

    private fun failureBody(failure: DshVscreenResult.Failure): String = DshVscreenJsonObject().apply {
        boolean("ok", false)
        string("error", failure.message)
        string("errorCode", failure.code.wire)
        failure.displayId?.let { number("displayId", it) }
        failure.hasFrame?.let { boolean("hasFrame", it) }
        failure.waitedMs?.let { number("waitedMs", it) }
        failure.attempts?.let { number("attempts", it) }
        failure.hint?.let { string("hint", it) }
    }.build()

    private fun failureBody(code: DshVscreenErrorCode, message: String): String =
        failureBody(DshVscreenResult.Failure(code = code, message = message))

    private fun json(statusCode: Int, bodyJson: String): DshCapabilityRouteResponse =
        DshCapabilityRouteResponse.Json(statusCode, bodyJson)

    companion object {
        /** All eight official virtual-screen routes, in registration order. */
        val routes: List<String> get() = DshVscreenAction.routes

        fun install(builder: DshCapabilityRouteTable.Builder, control: DshVscreenControl): DshCapabilityRouteTable.Builder =
            DshVscreenRoutes(control).register(builder)

        fun install(control: DshVscreenControl): DshCapabilityRouteTable =
            install(DshCapabilityRouteTable.builder(), control).build()
    }
}

/** Minimal deterministic JSON writer: no dependency, no locale-sensitive number formatting. */
internal class DshVscreenJsonObject {
    private val fields = ArrayList<String>()

    fun string(name: String, value: String?): DshVscreenJsonObject {
        fields += quote(name) + ":" + if (value == null) "null" else quote(value)
        return this
    }

    fun number(name: String, value: Int): DshVscreenJsonObject {
        fields += quote(name) + ":" + value
        return this
    }

    fun number(name: String, value: Long): DshVscreenJsonObject {
        fields += quote(name) + ":" + value
        return this
    }

    fun decimal(name: String, value: Double): DshVscreenJsonObject {
        val safe = if (value.isFinite()) value else 1.0
        fields += quote(name) + ":" + safe
        return this
    }

    fun boolean(name: String, value: Boolean): DshVscreenJsonObject {
        fields += quote(name) + ":" + if (value) "true" else "false"
        return this
    }

    fun build(): String = fields.joinToString(separator = ",", prefix = "{", postfix = "}")

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
