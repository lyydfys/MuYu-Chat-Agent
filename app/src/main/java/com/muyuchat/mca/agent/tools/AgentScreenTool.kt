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
                is AgentScreenOutcome.Observed -> AgentToolResults.success(
                    command = command,
                    descriptor = descriptor,
                    summary = "Screen captured.",
                    data = JSONObject().put("text", outcome.text)
                )

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
                "text and controls; tap presses a point (x,y) or a control by its text; input types " +
                "into the focused field; scroll moves a scrollable area; back and home press the " +
                "system actions. Requires the user to enable the MCA accessibility service. Every " +
                "action is a real interaction with the phone.",
            schemaJson = SCHEMA_JSON,
            schemaHash = AgentToolJson.sha256Hex(SCHEMA_JSON),
            sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            dataClass = AgentDataClass.PRIVATE
        )
    }
}

/** Outcomes the screen port may report; [Observed] carries the model-facing text. */
sealed interface AgentScreenOutcome {
    data class Observed(val text: String) : AgentScreenOutcome
    data object ServiceDisabled : AgentScreenOutcome
    data class Failed(val reason: String) : AgentScreenOutcome
}

/** The one operation set the screen tool can request. */
sealed interface AgentScreenAction {
    data object ReadScreen : AgentScreenAction
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
