package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model-facing screen tools backed by the accessibility lane.
 *
 * The app already ships a complete accessibility engine — `DshA11yController` drives real
 * `ACTION_CLICK`, `ACTION_SET_TEXT`, `dispatchGesture` and global actions through the user-granted
 * service, and its twelve routes are mounted in the DSH capability bridge. The agent side simply
 * had no tool that reached it, so an agent could read and write files but could not look at, or act
 * on, the phone it runs on. This tool closes that gap without a second implementation: every call
 * goes through [AgentScreenPort], whose production adapter forwards to the same engine the DSH lane
 * uses.
 *
 * Why a port instead of calling the controller directly: the tool layer must stay testable on the
 * JVM, and the controller is an Android-service singleton. The port also states the contract that
 * matters here — a tool reports *unavailable* rather than claiming success when the service is not
 * enabled.
 *
 * One tool with an `action` discriminator instead of six tools: the model chooses an operation over
 * the same subject (the screen), and a single schema keeps the approval story identical for every
 * action the user is asked about.
 */
class AgentScreenTool(
    private val port: AgentScreenPort
) : AgentToolExecutor {

    override val descriptor: AgentToolDescriptor = DESCRIPTOR

    override suspend fun execute(
        command: FrozenToolCommand,
        context: AgentToolContext
    ): AgentToolResult =
        runBuiltInTool(command, descriptor, context, requiresReadGrant = false) {
            val action = parseAction(command.normalizedArgumentsJson)
                ?: return@runBuiltInTool AgentToolResults.failure(
                    command,
                    descriptor,
                    "SCREEN_ARGUMENTS_INVALID",
                    "The screen tool needs a supported action and its matching fields."
                )
            when (val outcome = port.act(action)) {
                is AgentScreenOutcome.Observed -> {
                    val image = outcome.imageDataUrl?.takeIf { candidate ->
                        // Decide here, not in the envelope builder: a screenshot over the budget would
                        // push the whole observation into MODEL_CAP truncation, which drops the text
                        // too. Dropping just the image keeps the observation readable.
                        AgentToolJson.utf8Size(candidate) <= MAX_IMAGE_DATA_URL_BYTES
                    }
                    AgentToolResults.success(
                        command = command,
                        descriptor = descriptor,
                        summary = if (image == null) {
                            "Screen captured."
                        } else {
                            "Screen captured with a screenshot."
                        },
                        data = JSONObject().put("text", outcome.text).also { data ->
                            if (image != null) {
                                data.put("imageDataUrl", image)
                            } else if (outcome.imageDataUrl != null) {
                                data.put("imageDropped", true)
                            }
                        },
                        // Second safety net: if the persisted envelope is still over its own cap,
                        // the image is the first thing to go.
                        shrink = { data ->
                            if (data.has("imageDataUrl")) {
                                data.remove("imageDataUrl")
                                data.put("imageDropped", true)
                                true
                            } else {
                                false
                            }
                        }
                    )
                }

                AgentScreenOutcome.ServiceDisabled -> AgentToolResults.failure(
                    command,
                    descriptor,
                    AgentScreenErrors.SERVICE_DISABLED,
                    "The accessibility service is not enabled, so the screen cannot be read or " +
                        "operated. Ask the user to enable the MCA accessibility service in settings."
                )

                is AgentScreenOutcome.Failed -> AgentToolResults.failure(
                    command,
                    descriptor,
                    AgentScreenErrors.SCREEN_UNAVAILABLE,
                    outcome.reason
                )
            }
        }

    private fun parseAction(normalizedArgumentsJson: String): AgentScreenAction? {
        val root = runCatching { JSONObject(normalizedArgumentsJson) }.getOrNull() ?: return null
        return when (val action = root.optString("action").trim().lowercase()) {
            "read" -> AgentScreenAction.ReadScreen
            "screenshot" -> AgentScreenAction.ReadScreenshot
            "tap" -> AgentScreenAction.Tap(
                x = root.optInt("x", -1),
                y = root.optInt("y", -1),
                text = root.optString("text").trim()
            )

            "input" -> AgentScreenAction.InputText(root.optString("text"))
            "scroll" -> AgentScreenAction.Scroll(
                forward = root.optBoolean("forward", true),
                distance = root.optInt("distance", 0)
            )

            "back" -> AgentScreenAction.GlobalBack
            "home" -> AgentScreenAction.GlobalHome
            else -> {
                if (action.isEmpty()) null else AgentScreenAction.Rejected("unknown action '$action'")
            }
        }.takeIf { it !is AgentScreenAction.Rejected }
    }

    companion object {
        const val TOOL_KEY: String = "device.screen"
        const val MAX_TEXT_CHARS: Int = 4_096

        /**
         * Largest screenshot `data:` URL this tool will put in a result.
         *
         * Deliberately well under the 16 KiB model budget: the text half must still fit beside it,
         * and the envelope adds its own keys, so a value near the cap would truncate the whole
         * observation instead of just the image.
         */
        const val MAX_IMAGE_DATA_URL_BYTES: Int = 8_000

        private val SCHEMA_JSON: String = AgentToolJson.canonical(JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray().put("action"))
            .put(
                "properties",
                JSONObject()
                    .put(
                        "action",
                        JSONObject().put("type", "string").put(
                            "enum",
                            JSONArray()
                                .put("read")
                                .put("screenshot")
                                .put("tap")
                                .put("input")
                                .put("scroll")
                                .put("back")
                                .put("home")
                        )
                    )
                    .put("x", JSONObject().put("type", "integer").put("minimum", 0))
                    .put("y", JSONObject().put("type", "integer").put("minimum", 0))
                    .put("text", JSONObject().put("type", "string").put("maxLength", MAX_TEXT_CHARS))
                    .put("forward", JSONObject().put("type", "boolean"))
                    .put("distance", JSONObject().put("type", "integer").put("minimum", 0))
            )
        )

        val DESCRIPTOR: AgentToolDescriptor = AgentToolDescriptor(
            toolKey = TOOL_KEY,
            version = "1",
            displayName = "Phone Screen",
            description = "Read what is on the phone screen and act on it. read returns the visible " +
                "text and controls; screenshot also returns the pixels as a data:image/jpeg;base64 " +
                "URL, for screens with no readable controls (games, self-drawn UI) — it falls back " +
                "to the text when the image is unavailable. tap presses a point (x,y) or a " +
                "control by its text; input types into the focused field; scroll moves a scrollable " +
                "area; back and home press the system actions. Requires the user to enable the MCA " +
                "accessibility service. Every action is a real interaction with the phone.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/** Outcomes the screen port may report; [Observed] carries the model-facing text. */
sealed interface AgentScreenOutcome {
    /**
     * A successful observation or action.
     *
     * @param text what the model reads. Never blank.
     * @param imageDataUrl a `data:image/jpeg;base64,...` screenshot, when the port could produce one.
     *   Null is normal: the visual half is a best-effort extra, and its absence never turns a
     *   readable screen into a failure.
     */
    data class Observed(val text: String, val imageDataUrl: String? = null) : AgentScreenOutcome
    data object ServiceDisabled : AgentScreenOutcome
    data class Failed(val reason: String) : AgentScreenOutcome
}

/** The one operation set the screen tool can request. */
sealed interface AgentScreenAction {
    /** Text-only observation: the accessibility tree, no pixels. */
    data object ReadScreen : AgentScreenAction

    /**
     * Observation that also needs the pixels.
     *
     * A separate action instead of a `ReadScreen(includeImage)` parameter, because the two requests
     * have different costs and different failure modes: `read` never touches the screenshot path
     * (cheap, always available while the service is bound), while `screenshot` spends a real capture,
     * a PNG write and a JPEG re-encode, and may legitimately fail while the text half still works.
     * Modelling that as its own action keeps the schema enum self-describing (`"action":"screenshot"`),
     * keeps the retry story honest for the model, and leaves `read` byte-identical to before, so no
     * older caller or prompt changes behaviour. The port may still fall back to the text dump — that
     * is a degradation *inside* the action, not a shape change of it.
     */
    data object ReadScreenshot : AgentScreenAction
    data class Tap(val x: Int, val y: Int, val text: String) : AgentScreenAction
    data class InputText(val text: String) : AgentScreenAction
    data class Scroll(val forward: Boolean, val distance: Int) : AgentScreenAction
    data object GlobalBack : AgentScreenAction
    data object GlobalHome : AgentScreenAction
    data class Rejected(val reason: String) : AgentScreenAction
}

/** Error tokens the tool reports; stable so the model can branch on them. */
object AgentScreenErrors {
    const val SERVICE_DISABLED: String = "SCREEN_SERVICE_DISABLED"
    const val SCREEN_UNAVAILABLE: String = "SCREEN_UNAVAILABLE"
}

/**
 * Seam between the tool and the accessibility engine.
 *
 * Production forwards to `DshA11yController` through its route table, so there is exactly one
 * implementation of tapping and typing in the app. A JVM test supplies a fake.
 */
fun interface AgentScreenPort {
    suspend fun act(action: AgentScreenAction): AgentScreenOutcome
}
